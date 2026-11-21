package org.vivecraft.mixin.client_vr.blaze3d.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.Mixin;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.provider.openxr.QuestVulkanContext;

import java.nio.IntBuffer;
import java.util.*;

@Mixin(VulkanBackend.class)
public class VulkanBackendVRMixin {
    @WrapMethod(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;")
    private GpuDevice vivecraft$createGameDevice(GpuDebugOptions options, Operation<GpuDevice> original) {
        return QuestVulkanContext.createGameDevice(() -> original.call(options));
    }

    @WrapOperation(method = "findPhysicalDevice", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkEnumeratePhysicalDevices(Lorg/lwjgl/vulkan/VkInstance;Ljava/nio/IntBuffer;Lorg/lwjgl/PointerBuffer;)I"), require = 2)
    private static int vivecraft$selectQuestGpu(VkInstance instance, IntBuffer count, PointerBuffer out,
                                               Operation<Integer> original) {
        QuestVulkanContext context = QuestVulkanContext.creating();
        return context == null || context.usesGameDevice() ? original.call(instance, count, out) :
            context.enumerateDevices(instance, count, out);
    }

    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int vivecraft$createQuestDevice(VkPhysicalDevice gpu, VkDeviceCreateInfo info,
                                                  VkAllocationCallbacks allocator, PointerBuffer out,
                                                  Operation<Integer> original) {
        QuestVulkanContext context = QuestVulkanContext.creating();
        return context == null ? original.call(gpu, info, allocator, out) :
            context.createDevice(gpu, info, allocator, out, () -> original.call(gpu, info, allocator, out));
    }

    @ModifyArg(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;<init>(Ljava/lang/String;Ljava/util/Collection;)V"), index = 1)
    private Collection<FeatureSet> vivecraft$vrDeviceExtensions(
        Collection<FeatureSet> enabledFeatureSets, @Local VulkanPhysicalDevice physicalDevice,
        @Local(ordinal = 0) Set<String> deviceExtensions)
    {
        Set<String> questExtensions = QuestVulkanContext.additionalExtensions(false, physicalDevice::hasDeviceExtension);
        if (!questExtensions.isEmpty()) enabledFeatureSets.add(new FeatureSet("Quest OpenXR", questExtensions, Set.of()));
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
                enabledFeatureSets.add(
                    new FeatureSet("VR Extensions", new HashSet<>(Arrays.asList(neededExtensions)), Set.of()));
            }
        }
        return enabledFeatureSets;
    }
}
