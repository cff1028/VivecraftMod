package org.vivecraft.mixin.client_vr.questvulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VulkanInstance.class)
public abstract class InstanceExtensionsMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVulkan;SDL_Vulkan_GetInstanceExtensions()Lorg/lwjgl/PointerBuffer;"))
    private PointerBuffer vivecraft$androidExtensions() {
        MemoryStack stack = MemoryStack.stackGet();
        return stack.pointers(stack.ASCII("VK_KHR_surface"), stack.ASCII("VK_KHR_android_surface"));
    }
}
