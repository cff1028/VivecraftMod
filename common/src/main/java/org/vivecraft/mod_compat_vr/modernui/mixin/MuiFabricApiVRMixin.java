package org.vivecraft.mod_compat_vr.modernui.mixin;

import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.extensions.MethodDependentMixin;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.fabric.MuiFabricApi", remap = false)
@MethodDependentMixin("isGLVersionPromoted")
public class MuiFabricApiVRMixin {
    @Inject(method = "isGLVersionPromoted()Z", at = @At("HEAD"), cancellable = true)
    private void vivecraft$keepFclGameWindow(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.getBoolean("fcl.quest.vr")) {
            // ModernUIClient is loaded by Modern UI's mixin plugin before injections are ready.
            cir.setReturnValue(true);
            LoggerFactory.getLogger("Vivecraft/FCL").info(
                "Vivecraft: skipped Modern UI probe window; FCL keeps the Minecraft render context");
        }
    }
}
