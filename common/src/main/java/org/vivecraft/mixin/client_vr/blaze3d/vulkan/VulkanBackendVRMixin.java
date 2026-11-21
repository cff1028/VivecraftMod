package org.vivecraft.mixin.client_vr.blaze3d.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Mixin;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.provider.openxr.QuestVulkanContext;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Mixin(VulkanBackend.class)
public class VulkanBackendVRMixin {
    @WrapMethod(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;")
    private GpuDevice vivecraft$createGameDevice(long window, ShaderSource shaders, GpuDebugOptions options,
                                                 Runnable onDeviceLost, Operation<GpuDevice> original) {
        return QuestVulkanContext.createGameDevice(() -> original.call(window, shaders, options, onDeviceLost));
    }

    @WrapOperation(method = "findPhysicalDevice", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkEnumeratePhysicalDevices(Lorg/lwjgl/vulkan/VkInstance;Ljava/nio/IntBuffer;Lorg/lwjgl/PointerBuffer;)I"), require = 2)
    private static int vivecraft$selectQuestGpu(VkInstance instance, IntBuffer count, PointerBuffer out,
                                               Operation<Integer> original) {
        QuestVulkanContext context = QuestVulkanContext.creating();
        return context == null || context.usesGameDevice() ? original.call(instance, count, out) :
            context.enumerateDevices(instance, count, out);
    }

    @WrapOperation(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int vivecraft$createQuestDevice(VkPhysicalDevice gpu, VkDeviceCreateInfo info,
                                                  VkAllocationCallbacks allocator, PointerBuffer out,
                                                  Operation<Integer> original) {
        QuestVulkanContext context = QuestVulkanContext.creating();
        return context == null ? original.call(gpu, info, allocator, out) :
            context.createDevice(gpu, info, allocator, out, () -> original.call(gpu, info, allocator, out));
    }

    @Inject(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
    private void vivecraft$vrDeviceExtensions(
        CallbackInfoReturnable<GpuDevice> cir, @Local VulkanPhysicalDevice physicalDevice,
        @Local(ordinal = 0) Set<String> deviceExtensions)
    {
        Set<String> questExtensions = QuestVulkanContext.additionalExtensions(false, physicalDevice::hasDeviceExtension);
        if (!questExtensions.isEmpty()) deviceExtensions.addAll(questExtensions);
        if (!ClientDataHolderVR.getInstance().vrSettings.requiredVulkanDeviceExtensions.isEmpty()) {
            // check that all extensions are supported before enabling anything
            String[] neededExtensions = ClientDataHolderVR.getInstance().vrSettings.requiredVulkanDeviceExtensions.split(
                " ");
            List<String> missingExtensions = new ArrayList<>();
            for (String extension : neededExtensions) {
                if (!physicalDevice.hasDeviceExtension(extension)) {
                    missingExtensions.add(extension);
                }
            }
            if (missingExtensions.isEmpty()) {
                // all available, enable them
                deviceExtensions.addAll(Arrays.asList(neededExtensions));
            }
        }
    }
}
