package org.vivecraft.mixin.client_vr.blaze3d.opengl;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.provider.openxr.OpenXRSwapchainTarget;

import java.util.List;

@Mixin(FrameBufferCache.class)
public abstract class OpenXRFrameBufferCacheMixin {
    @Inject(method = "getFbo", at = @At("HEAD"), cancellable = true)
    private void vivecraft$openxrLayer(
        DirectStateAccess dsa, List<FrameBufferAttachment> colors, FrameBufferAttachment depth,
        CallbackInfoReturnable<Integer> cir)
    {
        for (FrameBufferAttachment attachment : colors) {
            if (attachment instanceof OpenXRSwapchainTarget.LayerView layer) {
                if (colors.size() != 1 || depth != null) {
                    throw new IllegalArgumentException("OpenXR output requires one color layer and no depth attachment");
                }
                cir.setReturnValue(layer.framebuffer());
                return;
            }
        }
    }
}
