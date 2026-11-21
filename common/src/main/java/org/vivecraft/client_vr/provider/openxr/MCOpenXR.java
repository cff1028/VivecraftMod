package org.vivecraft.client_vr.provider.openxr;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.vivecraft.client.VivecraftVRMod;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction;
import org.vivecraft.client_vr.provider.openvr_lwjgl.control.VRInputActionSet;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class MCOpenXR extends MCVR {

    private static MCOpenXR OME;
    public XrInstance instance;
    public XrSession session;
    public XrSpace xrAppSpace;
    public XrSpace xrViewSpace;
    public XrSwapchain swapchain;
    public long swapchainFormat;
    public final XrEventDataBuffer eventDataBuffer = XrEventDataBuffer.calloc();
    public long time;
    private boolean tried;
    private long systemID;
    public XrView.Buffer viewBuffer;
    public int width;
    public int height;

    private final Map<VRInputActionSet, Long> actionSetHandles = new EnumMap<>(VRInputActionSet.class);

    private XrActiveActionSet.Buffer activeActionSetsBuffer;
    private boolean isActive;
    private final HashMap<String, Long> paths = new HashMap<>();
    // Cache opaque source metadata only until the next input sync.
    private final Map<Long, List<Long>> boundSources = new HashMap<>();
    private final Map<Long, String> sourceUserNames = new HashMap<>();
    private String rightInputUser = "";
    private String leftInputUser = "";
    private final long[] grip = new long[2];
    private final long[] aim = new long[2];
    private final XrSpace[] gripSpace = new XrSpace[2];
    private final XrSpace[] aimSpace = new XrSpace[2];
    public static final XrPosef POSE_IDENTITY = XrPosef.calloc().set(
        XrQuaternionf.calloc().set(0, 0, 0, 1),
        XrVector3f.calloc()
    );
    public boolean shouldRender = true;
    public boolean frameStarted;
    private boolean sessionRunning;
    private boolean runtimeStopping;
    public final DeviceCompat device = DeviceCompat.detectDevice();
    public long[] haptics = new long[2];
    public String systemName;
    private boolean destroyed;

    public MCOpenXR(Minecraft mc, ClientDataHolderVR dh) {
        super(mc, dh, VivecraftVRMod.INSTANCE);
        OME = this;
        this.hapticScheduler = new OpenXRHapticScheduler();
        if (!QuestVulkanContext.requested()) GL11.glDisable(GL_FRAMEBUFFER_SRGB);
    }

    @Override
    public String getName() {
        return "OpenXR";
    }

    @Override
    public synchronized void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;

        // Stop haptics before freeing their OpenXR handles.
        ((OpenXRHapticScheduler) this.hapticScheduler).shutdown();
        this.inputInitialized = false;
        this.initialized = false;
        this.isActive = false;

        if (this.frameStarted && this.session != null) {
            try (MemoryStack stack = stackPush()) {
                logError(XR10.xrEndFrame(this.session, XrFrameEndInfo.calloc(stack)
                    .type(XR10.XR_TYPE_FRAME_END_INFO)
                    .displayTime(this.time)
                    .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)), "xrEndFrame", "shutdown");
            }
        }
        this.frameStarted = false;
        if (QuestVulkanContext.requested() && this.sessionRunning && this.session != null) {
            int result = XR10.xrRequestExitSession(this.session);
            logError(result, "xrRequestExitSession", "");
            if (result == XR10.XR_SUCCESS) {
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (this.sessionRunning && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                    pollVREvents();
                    if (this.sessionRunning) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
                }
                if (this.sessionRunning) VRSettings.LOGGER.warn("OpenXR session stop timed out");
            }
        }
        this.sessionRunning = false;

        // Destroy spaces before actions, and swapchains before the session.
        for (int i = 0; i < this.gripSpace.length; i++) {
            if (this.gripSpace[i] != null) {
                logError(XR10.xrDestroySpace(this.gripSpace[i]), "xrDestroySpace", "grip");
                this.gripSpace[i] = null;
            }
            if (this.aimSpace[i] != null) {
                logError(XR10.xrDestroySpace(this.aimSpace[i]), "xrDestroySpace", "aim");
                this.aimSpace[i] = null;
            }
        }
        if (this.swapchain != null) {
            logError(XR10.xrDestroySwapchain(this.swapchain), "xrDestroySwapchain", "");
            this.swapchain = null;
        }
        if (this.viewBuffer != null) {
            this.viewBuffer.close();
            this.viewBuffer = null;
        }
        if (this.xrAppSpace != null) {
            logError(XR10.xrDestroySpace(this.xrAppSpace), "xrDestroySpace", "xrAppSpace");
            this.xrAppSpace = null;
        }
        if (this.xrViewSpace != null) {
            logError(XR10.xrDestroySpace(this.xrViewSpace), "xrDestroySpace", "xrViewSpace");
            this.xrViewSpace = null;
        }
        if (this.session != null) {
            int result = XR10.xrDestroySession(this.session);
            logError(result, "xrDestroySession", "");
            if (QuestVulkanContext.requested()) {
                VRSettings.LOGGER.info("Vivecraft: stopped OpenXR session=0x{} owner=0x{} result={}",
                    Long.toHexString(this.session.address()), Long.toHexString(this.instance.address()), result);
                if (result < 0) QuestVulkanContext.invalidate(this.instance);
            }
            this.session = null;
        }
        if (this.instance != null) {
            for (long actionSet : this.actionSetHandles.values()) {
                if (actionSet != 0L) {
                    logError(XR10.xrDestroyActionSet(new XrActionSet(actionSet, this.instance)),
                        "xrDestroyActionSet", "");
                }
            }
        }
        this.actionSetHandles.clear();
        if (this.instance != null) {
            if (QuestVulkanContext.requested()) QuestVulkanContext.leave(this.instance);
            else logError(XR10.xrDestroyInstance(this.instance), "xrDestroyInstance", "");
            this.instance = null;
        }
        if (this.activeActionSetsBuffer != null) {
            this.activeActionSetsBuffer.close();
            this.activeActionSetsBuffer = null;
        }
        this.paths.clear();
        this.boundSources.clear();
        this.sourceUserNames.clear();
        this.inputActions.values().forEach(VRInputAction::unpressBindingImmediately);
        this.inputActions.clear();
        this.inputActionsByKeyBinding.clear();
        Arrays.fill(this.haptics, 0L);
        Arrays.fill(this.grip, 0L);
        Arrays.fill(this.aim, 0L);
        if (OME == this) OME = null;

        this.eventDataBuffer.close();
        if (!QuestVulkanContext.requested()) GL11.glEnable(GL_FRAMEBUFFER_SRGB);
    }

    @Override
    protected ControllerType findActiveBindingControllerType(KeyMapping keyMapping) {
        VRInputAction action = this.getInputAction(keyMapping);
        return this.inputInitialized && action != null ? this.getInputActionControllerType(action) : null;
    }

    @Override
    public void handleEvents() {
        Profiler.get().push("events");
        this.pollVREvents();
        Profiler.get().pop();
    }

    @Override
    public void poll(long frameIndex) {
        if (this.initialized) this.handleEvents();
        if (this.initialized && this.sessionRunning) {
            if (this.frameStarted && this.dh.vrRenderer instanceof OpenXRStereoRenderer renderer) {
                try {
                    renderer.endFrame(false);
                } catch (org.vivecraft.client_vr.render.RenderConfigException e) {
                    throw new IllegalStateException("Failed to finish the skipped OpenXR frame", e);
                }
            }

            Profiler.get().push("updatePose/Vsync");
            this.updatePose();
            if (!this.sessionRunning) {
                Profiler.get().pop();
                return;
            }
            Profiler.get().popPush("processInputs");
            this.processInputs();
            Profiler.get().popPush("hmdSampling");
            this.hmdSampling();
            Profiler.get().pop();
        }
    }

    private void updatePose() {
        if (this.mc == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrFrameState frameState = XrFrameState.calloc(stack).type(XR10.XR_TYPE_FRAME_STATE);

            int error = XR10.xrWaitFrame(
                this.session,
                XrFrameWaitInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_WAIT_INFO),
                frameState);
            if (this.stopOnLoss(error)) return;
            checkResult(error, "xrWaitFrame");

            this.time = frameState.predictedDisplayTime();
            boolean render = frameState.shouldRender();
            if (this.shouldRender != render) {
                VRSettings.LOGGER.debug("Vivecraft: OpenXR session=0x{} shouldRender={}",
                    Long.toHexString(this.session.address()), render);
            }
            this.shouldRender = render;

            error = XR10.xrBeginFrame(
                this.session,
                XrFrameBeginInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_BEGIN_INFO));
            if (this.stopOnLoss(error)) {
                this.frameStarted = error == XR10.XR_SESSION_LOSS_PENDING;
                return;
            }
            checkResult(error, "xrBeginFrame");
            this.frameStarted = true;

            XrViewState viewState = XrViewState.calloc(stack).type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.callocInt(1);

            XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.calloc(stack);
            viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                0,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                frameState.predictedDisplayTime(),
                this.xrAppSpace
            );

            error = XR10.xrLocateViews(this.session, viewLocateInfo, viewState, intBuf, this.viewBuffer);
            logError(error, "xrLocateViews", "");
            long validViewFlags = XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR10.XR_VIEW_STATE_POSITION_VALID_BIT;
            this.shouldRender &= error >= 0 && intBuf.get(0) == 2 &&
                (viewState.viewStateFlags() & validViewFlags) == validViewFlags;

            XrSpaceLocation space_location = XrSpaceLocation.calloc(stack).type(XR10.XR_TYPE_SPACE_LOCATION);

            error = XR10.xrLocateSpace(this.xrViewSpace, this.xrAppSpace, this.time, space_location);
            logError(error, "xrLocateSpace", "xrViewSpace");
            long validPoseFlags = XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT;
            if (error >= 0 && (space_location.locationFlags() & validPoseFlags) == validPoseFlags) {
                OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.hmdPose);
                this.headIsTracking = true;
            } else {
                this.headIsTracking = false;
                this.hmdPose.identity();
                this.hmdPose.m31(1.6F);
            }

            // Vivecraft expects head-relative eye poses.
            OpenXRUtil.openXRPoseToMarix(this.viewBuffer.get(0).pose(), this.hmdPoseLeftEye);
            OpenXRUtil.openXRPoseToMarix(this.viewBuffer.get(1).pose(), this.hmdPoseRightEye);
            OpenXRPoseMath.eyesToHeadRelative(this.hmdPose, this.hmdPoseLeftEye, this.hmdPoseRightEye);

            if (this.inputInitialized) {
                Profiler.get().push("updateActionState");

                if (this.updateActiveActionSets()) {
                    XrActionsSyncInfo syncInfo = XrActionsSyncInfo.calloc(stack)
                        .type(XR10.XR_TYPE_ACTIONS_SYNC_INFO)
                        .activeActionSets(this.activeActionSetsBuffer);
                    error = XR10.xrSyncActions(this.session, syncInfo);
                    logError(error, "xrSyncActions", "");
                }

                refreshInputOrigins();
                this.inputActions.values().forEach(this::readNewData);

                XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL),
                    this.instance);
                this.readPoseData(this.grip[RIGHT_CONTROLLER], actionSet);
                this.readPoseData(this.grip[LEFT_CONTROLLER], actionSet);
                this.readPoseData(this.aim[RIGHT_CONTROLLER], actionSet);
                this.readPoseData(this.aim[LEFT_CONTROLLER], actionSet);

                Profiler.get().pop();

                int rightHand = this.dh.vrSettings.reverseHands ? LEFT_CONTROLLER : RIGHT_CONTROLLER;
                int leftHand = this.dh.vrSettings.reverseHands ? RIGHT_CONTROLLER : LEFT_CONTROLLER;

                error = XR10.xrLocateSpace(this.gripSpace[rightHand], this.xrAppSpace, this.time,
                    space_location);
                logError(error, "xrLocateSpace", "gripSpace[0]");
                if (error >= 0) {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.handRotation[RIGHT_CONTROLLER]);
                }

                error = XR10.xrLocateSpace(this.gripSpace[leftHand], this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "gripSpace[1]");
                if (error >= 0) {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.handRotation[LEFT_CONTROLLER]);
                }

                error = XR10.xrLocateSpace(this.aimSpace[rightHand], this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "aimSpace[0]");
                if (error >= 0) {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.controllerPose[RIGHT_CONTROLLER]);
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.controllerRotation[RIGHT_CONTROLLER]);
                    this.controllerTracking[RIGHT_CONTROLLER] = true;
                } else {
                    this.controllerTracking[RIGHT_CONTROLLER] = false;
                }

                error = XR10.xrLocateSpace(this.aimSpace[leftHand], this.xrAppSpace, this.time, space_location);
                logError(error, "xrLocateSpace", "aimSpace[1]");
                if (error >= 0) {
                    OpenXRUtil.openXRPoseToMarix(space_location.pose(), this.controllerPose[LEFT_CONTROLLER]);
                    OpenXRUtil.openXRPoseToMarix(space_location.pose().orientation(),
                        this.controllerRotation[LEFT_CONTROLLER]);
                    this.controllerTracking[LEFT_CONTROLLER] = true;
                } else {
                    this.controllerTracking[LEFT_CONTROLLER] = false;
                }
            }

            if(this.controllerTracking[RIGHT_CONTROLLER]) {
                Matrix4fc tip = this.controllerRotation[RIGHT_CONTROLLER];
                Matrix4fc hand = this.handRotation[RIGHT_CONTROLLER];

                Vector3f tipVec = tip.transformDirection(MathUtils.BACK, new Vector3f());
                Vector3f handVec = hand.transformDirection(MathUtils.BACK, new Vector3f());

                float dot = Mth.clamp(Math.abs(tipVec.dot(handVec)), 0.0F, 1.0F);

                float angleRad = (float) Math.acos(dot);
                float angleDeg = Mth.RAD_TO_DEG * angleRad;

                this.gunStyle = angleDeg > 10.0F;
                this.gunAngle = angleDeg;
            }

            this.updateAim();
        }
    }

    private long getActiveOrigin(VRInputAction action, ControllerType hand) {
        List<Long> origins = getOrigins(action);
        if (hand == null) return origins.isEmpty() ? 0L : origins.getFirst();
        for (long origin : origins) {
            if (getOriginControllerType(origin) == hand) return origin;
        }
        return 0L;
    }

    private void refreshInputOrigins() {
        this.boundSources.clear();
        this.sourceUserNames.clear();
        // Resolve physical hands using the runtime labels of the separate aim actions.
        String rightUser = getPoseInputUser(this.aim[RIGHT_CONTROLLER]);
        String leftUser = getPoseInputUser(this.aim[LEFT_CONTROLLER]);
        if (!rightUser.equals(this.rightInputUser) || !leftUser.equals(this.leftInputUser)) {
            VRSettings.LOGGER.info("Vivecraft OpenXR: input source users right='{}', left='{}'; handed input uses queried subaction hand",
                rightUser, leftUser);
        }
        this.rightInputUser = rightUser;
        this.leftInputUser = leftUser;
    }

    private String getPoseInputUser(long actionHandle) {
        List<Long> sources = getBoundSources(actionHandle, VRInputActionSet.GLOBAL);
        return sources.isEmpty() ? "" : getSourceUserName(sources.getFirst());
    }

    private String getSourceUserName(long source) {
        if (source == 0L) return "";
        return this.sourceUserNames.computeIfAbsent(source,
            value -> getLocalizedSourceName(value, XR10.XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT));
    }

    @Override
    public ControllerType getInputActionControllerType(VRInputAction action) {
        // The queried subaction hand remains valid when source metadata is missing.
        return OpenXRInputOrigins.handForAction(action.isHanded() ? action.getCurrentHand() : null,
            action.getLastOrigin(), this::getOriginControllerType);
    }

    public void readNewData(VRInputAction action) {
        switch (action.type) {
            case "boolean" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype1 : ControllerType.values()) {
                        this.readBoolean(action, controllertype1);
                    }
                } else {
                    this.readBoolean(action, null);
                }
            }

            case "vector1" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype : ControllerType.values()) {
                        this.readFloat(action, controllertype);
                    }
                } else {
                    this.readFloat(action, null);
                }
            }

            case "vector2" -> {
                if (action.isHanded()) {
                    for (ControllerType controllertype : ControllerType.values()) {
                        this.readVecData(action, controllertype);
                    }
                } else {
                    this.readVecData(action, null);
                }
            }
        }
    }

    private void readBoolean(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            if (hand != null) {
                boolean right = hand == ControllerType.RIGHT;
                if (this.dh.vrSettings.reverseHands) right = !right;
                info.subactionPath(getPath(right ? "/user/hand/right" : "/user/hand/left"));
            }
            XrActionStateBoolean state = XrActionStateBoolean.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_BOOLEAN);
            int error = XR10.xrGetActionStateBoolean(this.session, info, state);
            logError(error, "xrGetActionStateBoolean", action.name);

            action.digitalData[i].state = state.currentState();
            action.digitalData[i].isActive = state.isActive();
            action.digitalData[i].isChanged = state.changedSinceLastSync();
            action.digitalData[i].activeOrigin = getActiveOrigin(action, hand);
        }
    }

    private void readFloat(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            if (hand != null) {
                boolean right = hand == ControllerType.RIGHT;
                if (this.dh.vrSettings.reverseHands) right = !right;
                info.subactionPath(getPath(right ? "/user/hand/right" : "/user/hand/left"));
            }
            XrActionStateFloat state = XrActionStateFloat.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_FLOAT);
            int error = XR10.xrGetActionStateFloat(this.session, info, state);
            logError(error, "xrGetActionStateFloat", action.name);

            action.analogData[i].deltaX = state.currentState() - action.analogData[i].x;
            action.analogData[i].x = state.currentState();
            action.analogData[i].activeOrigin = getActiveOrigin(action, hand);
            action.analogData[i].isActive = state.isActive();
            action.analogData[i].isChanged = state.changedSinceLastSync();
        }
    }

    private void readVecData(VRInputAction action, ControllerType hand) {
        int i = 0;

        if (hand != null) {
            i = hand.ordinal();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action.handle,
                new XrActionSet(this.actionSetHandles.get(action.actionSet), this.instance)));
            if (hand != null) {
                boolean right = hand == ControllerType.RIGHT;
                if (this.dh.vrSettings.reverseHands) right = !right;
                info.subactionPath(getPath(right ? "/user/hand/right" : "/user/hand/left"));
            }
            XrActionStateVector2f state = XrActionStateVector2f.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_VECTOR2F);
            int error = XR10.xrGetActionStateVector2f(this.session, info, state);
            logError(error, "xrGetActionStateVector2f", action.name);

            action.analogData[i].deltaX = state.currentState().x() - action.analogData[i].x;
            action.analogData[i].deltaY = state.currentState().y() - action.analogData[i].y;
            action.analogData[i].x = state.currentState().x();
            action.analogData[i].y = state.currentState().y();
            action.analogData[i].activeOrigin = getActiveOrigin(action, hand);
            action.analogData[i].isActive = state.isActive();
            action.analogData[i].isChanged = state.changedSinceLastSync();
        }
    }

    private void readPoseData(Long action, XrActionSet set) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionStateGetInfo info = XrActionStateGetInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_STATE_GET_INFO);
            info.action(new XrAction(action, set));
            XrActionStatePose state = XrActionStatePose.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_POSE);
            int error = XR10.xrGetActionStatePose(this.session, info, state);
            logError(error, "xrGetActionStatePose", "");
        }
    }

    private boolean updateActiveActionSets() {
        ArrayList<VRInputActionSet> arraylist = new ArrayList<>();
        arraylist.add(VRInputActionSet.GLOBAL);

        arraylist.add(VRInputActionSet.MOD);

        arraylist.add(VRInputActionSet.MIXED_REALITY);
        arraylist.add(VRInputActionSet.TECHNICAL);

        if (this.mc.gui.screen() == null) {
            arraylist.add(VRInputActionSet.INGAME);
            arraylist.add(VRInputActionSet.CONTEXTUAL);
        } else {
            arraylist.add(VRInputActionSet.GUI);
            if (ClientDataHolderVR.getInstance().vrSettings.ingameBindingsInGui) {
                arraylist.add(VRInputActionSet.INGAME);
            }
        }

        if (KeyboardHandler.SHOWING || RadialHandler.isShowing()) {
            arraylist.add(VRInputActionSet.KEYBOARD);
        }

        if (this.activeActionSetsBuffer == null) {
            this.activeActionSetsBuffer = XrActiveActionSet.calloc(arraylist.size());
        } else if (this.activeActionSetsBuffer.capacity() != arraylist.size()) {
            this.activeActionSetsBuffer.close();
            this.activeActionSetsBuffer = XrActiveActionSet.calloc(arraylist.size());
        }

        for (int i = 0; i < arraylist.size(); ++i) {
            VRInputActionSet vrinputactionset = arraylist.get(i);
            this.activeActionSetsBuffer.get(i)
                .set(new XrActionSet(this.getActionSetHandle(vrinputactionset), this.instance), NULL);
        }

        return !arraylist.isEmpty();
    }

    long getActionSetHandle(VRInputActionSet actionSet) {
        return this.actionSetHandles.get(actionSet);
    }

    private void pollVREvents() {
        while (true) {
            this.eventDataBuffer.clear();
            this.eventDataBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);
            int error = XR10.xrPollEvent(this.instance, this.eventDataBuffer);
            if (error == XR10.XR_ERROR_INSTANCE_LOST) {
                this.stopLostInstance();
                return;
            }
            logError(error, "xrPollEvent", "");
            if (error != XR10.XR_SUCCESS) {
                break;
            }
            XrEventDataBaseHeader event = XrEventDataBaseHeader.create(this.eventDataBuffer.address());

            switch (event.type()) {
                case XR10.XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING -> {
                    this.stopLostInstance();
                    return;
                }
                case XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                    this.sessionChanged(XrEventDataSessionStateChanged.create(event.address()));
                }
                case XR10.XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED -> {
                }
                case XR10.XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING -> {
                }
                default -> {
                }
            }
        }
    }

    private void stopLostInstance() {
        VRSettings.LOGGER.warn("Vivecraft: OpenXR instance loss for owner=0x{}", Long.toHexString(this.instance.address()));
        QuestVulkanContext.invalidate(this.instance);
        this.stopSession();
    }

    boolean stopOnLoss(int result) {
        if (result == XR10.XR_ERROR_INSTANCE_LOST) {
            this.stopLostInstance();
        } else if (result == XR10.XR_ERROR_SESSION_LOST || result == XR10.XR_SESSION_LOSS_PENDING) {
            this.stopSession();
        } else {
            return false;
        }
        return true;
    }

    private void stopSession() {
        this.runtimeStopping = true;
        this.isActive = false;
        this.sessionRunning = false;
        this.shouldRender = false;
        VRState.VR_ENABLED = false;
    }

    private void sessionChanged(XrEventDataSessionStateChanged xrEventDataSessionStateChanged) {
        if (this.session == null || xrEventDataSessionStateChanged.session() != this.session.address()) return;
        int state = xrEventDataSessionStateChanged.state();
        VRSettings.LOGGER.info("Vivecraft: OpenXR session=0x{} state={}", Long.toHexString(this.session.address()), state);

        switch (state) {
            case XR10.XR_SESSION_STATE_READY: {
                if (this.destroyed) break;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    XrSessionBeginInfo sessionBeginInfo = XrSessionBeginInfo.calloc(stack);
                    sessionBeginInfo.type(XR10.XR_TYPE_SESSION_BEGIN_INFO);
                    sessionBeginInfo.next(NULL);
                    sessionBeginInfo.primaryViewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);

                    int error = XR10.xrBeginSession(this.session, sessionBeginInfo);
                    if (this.stopOnLoss(error)) return;
                    checkResult(error, "xrBeginSession");
                    this.sessionRunning = true;
                }
                this.isActive = true;
                break;
            }
            case XR10.XR_SESSION_STATE_STOPPING: {
                this.isActive = false;
                this.sessionRunning = false;
                int error = XR10.xrEndSession(this.session);
                logError(error, "xrEndSession", "XR_SESSION_STATE_STOPPING");
                break;
            }
            case XR10.XR_SESSION_STATE_VISIBLE, XR10.XR_SESSION_STATE_FOCUSED: {
                this.isActive = true;
                break;
            }
            case XR10.XR_SESSION_STATE_EXITING: {
                if (this.destroyed) break;
                if (QuestVulkanContext.requested()) {
                    this.stopSession();
                    break;
                }
                this.runtimeStopping = true;
                if (ClientDataHolderVR.getInstance().vrSettings.closeWithRuntime) {
                    VRSettings.LOGGER.info("Vivecraft: OpenXR stopped, closing the game with it");
                    this.mc.stop();
                } else {
                    VRSettings.LOGGER.info("Vivecraft: OpenXR stopped, disabling VR");
                    VRState.VR_ENABLED = false;
                    ClientDataHolderVR.getInstance().vrSettings.vrEnabled = VRState.VR_ENABLED;
                    ClientDataHolderVR.getInstance().vrSettings.saveOptions();
                }
                break;
            }
            case XR10.XR_SESSION_STATE_SYNCHRONIZED: {
                this.isActive = this.sessionRunning;
                break;
            }
            case XR10.XR_SESSION_STATE_IDLE: {
                this.isActive = false;
                break;
            }
            case XR10.XR_SESSION_STATE_LOSS_PENDING: {
                this.stopSession();
                break;
            }
            default:
                break;
        }
    }

    @Override
    public Vector2f getPlayAreaSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrExtent2Df vec = XrExtent2Df.calloc(stack);
            int error = XR10.xrGetReferenceSpaceBoundsRect(this.session, XR10.XR_REFERENCE_SPACE_TYPE_STAGE, vec);
            logError(error, "xrGetReferenceSpaceBoundsRect", "");
            return new Vector2f(vec.width(), vec.height());
        }
    }

    @Override
    public boolean init() {
        if (this.initialized) {
            return true;
        } else if (this.tried) {
            return this.initialized;
        } else {
            this.tried = true;
            this.mc = Minecraft.getInstance();
            try {
                this.instance = QuestVulkanContext.requested() ? QuestVulkanContext.claimInstance() :
                    createInstance(this.device, this.device.getGraphicsExtension());
                this.poseMatrices = new Matrix4f[64];
                this.initializeOpenXRSession();
                this.initializeOpenXRSpace();
                this.initializeOpenXRSwapChain();
                this.initInputAndApplication();
            } catch (Exception e) {
                VRSettings.LOGGER.error("Vivecraft: OpenXR init failed", e);
                this.initSuccess = false;
                this.initStatus = e.getLocalizedMessage();
                return false;
            }

            VRSettings.LOGGER.info("Vivecraft: OpenXR initialized & VR connected.");
            this.deviceVelocity = new Vector3f[64];

            for (int i = 0; i < this.poseMatrices.length; ++i) {
                this.poseMatrices[i] = new Matrix4f();
                this.deviceVelocity[i] = new Vector3f();
            }

            this.initSuccess = true;
            this.initialized = true;
            return true;
        }
    }

    static XrInstance createInstance(DeviceCompat device, String graphicsExtension) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            device.initializeLoader(stack);

            IntBuffer numExtensions = stack.callocInt(1);
            int error = XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, null);
            checkResult(error, "xrEnumerateInstanceExtensionProperties");

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                bufferStack(numExtensions.get(0), XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            error = XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, properties);
            checkResult(error, "xrEnumerateInstanceExtensionProperties");

            boolean needsAndroidExtension = device instanceof DeviceCompat.Mobile;
            boolean missingGraphics = true;
            PointerBuffer extensions = stack.callocPointer(numExtensions.get(0));
            while (properties.hasRemaining()) {
                XrExtensionProperties prop = properties.get();
                String extensionName = prop.extensionNameString();
                if (needsAndroidExtension &&
                    extensionName.equals(KHRAndroidCreateInstance.XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) {
                    extensions.put(memAddress(stackUTF8(extensionName)));
                    needsAndroidExtension = false;
                }
                if (extensionName.equals(graphicsExtension)) {
                    missingGraphics = false;
                    extensions.put(memAddress(stackUTF8(graphicsExtension)));
                }
                if (extensionName.equals(
                    EXTHPMixedRealityController.XR_EXT_HP_MIXED_REALITY_CONTROLLER_EXTENSION_NAME))
                {
                    extensions.put(memAddress(
                        stackUTF8(EXTHPMixedRealityController.XR_EXT_HP_MIXED_REALITY_CONTROLLER_EXTENSION_NAME)));
                }
                if (extensionName.equals(
                    HTCViveCosmosControllerInteraction.XR_HTC_VIVE_COSMOS_CONTROLLER_INTERACTION_EXTENSION_NAME))
                {
                    extensions.put(memAddress(stackUTF8(
                        HTCViveCosmosControllerInteraction.XR_HTC_VIVE_COSMOS_CONTROLLER_INTERACTION_EXTENSION_NAME)));
                }
                if (extensionName.equals(FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)) {
                    extensions.put(memAddress(stackUTF8(FBDisplayRefreshRate.XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)));
                }
                if (extensionName.equals(BDControllerInteraction.XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME)) {
                    extensions.put(memAddress(stackUTF8(BDControllerInteraction.XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME)));
                }
            }

            if (needsAndroidExtension) {
                throw new IllegalStateException("OpenXR runtime is missing XR_KHR_android_create_instance");
            }
            if (missingGraphics) {
                throw new RuntimeException("OpenXR runtime is missing " + graphicsExtension);
            }

            XrApplicationInfo applicationInfo = XrApplicationInfo.calloc(stack);
            applicationInfo.apiVersion(XR10.XR_MAKE_VERSION(1, 0, 40));
            applicationInfo.applicationName(stack.UTF8("Vivecraft"));
            applicationInfo.applicationVersion(1);

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.calloc(stack);
            createInfo.type(XR10.XR_TYPE_INSTANCE_CREATE_INFO);

            createInfo.createFlags(0);
            createInfo.applicationInfo(applicationInfo);
            createInfo.enabledApiLayerNames(null);
            createInfo.enabledExtensionNames(extensions.flip());

            PointerBuffer instancePtr = stack.callocPointer(1);
            createInfo.next(device.instancePlatformInfo(stack));
            int xrResult = XR10.xrCreateInstance(createInfo, instancePtr);
            if (xrResult == XR10.XR_ERROR_RUNTIME_FAILURE) {
                throw new RuntimeException("Failed to create xrInstance, are you sure your headset is plugged in?");
            } else if (xrResult == XR10.XR_ERROR_INSTANCE_LOST) {
                throw new RuntimeException("Failed to create xrInstance due to runtime updating");
            } else if (xrResult < 0) {
                throw new RuntimeException("XR method returned " + xrResult);
            }
            return new XrInstance(instancePtr.get(0), createInfo);
        }
    }

    public static MCOpenXR get() {
        return OME;
    }

    private void initializeOpenXRSession() {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            if (QuestVulkanContext.requested()) {
                this.systemID = QuestVulkanContext.systemId(this.instance);
            } else {
                XrSystemGetInfo system = XrSystemGetInfo.calloc(stack).type$Default()
                    .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);
                LongBuffer id = stack.callocLong(1);
                checkResult(XR10.xrGetSystem(this.instance, system, id), "xrGetSystem");
                this.systemID = id.get(0);
            }

            if (this.systemID == 0) {
                throw new RuntimeException("No compatible headset detected");
            }

            XrSystemProperties systemProperties = XrSystemProperties.calloc(stack).type(XR10.XR_TYPE_SYSTEM_PROPERTIES);
            int error = XR10.xrGetSystemProperties(this.instance, this.systemID, systemProperties);
            checkResult(error, "xrGetSystemProperties");
            XrSystemTrackingProperties trackingProperties = systemProperties.trackingProperties();
            XrSystemGraphicsProperties graphicsProperties = systemProperties.graphicsProperties();

            MCOpenXR.get().systemName = memUTF8(memAddress(systemProperties.systemName()));
            int vendor = systemProperties.vendorId();
            boolean orientationTracking = trackingProperties.orientationTracking();
            boolean positionTracking = trackingProperties.positionTracking();
            int maxWidth = graphicsProperties.maxSwapchainImageWidth();
            int maxHeight = graphicsProperties.maxSwapchainImageHeight();
            int maxLayerCount = graphicsProperties.maxLayerCount();

            VRSettings.LOGGER.info("Found device with id:  {}", this.systemID);
            VRSettings.LOGGER.info("Headset Name: {}, Vendor: {}", MCOpenXR.get().systemName, vendor);
            VRSettings.LOGGER.info("Headset Orientation Tracking: {}, Position Tracking: {}", orientationTracking,
                positionTracking);
            VRSettings.LOGGER.info("Headset Max Width: {}, Max Height: {}, Max Layer Count: {}", maxWidth, maxHeight,
                maxLayerCount);

            PointerBuffer sessionPtr = stack.callocPointer(1);
            if (QuestVulkanContext.requested()) {
                error = QuestVulkanContext.createSession(stack, this.instance, this.systemID, sessionPtr);
            } else {
                var info = XrSessionCreateInfo.calloc(stack).type$Default().systemId(this.systemID)
                    .next(this.device.checkGraphics(stack, this.instance, this.systemID).address());
                error = XR10.xrCreateSession(this.instance, info, sessionPtr);
            }
            checkResult(error, "xrCreateSession");

            this.session = new XrSession(sessionPtr.get(0), this.instance);

            boolean checked = false;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!this.sessionRunning) {
                if (this.runtimeStopping) throw new IllegalStateException("OpenXR stopped during session startup");
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("OpenXR session did not become READY within 30 seconds");
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for OpenXR", e);
                }
                if (!checked)
                    VRSettings.LOGGER.info("Vivecraft: waiting for OpenXR session to start");
                checked = true;
                pollVREvents();
            }
        }
    }

    private void initializeOpenXRSpace() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrPosef identityPose = XrPosef.calloc(stack);
            identityPose.set(
                XrQuaternionf.calloc(stack).set(0, 0, 0, 1),
                XrVector3f.calloc(stack)
            );

            XrReferenceSpaceCreateInfo referenceSpaceCreateInfo = XrReferenceSpaceCreateInfo.calloc(stack);
            referenceSpaceCreateInfo.type(XR10.XR_TYPE_REFERENCE_SPACE_CREATE_INFO);
            referenceSpaceCreateInfo.next(NULL);
            referenceSpaceCreateInfo.referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_STAGE);
            referenceSpaceCreateInfo.poseInReferenceSpace(identityPose);

            PointerBuffer pp = stack.callocPointer(1);
            int error = XR10.xrCreateReferenceSpace(this.session, referenceSpaceCreateInfo, pp);
            this.xrAppSpace = new XrSpace(pp.get(0), this.session);
            checkResult(error, "xrCreateReferenceSpace");

            referenceSpaceCreateInfo.referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_VIEW);
            error = XR10.xrCreateReferenceSpace(this.session, referenceSpaceCreateInfo, pp);
            checkResult(error, "xrCreateReferenceSpace");
            this.xrViewSpace = new XrSpace(pp.get(0), this.session);
        }
    }

    private void initializeOpenXRSwapChain() {
        try (MemoryStack stack = stackPush()) {

            IntBuffer intBuf = stack.callocInt(1);
            int error = XR10.xrEnumerateViewConfigurationViews(this.instance, this.systemID,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, intBuf, null);
            checkResult(error, "xrEnumerateViewConfigurationViews");

            if (intBuf.get(0) != 2) throw new IllegalStateException("OpenXR requires two stereo views");
            ByteBuffer viewConfBuffer = bufferStack(intBuf.get(0), XrViewConfigurationView.SIZEOF,
                XR10.XR_TYPE_VIEW_CONFIGURATION_VIEW);
            XrViewConfigurationView.Buffer views = new XrViewConfigurationView.Buffer(viewConfBuffer);
            error = XR10.xrEnumerateViewConfigurationViews(this.instance, this.systemID,
                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, intBuf, views);
            checkResult(error, "xrEnumerateViewConfigurationViews");
            int viewCountNumber = intBuf.get(0);

            this.viewBuffer = new XrView.Buffer(
                bufferHeap(viewCountNumber, XrView.SIZEOF, XR10.XR_TYPE_VIEW)
            );

            error = XR10.xrEnumerateSwapchainFormats(this.session, intBuf, null);
            checkResult(error, "xrEnumerateSwapchainFormats");

            LongBuffer swapchainFormats = stack.callocLong(intBuf.get(0));
            error = XR10.xrEnumerateSwapchainFormats(this.session, intBuf, swapchainFormats);
            checkResult(error, "xrEnumerateSwapchainFormats");

            long chosenFormat = QuestVulkanContext.requested() ? OpenXRVulkanEyes.selectFormat(swapchainFormats) :
                selectGLFormat(swapchainFormats);
            this.swapchainFormat = chosenFormat;
            int width = Math.max(views.get(0).recommendedImageRectWidth(), views.get(1).recommendedImageRectWidth());
            int height = Math.max(views.get(0).recommendedImageRectHeight(), views.get(1).recommendedImageRectHeight());
            width = Math.min(width, Math.min(views.get(0).maxImageRectWidth(), views.get(1).maxImageRectWidth()));
            height = Math.min(height, Math.min(views.get(0).maxImageRectHeight(), views.get(1).maxImageRectHeight()));
            XrSwapchainCreateInfo swapchainCreateInfo = XrSwapchainCreateInfo.calloc(stack);
            swapchainCreateInfo.type(XR10.XR_TYPE_SWAPCHAIN_CREATE_INFO);
            swapchainCreateInfo.next(NULL);
            swapchainCreateInfo.createFlags(0);
            swapchainCreateInfo.usageFlags(XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                XR10.XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT);
            swapchainCreateInfo.format(chosenFormat);
            swapchainCreateInfo.sampleCount(1);
            swapchainCreateInfo.width(width);
            swapchainCreateInfo.height(height);
            swapchainCreateInfo.faceCount(1);
            swapchainCreateInfo.arraySize(2);
            swapchainCreateInfo.mipCount(1);

            PointerBuffer handlePointer = stack.callocPointer(1);
            error = XR10.xrCreateSwapchain(this.session, swapchainCreateInfo, handlePointer);
            checkResult(error, "xrCreateSwapchain");
            this.swapchain = new XrSwapchain(handlePointer.get(0), this.session);
            this.width = swapchainCreateInfo.width();
            this.height = swapchainCreateInfo.height();
            VRSettings.LOGGER.info("Vivecraft: OpenXR stereo swapchain format={} size={}x{} layers=2",
                chosenFormat, width, height);
        }
    }

    private static long selectGLFormat(LongBuffer swapchainFormats) {
        long[] desiredSwapchainFormats = {

            GL21.GL_SRGB8_ALPHA8,
            GL21.GL_SRGB8,

            GL11.GL_RGB10_A2,
            GL30.GL_RGBA16F,
            GL30.GL_RGB16F,

            // Prefer sRGB to avoid banding.
            GL11.GL_RGBA8,
            GL31.GL_RGBA8_SNORM,
        };

        long chosenFormat = 0;
        for (long glFormatIter : desiredSwapchainFormats) {
            swapchainFormats.rewind();
            while (swapchainFormats.hasRemaining()) {
                if (glFormatIter == swapchainFormats.get()) {
                    chosenFormat = glFormatIter;
                    break;
                }
            }
            if (chosenFormat != 0) {
                break;
            }
        }

        if (chosenFormat == 0) {
            var formats = new ArrayList<Long>();
            swapchainFormats.rewind();
            while (swapchainFormats.hasRemaining()) {
                formats.add(swapchainFormats.get());
            }
            throw new RuntimeException("No compatible swapchain / framebuffer format available: " + formats);
        }
        return chosenFormat;
    }

    private void initDisplayRefreshRate() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer refreshRateCount = stack.callocInt(1);
            FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, refreshRateCount, null);
            FloatBuffer refreshRateBuffer = stack.callocFloat(refreshRateCount.get(0));
            FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(session, refreshRateCount, refreshRateBuffer);
            refreshRateBuffer.rewind();
            if (refreshRateCount.get(0) > 0) {
                FBDisplayRefreshRate.xrRequestDisplayRefreshRateFB(session, refreshRateBuffer.get(refreshRateCount.get(0) - 1));
            }
        }
    }

    static ByteBuffer bufferStack(int capacity, int sizeof, int type) {
        ByteBuffer b = stackCalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    private void initInputAndApplication() {
        this.populateInputActions();

        this.loadActionHandles();
        this.loadDefaultBindings();

        this.inputInitialized = true;
        if (this.session.getCapabilities().XR_FB_display_refresh_rate) {
            initDisplayRefreshRate();
        }
    }

    @Override
    public void refreshControllerTransforms() {
        // OpenXR poses already include the controller transforms.
    }

    @Override
    public String getOriginName(long origin) {
        return getLocalizedSourceName(origin, XR10.XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT |
            XR10.XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT);
    }

    private String getLocalizedSourceName(long origin, int components) {
        if (origin == 0 || this.session == null) return "";
        try (MemoryStack stack = stackPush()) {
            XrInputSourceLocalizedNameGetInfo info = XrInputSourceLocalizedNameGetInfo.calloc(stack)
                .type(XR10.XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO)
                .sourcePath(origin).whichComponents(components);
            IntBuffer count = stack.callocInt(1);
            int result = XR10.xrGetInputSourceLocalizedName(this.session, info, count, null);
            if (result < 0 || count.get(0) <= 1) return "";
            ByteBuffer name = stack.calloc(count.get(0));
            result = XR10.xrGetInputSourceLocalizedName(this.session, info, count, name);
            return result >= 0 ? memUTF8(memAddress(name)) : "";
        }
    }

    @Override
    public Matrix4f getControllerComponentTransform(int controllerIndex, String componentName) {
        if ("handgrip".equals(componentName)) {
            // updateAim composes the aim pose with this local grip rotation.
            return OpenXRPoseMath.gripRelativeToAim(this.controllerPose[controllerIndex],
                this.handRotation[controllerIndex]);
        }
        return new Matrix4f();
    }

    @Override
    public boolean hasCameraTracker() {
        return false;
    }

    @Override
    public List<Long> getOrigins(VRInputAction action) {
        return getBoundSources(action.handle, action.actionSet);
    }

    private List<Long> getBoundSources(long actionHandle, VRInputActionSet actionSet) {
        if (actionHandle == 0L || this.session == null) return List.of();
        return this.boundSources.computeIfAbsent(actionHandle, handle -> enumerateBoundSources(handle, actionSet));
    }

    private List<Long> enumerateBoundSources(long actionHandle, VRInputActionSet actionSet) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrBoundSourcesForActionEnumerateInfo info = XrBoundSourcesForActionEnumerateInfo.calloc(stack);
            info.type(XR10.XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO);
            info.next(NULL);
            info.action(new XrAction(actionHandle,
                new XrActionSet(this.actionSetHandles.get(actionSet), this.instance)));
            IntBuffer buf = stack.callocInt(1);
            int error = XR10.xrEnumerateBoundSourcesForAction(this.session, info, buf, null);
            logError(error, "xrEnumerateBoundSourcesForAction", "count");

            int size = buf.get(0);
            if (error < 0 || size <= 0) {
                return List.of();
            }

            LongBuffer longbuf = stack.callocLong(size);
            error = XR10.xrEnumerateBoundSourcesForAction(this.session, info, buf, longbuf);
            logError(error, "xrEnumerateBoundSourcesForAction", "sources");
            if (error < 0) return List.of();
            List<Long> sources = new ArrayList<>(buf.get(0));
            for (int i = 0; i < buf.get(0); i++) {
                sources.add(longbuf.get(i));
            }
            return List.copyOf(sources);
        }
    }

    @Override
    public VRRenderer createVRRenderer() {
        return new OpenXRStereoRenderer(this);
    }

    @Override
    public boolean isActive() {
        return this.isActive;
    }

    @Override
    public ControllerType getOriginControllerType(long inputValueHandle) {
        return OpenXRInputOrigins.resolve(getSourceUserName(inputValueHandle), this.rightInputUser,
            this.leftInputUser, this.dh.vrSettings.reverseHands);
    }

    @Override
    public float getIPD() {
        return this.hmdPoseLeftEye.getTranslation(new Vector3f()).distance(
            this.hmdPoseRightEye.getTranslation(new Vector3f()));
    }

    @Override
    public String getRuntimeName() {
        return "OpenXR";
    }

    private static final String[] BOTH_HANDS = new String[]{"/user/hand/left", "/user/hand/right"};

    private void loadActionHandles() {
        for (VRInputActionSet vrinputactionset : VRInputActionSet.values()) {
            long actionSet = makeActionSet(this.instance, vrinputactionset.name, vrinputactionset.localizedName, 0);
            this.actionSetHandles.put(vrinputactionset, actionSet);
        }

        for (VRInputAction vrinputaction : this.inputActions.values()) {
            long action = createAction(vrinputaction.name, vrinputaction.name, vrinputaction.type,
                new XrActionSet(this.actionSetHandles.get(vrinputaction.actionSet), this.instance), BOTH_HANDS);
            vrinputaction.setHandle(action);
        }

        setupControllers();

        XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
        this.haptics[RIGHT_CONTROLLER] = createAction("/actions/global/out/righthaptic",
            "/actions/global/out/righthaptic", "haptic", actionSet, BOTH_HANDS);
        this.haptics[LEFT_CONTROLLER] = createAction("/actions/global/out/lefthaptic", "/actions/global/out/lefthaptic",
            "haptic", actionSet, BOTH_HANDS);
    }

    private void setupControllers() {
        XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
        this.grip[RIGHT_CONTROLLER] = createAction("/actions/global/in/righthand", "/actions/global/in/righthand",
            "pose", actionSet, BOTH_HANDS);
        this.grip[LEFT_CONTROLLER] = createAction("/actions/global/in/lefthand", "/actions/global/in/lefthand", "pose",
            actionSet, BOTH_HANDS);
        this.aim[RIGHT_CONTROLLER] = createAction("/actions/global/in/righthandaim", "/actions/global/in/righthandaim",
            "pose", actionSet, BOTH_HANDS);
        this.aim[LEFT_CONTROLLER] = createAction("/actions/global/in/lefthandaim", "/actions/global/in/lefthandaim",
            "pose", actionSet, BOTH_HANDS);
    }

    private void loadDefaultBindings() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int error;
            for (String headset : XRBindings.supportedHeadsets()) {
                VRSettings.LOGGER.info("loading defaults for {}", headset);
                Pair<String, String>[] defaultBindings = XRBindings.getBinding(headset).toArray(new Pair[0]);
                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.calloc(defaultBindings.length + 6,
                    stack);

                int bindingCount = 0;
                for (int i = 0; i < defaultBindings.length; i++) {
                    Pair<String, String> pair = defaultBindings[i];
                    VRInputAction binding = this.getInputActionByName(pair.getLeft());
                    if (binding == null || binding.handle == 0L) {
                        VRSettings.LOGGER.error("Handle for '{}'/'{}' is null", pair.getLeft(), pair.getRight());
                        continue;
                    }
                    bindings.get(bindingCount++).set(
                        new XrAction(binding.handle,
                            new XrActionSet(this.actionSetHandles.get(binding.actionSet), this.instance)),
                        getPath(pair.getRight())
                    );
                }

                XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL),
                    this.instance);
                bindings.get(bindingCount++).set(
                    new XrAction(this.grip[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/input/grip/pose")
                );
                bindings.get(bindingCount++).set(
                    new XrAction(this.grip[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/input/grip/pose")
                );
                bindings.get(bindingCount++).set(
                    new XrAction(this.aim[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/input/aim/pose")
                );
                bindings.get(bindingCount++).set(
                    new XrAction(this.aim[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/input/aim/pose")
                );

                bindings.get(bindingCount++).set(
                    new XrAction(this.haptics[RIGHT_CONTROLLER], actionSet),
                    getPath("/user/hand/right/output/haptic")
                );

                bindings.get(bindingCount++).set(
                    new XrAction(this.haptics[LEFT_CONTROLLER], actionSet),
                    getPath("/user/hand/left/output/haptic")
                );

                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.calloc(
                    stack);
                suggested_binds.type(XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING);
                suggested_binds.next(NULL);
                suggested_binds.interactionProfile(getPath(headset));
                bindings.limit(bindingCount);
                suggested_binds.suggestedBindings(bindings);

                error = XR10.xrSuggestInteractionProfileBindings(this.instance, suggested_binds);
                logError(error, "xrSuggestInteractionProfileBindings", headset);
            }

            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.calloc(stack);
            attach_info.type(XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO);
            attach_info.next(NULL);
            attach_info.actionSets(
                stackPointers(this.actionSetHandles.values().stream().mapToLong(value -> value).toArray()));

            error = XR10.xrAttachSessionActionSets(this.session, attach_info);
            checkResult(error, "xrAttachSessionActionSets");

            XrActionSet actionSet = new XrActionSet(this.actionSetHandles.get(VRInputActionSet.GLOBAL), this.instance);
            XrActionSpaceCreateInfo actionSpace = XrActionSpaceCreateInfo.calloc(stack);
            actionSpace.type(XR10.XR_TYPE_ACTION_SPACE_CREATE_INFO);
            actionSpace.next(NULL);
            actionSpace.action(new XrAction(this.grip[RIGHT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/right"));
            actionSpace.poseInActionSpace(POSE_IDENTITY);
            PointerBuffer pp = stackCallocPointer(1);
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            checkResult(error, "xrCreateActionSpace");
            this.gripSpace[RIGHT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.grip[LEFT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/left"));
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            checkResult(error, "xrCreateActionSpace");
            this.gripSpace[LEFT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.aim[RIGHT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/right"));
            error = XR10.xrCreateActionSpace(session, actionSpace, pp);
            checkResult(error, "xrCreateActionSpace");
            this.aimSpace[RIGHT_CONTROLLER] = new XrSpace(pp.get(0), this.session);

            actionSpace.action(new XrAction(this.aim[LEFT_CONTROLLER], actionSet));
            actionSpace.subactionPath(getPath("/user/hand/left"));
            error = XR10.xrCreateActionSpace(this.session, actionSpace, pp);
            checkResult(error, "xrCreateActionSpace");
            this.aimSpace[LEFT_CONTROLLER] = new XrSpace(pp.get(0), this.session);
        }
    }

    public long getPath(String pathString) {
        return this.paths.computeIfAbsent(pathString, s -> {
            try (MemoryStack ignored = stackPush()) {
                LongBuffer buf = stackCallocLong(1);
                int error = XR10.xrStringToPath(this.instance, pathString, buf);
                logError(error, "getPath", pathString);
                return buf.get();
            }
        });
    }

    private String processActionName(String name) {
        String s = name.substring(name.lastIndexOf('/') + 1)
            .toLowerCase()
            .replaceAll(" ", "-")
            .replaceAll("[^-_./a-z0-9]", "");

        if (s.length() > 64) {s = s.substring(0, 60) + "...";}
        return s;
    }

    private long createAction(
        String name, String localisedName, String type, XrActionSet actionSet, @Nullable String[] subactionPaths)
    {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            String s = processActionName(name);
            XrActionCreateInfo hands = XrActionCreateInfo.calloc(stack);
            hands.type(XR10.XR_TYPE_ACTION_CREATE_INFO);
            hands.next(NULL);
            hands.actionName(stack.UTF8(s));
            switch (type) {
                case "boolean" -> hands.actionType(XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
                case "vector1" -> hands.actionType(XR10.XR_ACTION_TYPE_FLOAT_INPUT);
                case "vector2" -> hands.actionType(XR10.XR_ACTION_TYPE_VECTOR2F_INPUT);
                case "pose" -> hands.actionType(XR10.XR_ACTION_TYPE_POSE_INPUT);
                case "haptic" -> hands.actionType(XR10.XR_ACTION_TYPE_VIBRATION_OUTPUT);
            }
            if (subactionPaths != null) {
                LongBuffer buffer = stackCallocLong(subactionPaths.length);
                for (String path : subactionPaths) {
                    buffer.put(getPath(path));
                }
                hands.countSubactionPaths(subactionPaths.length);
                hands.subactionPaths(buffer.rewind());
            } else {
                hands.countSubactionPaths(0);
                hands.subactionPaths(null);
            }
            hands.localizedActionName(stack.UTF8(s));
            PointerBuffer buffer = stackCallocPointer(1);

            int error = XR10.xrCreateAction(actionSet, hands, buffer);
            logError(error, "xrCreateAction", "name:", name, "type:", type);
            return buffer.get(0);
        }
    }

    private long makeActionSet(XrInstance instance, String name, String localisedName, int priority) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrActionSetCreateInfo info = XrActionSetCreateInfo.calloc(stack);
            info.type(XR10.XR_TYPE_ACTION_SET_CREATE_INFO);
            info.next(NULL);
            info.actionSetName(stack.UTF8(localisedName.toLowerCase(Locale.ROOT)));
            info.localizedActionSetName(stack.UTF8(localisedName.toLowerCase(Locale.ROOT)));
            info.priority(priority);
            PointerBuffer buffer = stack.callocPointer(1);

            int error = XR10.xrCreateActionSet(instance, info, buffer);
            checkResult(error, "xrCreateActionSet (" + name + ")");
            return buffer.get(0);
        }
    }

    static ByteBuffer bufferHeap(int capacity, int sizeof, int type) {
        ByteBuffer b = memCalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    private String getResultName(int xrResult) {
        String resultString = switch (xrResult) {
            case 1 -> "XR_TIMEOUT_EXPIRED";
            case 3 -> "XR_SESSION_LOSS_PENDING";
            case 4 -> "XR_EVENT_UNAVAILABLE";
            case 7 -> "XR_SPACE_BOUNDS_UNAVAILABLE";
            case 8 -> "XR_SESSION_NOT_FOCUSED";
            case 9 -> "XR_FRAME_DISCARDED";
            case -1 -> "XR_ERROR_VALIDATION_FAILURE";
            case -2 -> "XR_ERROR_RUNTIME_FAILURE";
            case -3 -> "XR_ERROR_OUT_OF_MEMORY";
            case -4 -> "XR_ERROR_API_VERSION_UNSUPPORTED";
            case -6 -> "XR_ERROR_INITIALIZATION_FAILED";
            case -7 -> "XR_ERROR_FUNCTION_UNSUPPORTED";
            case -8 -> "XR_ERROR_FEATURE_UNSUPPORTED";
            case -9 -> "XR_ERROR_EXTENSION_NOT_PRESENT";
            case -10 -> "XR_ERROR_LIMIT_REACHED";
            case -11 -> "XR_ERROR_SIZE_INSUFFICIENT";
            case -12 -> "XR_ERROR_HANDLE_INVALID";
            case -13 -> "XR_ERROR_INSTANCE_LOST";
            case -14 -> "XR_ERROR_SESSION_RUNNING";
            case -16 -> "XR_ERROR_SESSION_NOT_RUNNING";
            case -17 -> "XR_ERROR_SESSION_LOST";
            case -18 -> "XR_ERROR_SYSTEM_INVALID";
            case -19 -> "XR_ERROR_PATH_INVALID";
            case -20 -> "XR_ERROR_PATH_COUNT_EXCEEDED";
            case -21 -> "XR_ERROR_PATH_FORMAT_INVALID";
            case -22 -> "XR_ERROR_PATH_UNSUPPORTED";
            case -23 -> "XR_ERROR_LAYER_INVALID";
            case -24 -> "XR_ERROR_LAYER_LIMIT_EXCEEDED";
            case -25 -> "XR_ERROR_SWAPCHAIN_RECT_INVALID";
            case -26 -> "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED";
            case -27 -> "XR_ERROR_ACTION_TYPE_MISMATCH";
            case -28 -> "XR_ERROR_SESSION_NOT_READY";
            case -29 -> "XR_ERROR_SESSION_NOT_STOPPING";
            case -30 -> "XR_ERROR_TIME_INVALID";
            case -31 -> "XR_ERROR_REFERENCE_SPACE_UNSUPPORTED";
            case -32 -> "XR_ERROR_FILE_ACCESS_ERROR";
            case -33 -> "XR_ERROR_FILE_CONTENTS_INVALID";
            case -34 -> "XR_ERROR_FORM_FACTOR_UNSUPPORTED";
            case -35 -> "XR_ERROR_FORM_FACTOR_UNAVAILABLE";
            case -36 -> "XR_ERROR_API_LAYER_NOT_PRESENT";
            case -37 -> "XR_ERROR_CALL_ORDER_INVALID";
            case -38 -> "XR_ERROR_GRAPHICS_DEVICE_INVALID";
            case -39 -> "XR_ERROR_POSE_INVALID";
            case -40 -> "XR_ERROR_INDEX_OUT_OF_RANGE";
            case -41 -> "XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED";
            case -42 -> "XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED";
            case -44 -> "XR_ERROR_NAME_DUPLICATED";
            case -45 -> "XR_ERROR_NAME_INVALID";
            case -46 -> "XR_ERROR_ACTIONSET_NOT_ATTACHED";
            case -47 -> "XR_ERROR_ACTIONSETS_ALREADY_ATTACHED";
            case -48 -> "XR_ERROR_LOCALIZED_NAME_DUPLICATED";
            case -49 -> "XR_ERROR_LOCALIZED_NAME_INVALID";
            case -50 -> "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING";
            case -51 -> "XR_ERROR_RUNTIME_UNAVAILABLE";
            default -> null;
        };
        if (resultString == null) {

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer str = stack.calloc(XR10.XR_MAX_RESULT_STRING_SIZE);

                if (XR10.xrResultToString(this.instance, xrResult, str) == XR10.XR_SUCCESS) {
                    resultString = (memUTF8(memAddress(str)));
                } else {
                    resultString = "Unknown Error: " + xrResult;
                }
            }
        }
        return resultString;
    }

    static void checkResult(int xrResult, String caller) {
        if (xrResult < 0) throw new IllegalStateException(caller + " failed with OpenXR result " + xrResult);
    }

    protected void logError(int xrResult, String caller, String... args) {
        if (xrResult < 0) {
            VRSettings.LOGGER.error("{} for {} errored: {}", caller, String.join(" ", args), getResultName(xrResult));
        }
    }
}
