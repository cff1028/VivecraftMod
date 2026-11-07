package org.vivecraft.client_vr.provider.openxr;

import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.HapticScheduler;
import org.vivecraft.client_vr.provider.openvr_lwjgl.control.VRInputActionSet;

import java.util.concurrent.TimeUnit;

import static org.lwjgl.system.MemoryUtil.NULL;

public class OpenXRHapticScheduler extends HapticScheduler {

    // Delayed pulses must retain their original provider.
    private final MCOpenXR provider = MCOpenXR.get();
    private boolean closed;

    private synchronized void triggerHapticPulse(
        ControllerType controller, float durationSeconds, float frequency, float amplitude)
    {
        if (this.closed || this.provider == null || this.provider.session == null ||
            this.provider.instance == null)
        {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()){
            int i = controller == ControllerType.RIGHT ? 0 : 1;
            if (ClientDataHolderVR.getInstance().vrSettings.reverseHands) {
                i = controller == ControllerType.RIGHT ? 1 : 0;
            }
            if (this.provider.haptics[i] == 0L) return;
            XrActionSet actionSet = new XrActionSet(this.provider.getActionSetHandle(VRInputActionSet.GLOBAL), this.provider.instance);
            XrHapticActionInfo info = XrHapticActionInfo.calloc(stack);
            info.type(XR10.XR_TYPE_HAPTIC_ACTION_INFO);
            info.next(NULL);
            info.action(new XrAction(this.provider.haptics[i], actionSet));

            XrHapticVibration vibration = XrHapticVibration.calloc(stack);
            vibration.type(XR10.XR_TYPE_HAPTIC_VIBRATION);
            vibration.next(NULL);
            vibration.duration(Math.max(1L, (long) (durationSeconds * 1_000_000_000L)));
            vibration.frequency(Math.max(0.0F, frequency));
            vibration.amplitude(Math.clamp(amplitude, 0.0F, 1.0F));

            int error = XR10.xrApplyHapticFeedback(this.provider.session, info, XrHapticBaseHeader.create(vibration));
            this.provider.logError(error, "xrApplyHapticFeedback", "");
        }
    }

    @Override
    public synchronized void queueHapticPulse(ControllerType controller, float durationSeconds, float frequency, float amplitude, float delaySeconds) {
        if (this.closed || controller == null || !Float.isFinite(durationSeconds) ||
            !Float.isFinite(frequency) || !Float.isFinite(amplitude) || !Float.isFinite(delaySeconds))
        {
            return;
        }
        this.executor.schedule(() ->
        {
            this.triggerHapticPulse(controller, durationSeconds, frequency, amplitude);
        }, Math.max(0L, (long) (delaySeconds * 1000000.0F)), TimeUnit.MICROSECONDS);
    }

    public synchronized void shutdown() {
        this.closed = true;
        this.executor.shutdownNow();
    }
}
