package org.vivecraft.client_vr.provider.openxr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.VRTextureTarget;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.render.RenderConfigException;
import org.vivecraft.client_vr.render.helpers.ShaderHelper;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;
import org.vivecraft.client_vr.render.helpers.graphics.OpenGLHelper;
import org.vivecraft.client_vr.render.renderstates.PostProcessRenderState;
import org.vivecraft.client_vr.settings.VRSettings;

import java.nio.IntBuffer;
import java.util.List;

public class OpenXRStereoRenderer extends VRRenderer {
    private final MCOpenXR openxr;
    private OpenXRSwapchainTarget[] leftFramebuffers;
    private OpenXRSwapchainTarget[] rightFramebuffers;
    private int swapIndex;
    private boolean imageAcquired;
    private boolean imageWaited;
    private int renderedEyes;
    private int loggedEyes;
    private boolean warnedIncompleteFrame;
    private RenderConfigException frameFailure;

    public OpenXRStereoRenderer(MCOpenXR vr) {
        super(vr);
        this.openxr = vr;
    }

    @Override
    public void checkCapabilities() throws RenderConfigException {
        super.checkCapabilities();
        if (!(GraphicsHelper.INSTANCE instanceof OpenGLHelper)) {
            throw new RenderConfigException(Component.literal("OpenXR graphics backend"),
                Component.literal("Quest OpenXR requires the OpenGL renderer"));
        }
    }

    @Override
    public void createRenderTexture(int width, int height) {
        this.lastError = "";
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            checkInitResult(XR10.xrEnumerateSwapchainImages(this.openxr.swapchain, count, null),
                "xrEnumerateSwapchainImages(count)");
            int imageCount = count.get(0);
            if (imageCount <= 0) {
                throw new IllegalStateException("OpenXR returned no swapchain images");
            }
            XrSwapchainImageOpenGLKHR.Buffer images = this.openxr.device.createImageBuffers(imageCount, stack);
            checkInitResult(XR10.xrEnumerateSwapchainImages(this.openxr.swapchain, count,
                XrSwapchainImageBaseHeader.create(images.address(), images.capacity())),
                "xrEnumerateSwapchainImages(images)");

            this.leftFramebuffers = new OpenXRSwapchainTarget[imageCount];
            this.rightFramebuffers = new OpenXRSwapchainTarget[imageCount];
            for (int i = 0; i < imageCount; i++) {
                int texture = images.get(i).image();
                this.leftFramebuffers[i] = new OpenXRSwapchainTarget("OpenXR L Eye " + i, width, height, texture, 0, this.openxr.swapchainFormat);
                this.rightFramebuffers[i] = new OpenXRSwapchainTarget("OpenXR R Eye " + i, width, height, texture, 1, this.openxr.swapchainFormat);
            }
            this.loggedEyes = 0;
            VRSettings.LOGGER.info("Vivecraft OpenXR: direct eye rendering, {}x{}, {} swapchain images; GL vendor={}, renderer={}, library={}, FCL renderer={}",
                width, height, imageCount, GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER),
                System.getProperty("org.lwjgl.opengl.libname", "default"), System.getenv("FCL_VR_RENDERER"));
            for (int eye = 0; eye < 2; eye++) {
                this.framebufferEye[eye] = VRTextureTarget.builder(eye == 0 ? "L Eye" : "R Eye")
                    .withSize(width, height)
                    .build();
            }
            this.lastError = GraphicsHelper.INSTANCE.checkError("OpenXR eye framebuffer setup");
            if (!this.lastError.isEmpty()) {
                throw new IllegalStateException(this.lastError);
            }
        }
    }

    private void checkInitResult(int result, String operation) {
        if (result != XR10.XR_SUCCESS) {
            this.lastError = operation + " failed: " + result;
            throw new IllegalStateException(this.lastError);
        }
    }

    private void checkFrameResult(int result, String operation) throws RenderConfigException {
        if (result != XR10.XR_SUCCESS) {
            this.lastError = operation + " failed: " + result;
            this.frameFailure = new RenderConfigException(Component.literal("OpenXR frame error"),
                Component.literal(this.lastError));
            throw this.frameFailure;
        }
    }

    @Override
    public void beginFrame() throws RenderConfigException {
        if (this.frameFailure != null) {
            throw this.frameFailure;
        }
        if (!this.openxr.frameStarted || !this.openxr.shouldRender || this.imageAcquired) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer index = stack.callocInt(1);
            checkFrameResult(XR10.xrAcquireSwapchainImage(this.openxr.swapchain,
                XrSwapchainImageAcquireInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO), index),
                "xrAcquireSwapchainImage");
            this.imageAcquired = true;
            this.swapIndex = index.get(0);
            checkFrameResult(XR10.xrWaitSwapchainImage(this.openxr.swapchain,
                XrSwapchainImageWaitInfo.calloc(stack)
                    .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO).timeout(XR10.XR_INFINITE_DURATION)),
                "xrWaitSwapchainImage");
            this.imageWaited = true;
            this.renderedEyes = 0;
        }
    }

    @Override
    public void postProcessEye(RenderPass eye, RenderTarget source, PostProcessRenderState state) {
        if (!this.imageAcquired || !this.imageWaited) {
            throw new IllegalStateException("OpenXR eye rendering requires an acquired and waited swapchain image");
        }
        int eyeIndex = eye == RenderPass.LEFT ? 0 : 1;
        OpenXRSwapchainTarget target = (eyeIndex == 0 ? this.leftFramebuffers : this.rightFramebuffers)[this.swapIndex];
        ShaderHelper.doVrPostProcess(eye, source, target, state, this.framebufferEye[eyeIndex]);
        this.renderedEyes |= 1 << eyeIndex;
        if ((this.loggedEyes & (1 << eyeIndex)) == 0) {
            XrFovf fov = this.openxr.viewBuffer.get(eyeIndex).fov();
            VRSettings.LOGGER.info("Vivecraft OpenXR: eye={}, image={}, FBO={}, texture={}, layer={}, target={}x{}, source={}x{}, FOV L/R/D/U={}/{}/{}/{}",
                eye, this.swapIndex, target.layerView().framebuffer(), target.layerView().glId(), eyeIndex,
                target.width, target.height, source.width, source.height,
                fov.angleLeft(), fov.angleRight(), fov.angleDown(), fov.angleUp());
            this.loggedEyes |= 1 << eyeIndex;
        }
    }

    @Override
    public List<RenderPass> getRenderPasses(boolean includeNonRendered) {
        if (!includeNonRendered && (!this.openxr.frameStarted || !this.openxr.shouldRender)) {
            return List.of();
        }
        return super.getRenderPasses(includeNonRendered);
    }

    @Override
    public Matrix4f getCachedProjectionMatrix(int eyeType, float nearClip, float farClip) {
        // FOV and clip planes can change between projection queries.
        this.eyeProj[0] = getProjectionMatrix(0, nearClip, farClip);
        this.eyeProj[1] = getProjectionMatrix(1, nearClip, farClip);
        return this.eyeProj[eyeType];
    }

    @Override
    public Matrix4f getCachedReverseProjectionMatrix(int eyeType, float nearClip, float farClip) {
        this.eyeReverseProj[0] = getProjectionMatrix(0, farClip, nearClip);
        this.eyeReverseProj[1] = getProjectionMatrix(1, farClip, nearClip);
        return this.eyeReverseProj[eyeType];
    }

    @Override
    protected Matrix4f getProjectionMatrix(int eyeType, float nearClip, float farClip) {
        if (this.openxr.viewBuffer != null) {
            XrFovf fov = this.openxr.viewBuffer.get(eyeType).fov();
            if (Float.isFinite(fov.angleLeft()) && Float.isFinite(fov.angleRight()) &&
                Float.isFinite(fov.angleDown()) && Float.isFinite(fov.angleUp()) &&
                fov.angleRight() > fov.angleLeft() && fov.angleUp() > fov.angleDown())
            {
                return OpenXRProjectionMath.projection(fov.angleLeft(), fov.angleRight(),
                    fov.angleDown(), fov.angleUp(), nearClip, farClip,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            }
        }
        // Buffer setup can run before the first xrLocateViews.
        return new Matrix4f().setPerspective((float) (Math.PI / 2.0),
            this.openxr.height > 0 ? (float) this.openxr.width / this.openxr.height : 1F, nearClip, farClip,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    @Override
    public void endFrame() throws RenderConfigException {
        endFrame(true);
    }

    public void endFrame(boolean submit) throws RenderConfigException {
        if (!this.openxr.frameStarted) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean submitLayer = submit && this.openxr.shouldRender && this.imageAcquired && this.imageWaited;
            RenderConfigException failure = null;
            if (submitLayer && this.renderedEyes != 3) {
                submitLayer = false;
                if (!this.warnedIncompleteFrame) {
                    VRSettings.LOGGER.warn("Vivecraft OpenXR: skipping incomplete stereo frame, rendered eye mask={}",
                        this.renderedEyes);
                    this.warnedIncompleteFrame = true;
                }
            }
            if (this.imageAcquired && this.imageWaited) {
                GraphicsHelper.INSTANCE.flush();
                try {
                    checkFrameResult(XR10.xrReleaseSwapchainImage(this.openxr.swapchain,
                        XrSwapchainImageReleaseInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)),
                        "xrReleaseSwapchainImage");
                    this.imageAcquired = false;
                    this.imageWaited = false;
                } catch (RenderConfigException exception) {
                    failure = exception;
                    submitLayer = false;
                }
            }

            XrFrameEndInfo endInfo = XrFrameEndInfo.calloc(stack)
                .type(XR10.XR_TYPE_FRAME_END_INFO)
                .displayTime(this.openxr.time)
                .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
            if (submitLayer) {
                XrCompositionLayerProjectionView.Buffer views = XrCompositionLayerProjectionView.calloc(2, stack);
                for (int eye = 0; eye < 2; eye++) {
                    XrSwapchainSubImage subImage = views.get(eye)
                        .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                        .pose(this.openxr.viewBuffer.get(eye).pose())
                        .fov(this.openxr.viewBuffer.get(eye).fov())
                        .subImage();
                    subImage.swapchain(this.openxr.swapchain);
                    subImage.imageRect().offset().set(0, 0);
                    subImage.imageRect().extent().set(this.openxr.width, this.openxr.height);
                    subImage.imageArrayIndex(eye);
                }
                XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc(stack)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                    .space(this.openxr.xrAppSpace)
                    .views(views);
                PointerBuffer layers = stack.pointers(layer.address());
                endInfo.layers(layers);
            }
            try {
                checkFrameResult(XR10.xrEndFrame(this.openxr.session, endInfo), "xrEndFrame");
            } catch (RenderConfigException exception) {
                failure = exception;
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            this.openxr.frameStarted = false;
        }
    }

    @Override
    public boolean providesStencilMask() {
        return false;
    }

    @Override
    public String getName() {
        return "OpenXR";
    }

    @Override
    public Vector2ic getRenderTextureSizes() {
        return new Vector2i(this.openxr.width, this.openxr.height);
    }

    @Override
    public void destroy() {
        try {
            endFrame(false);
        } catch (RenderConfigException exception) {
            VRSettings.LOGGER.warn("Vivecraft: could not finish the OpenXR frame during shutdown", exception);
        } finally {
            super.destroy();
            this.imageAcquired = false;
            this.imageWaited = false;
        }
    }

    @Override
    protected void destroyBuffers() {
        super.destroyBuffers();
        if (this.leftFramebuffers != null) {
            for (OpenXRSwapchainTarget framebuffer : this.leftFramebuffers) {
                if (framebuffer != null) framebuffer.destroyBuffers();
            }
            this.leftFramebuffers = null;
        }
        if (this.rightFramebuffers != null) {
            for (OpenXRSwapchainTarget framebuffer : this.rightFramebuffers) {
                if (framebuffer != null) framebuffer.destroyBuffers();
            }
            this.rightFramebuffers = null;
        }
    }
}
