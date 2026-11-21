package org.vivecraft.mixin.client_vr.questvulkan;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.vivecraft.client_vr.provider.openxr.compat.QuestVulkanFlatSurface;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

@Mixin(VulkanGpuSurface.class)
public abstract class PanelSurfaceMixin {
    @Shadow @Final private long[] acquireSemaphores;
    @Shadow private int currentAcquireSemaphore;
    @Shadow private int swapchainWidth;
    @Shadow private int swapchainHeight;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwCreateWindowSurface(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int vivecraft$usePanel(VkInstance instance, long handle, VkAllocationCallbacks allocator,
        LongBuffer out, Operation<Integer> original)
    {
        if ((Object) this instanceof QuestVulkanFlatSurface.Panel) {
            out.put(out.position(), handle);
            return VK_SUCCESS;
        }
        return original.call(instance, handle, allocator, out);
    }

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSurface;vkGetPhysicalDeviceSurfacePresentModesKHR(Lorg/lwjgl/vulkan/VkPhysicalDevice;JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)I"))
    private int vivecraft$panelModes(int result) {
        return QuestVulkanFlatSurface.check(result, "vkGetPhysicalDeviceSurfacePresentModesKHR");
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSurface;vkGetPhysicalDeviceSurfaceFormatsKHR(Lorg/lwjgl/vulkan/VkPhysicalDevice;JLjava/nio/IntBuffer;Lorg/lwjgl/vulkan/VkSurfaceFormatKHR$Buffer;)I"))
    private int vivecraft$panelFormats(VkPhysicalDevice gpu, long surface, IntBuffer count,
        VkSurfaceFormatKHR.Buffer formats, Operation<Integer> original)
    {
        int result = original.call(gpu, surface, count, formats);
        if (result < 0 && formats != null) formats.free();
        return QuestVulkanFlatSurface.check(result, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    }

    @ModifyExpressionValue(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSurface;vkGetPhysicalDeviceSurfaceCapabilitiesKHR(Lorg/lwjgl/vulkan/VkPhysicalDevice;JLorg/lwjgl/vulkan/VkSurfaceCapabilitiesKHR;)I"))
    private int vivecraft$surfaceLimits(int result, @Local VkSurfaceCapabilitiesKHR caps) {
        QuestVulkanFlatSurface.check(result, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        QuestVulkanFlatSurface.normalizeExtent(caps);
        return result;
    }

    @ModifyExpressionValue(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int vivecraft$configureResult(int result) {
        return QuestVulkanFlatSurface.check(result, "vkCreateSwapchainKHR");
    }

    @ModifyArg(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"), index = 1)
    private VkSwapchainCreateInfoKHR vivecraft$panelCapabilities(VkSwapchainCreateInfoKHR info,
        @Local VkSurfaceCapabilitiesKHR caps)
    {
        if (caps.maxImageCount() != 0) info.minImageCount(Math.min(info.minImageCount(), caps.maxImageCount()));
        info.compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha()));
        return info;
    }

    @ModifyArg(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"), index = 2)
    private long vivecraft$panelAcquireTimeout(long original) {
        return 50_000_000L;
    }

    @ModifyExpressionValue(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
    private int vivecraft$acquireResult(int result) {
        if (result == VK_TIMEOUT || result == VK_NOT_READY) {
            // Retry with the same unsignalled semaphore.
            this.currentAcquireSemaphore = Math.floorMod(this.currentAcquireSemaphore - 1, this.acquireSemaphores.length);
        }
        return QuestVulkanFlatSurface.checkAcquire(result);
    }

    @ModifyArg(method = "blitFromTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"), index = 5)
    private VkImageBlit.Buffer vivecraft$scaleToPanel(VkImageBlit.Buffer regions, @Local(argsOnly = true) GpuTextureView source) {
        VkImageBlit region = regions.get(0);
        region.srcOffsets(1).set(source.getWidth(0), source.getHeight(0), 1);
        region.dstOffsets(0).set(0, this.swapchainHeight, 0);
        region.dstOffsets(1).set(this.swapchainWidth, 0, 1);
        return regions;
    }

    @ModifyExpressionValue(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int vivecraft$presentResult(int result) {
        return (Object) this instanceof QuestVulkanFlatSurface.Panel panel ? panel.presented(result) : result;
    }
}
