package org.vivecraft.mixin.client_vr.questvulkan;

import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.provider.openxr.compat.QuestVulkanFlatSurface;

@Mixin(VulkanDevice.class)
public abstract class DeviceSurfaceMixin {
    @Inject(method = "createSurface", at = @At("HEAD"), cancellable = true)
    private void vivecraft$deferPanel(CallbackInfoReturnable<GpuSurfaceBackend> cir) {
        cir.setReturnValue(new QuestVulkanFlatSurface((VulkanDevice) (Object) this));
    }
}
