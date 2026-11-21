package org.vivecraft.mixin.client_vr.blaze3d.vulkan;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.extensions.vulkan.VulkanInstanceExtension;
import org.vivecraft.client_vr.provider.openxr.QuestVulkanContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Mixin(VulkanInstance.class)
public class VulkanInstanceVRMixin implements VulkanInstanceExtension {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private int vivecraft$createQuestInstance(VkInstanceCreateInfo info, VkAllocationCallbacks allocator,
                                               PointerBuffer out, Operation<Integer> original) {
        QuestVulkanContext context = QuestVulkanContext.creating();
        return context == null ? original.call(info, allocator, out) : context.createInstance(info, allocator, out,
            () -> original.call(info, allocator, out));
    }

    @Unique
    private Set<String> vivecraft$availableExtensions;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;"), index = 3)
    private Set<String> vivecraft$vrInstanceExtensions(
        Set<String> enabledExtensions, @Local Set<String> availableExtensions)
    {
        if (!ClientDataHolderVR.getInstance().vrSettings.requiredVulkanInstanceExtensions.isEmpty()) {
            // check that all extensions are supported before enabling anything
            String[] neededExtensions = ClientDataHolderVR.getInstance().vrSettings.requiredVulkanInstanceExtensions.split(
                " ");
            List<String> missingExtensions = new ArrayList<>();
            for (String extension : neededExtensions) {
                if (!availableExtensions.contains(extension)) {
                    missingExtensions.add(extension);
                }
            }
            if (missingExtensions.isEmpty()) {
                // all available, enable them
                enabledExtensions.addAll(Arrays.asList(neededExtensions));
            }
        }
        Set<String> questExtensions = QuestVulkanContext.additionalExtensions(true, availableExtensions::contains);
        if (!questExtensions.isEmpty()) enabledExtensions.addAll(questExtensions);
        this.vivecraft$availableExtensions = ImmutableSet.copyOf(availableExtensions);
        return enabledExtensions;
    }

    @Override
    public Set<String> vivecraft$getAvailableExtensions() {
        return this.vivecraft$availableExtensions;
    }
}
