package org.vivecraft.mixin.client_vr.questvulkan;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VulkanPhysicalDevice.class)
public abstract class PhysicalDeviceMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwGetPhysicalDevicePresentationSupport(Lorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkPhysicalDevice;I)Z"))
    private boolean vivecraft$androidGraphicsQueue(VkInstance instance, VkPhysicalDevice device, int family) {
        // Check presentation support once the Android panel exists.
        return true;
    }
}
