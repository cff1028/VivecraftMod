package org.vivecraft.mixin.client_vr.questvulkan;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.vulkan.VK;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanBackend.class)
public abstract class BackendMixin {
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private void vivecraft$useGameLoader(CallbackInfo ci) throws BackendCreationException {
        if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable() ||
            VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr") == 0)
        {
            throw new BackendCreationException("FCL Vulkan loader has no vkGetInstanceProcAddr",
                BackendCreationException.Reason.PLATFORM_ERROR);
        }
        ci.cancel();
    }

    @Inject(method = "unloadLibrary", at = @At("HEAD"), cancellable = true)
    private void vivecraft$keepGameLoader(CallbackInfo ci) {
        ci.cancel();
    }
}
