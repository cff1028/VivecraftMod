package org.vivecraft.mixin.client_vr.questvulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.vulkan.VK;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VulkanBackend.class)
public abstract class BackendMixin {
    @Redirect(method = "checkBackendAvailable", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwVulkanSupported()Z"))
    private static boolean vivecraft$gameVulkanLoader() {
        return VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr") != 0;
    }

    @Redirect(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwVulkanSupported()Z"))
    private boolean vivecraft$formalGameLoader() {
        return VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr") != 0;
    }
}
