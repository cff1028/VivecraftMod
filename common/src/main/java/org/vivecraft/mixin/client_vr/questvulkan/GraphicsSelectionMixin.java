package org.vivecraft.mixin.client_vr.questvulkan;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class GraphicsSelectionMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/blaze3d/systems/GpuBackend;"))
    private GpuBackend[] vivecraft$questVulkan(GpuBackend[] original) {
        return new GpuBackend[]{new VulkanBackend()};
    }
}
