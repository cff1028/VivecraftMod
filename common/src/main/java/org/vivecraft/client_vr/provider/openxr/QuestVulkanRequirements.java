package org.vivecraft.client_vr.provider.openxr;

import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.vivecraft.client_vr.settings.VRSettings;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.openxr.XR10.XR_MAKE_VERSION;
import static org.lwjgl.vulkan.VK10.*;

final class QuestVulkanRequirements {
    private static final Set<String> INSTANCE = Set.of(
        "VK_KHR_get_physical_device_properties2", "VK_KHR_external_memory_capabilities",
        "VK_KHR_external_semaphore_capabilities", "VK_KHR_external_fence_capabilities");
    private static final Set<String> DEVICE = Set.of(
        "VK_KHR_external_memory", "VK_KHR_external_memory_fd", "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_semaphore", "VK_KHR_external_semaphore_fd", "VK_KHR_external_fence",
        "VK_KHR_external_fence_fd", "VK_EXT_queue_family_foreign", "VK_KHR_get_memory_requirements2",
        "VK_KHR_dedicated_allocation", "VK_KHR_bind_memory2", "VK_KHR_sampler_ycbcr_conversion",
        "VK_KHR_maintenance1", "VK_KHR_maintenance2", "VK_KHR_multiview", "VK_KHR_image_format_list");

    private Set<String> enabledInstance = Set.of();
    private Set<String> enabledDevice = Set.of();

    static Set<String> toEnable(boolean instance, String remembered, Predicate<String> supported) {
        Set<String> result = new LinkedHashSet<>(instance ? INSTANCE : DEVICE);
        result.addAll(names(remembered));
        result.removeIf(name -> !supported.test(name));
        return result;
    }

    void record(boolean instance, PointerBuffer extensions) {
        Set<String> enabled = new LinkedHashSet<>();
        if (extensions != null) {
            for (int i = extensions.position(); i < extensions.limit(); i++) enabled.add(extensions.getStringASCII(i));
        }
        if (instance) this.enabledInstance = enabled;
        else this.enabledDevice = enabled;
    }

    String validate(MemoryStack stack, XrInstance xr, long system, VkDevice device, int apiVersion, VRSettings settings) {
        var req = XrGraphicsRequirementsVulkanKHR.calloc(stack).type$Default();
        MCOpenXR.checkResult(KHRVulkanEnable.xrGetVulkanGraphicsRequirementsKHR(xr, system, req),
            "xrGetVulkanGraphicsRequirementsKHR");
        long api = XR_MAKE_VERSION(VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion),
            VK_API_VERSION_PATCH(apiVersion));
        VRSettings.LOGGER.info("Vivecraft: Vulkan API requested={} OpenXR range={}..{}", api,
            req.minApiVersionSupported(), req.maxApiVersionSupported());
        if (api < req.minApiVersionSupported() || api > req.maxApiVersionSupported()) {
            throw new IllegalStateException("Game Vulkan API version is outside the OpenXR runtime range");
        }
        Set<String> instances = queryExtensions(stack, xr, system, true);
        Set<String> devices = queryExtensions(stack, xr, system, false);
        VRSettings.LOGGER.info("Vivecraft: OpenXR requires Vulkan instance={} device={}", instances, devices);
        if (this.enabledInstance.containsAll(instances) && this.enabledDevice.containsAll(devices)) {
            var gpu = device.getPhysicalDevice();
            PointerBuffer out = stack.callocPointer(1);
            MCOpenXR.checkResult(KHRVulkanEnable.xrGetVulkanGraphicsDeviceKHR(xr, system, gpu.getInstance(), out),
                "xrGetVulkanGraphicsDeviceKHR");
            if (out.get(0) != gpu.address()) {
                throw new IllegalStateException("OpenXR selected a different physical GPU than Minecraft");
            }
            return null;
        }

        String instanceNames = String.join(" ", new TreeSet<>(instances));
        String deviceNames = String.join(" ", new TreeSet<>(devices));
        instances.removeAll(this.enabledInstance);
        devices.removeAll(this.enabledDevice);
        String missing = "instance=" + instances + ", device=" + devices;
        if ((!instances.isEmpty() && !advertised(stack, device, true).containsAll(instances)) ||
            (!devices.isEmpty() && !advertised(stack, device, false).containsAll(devices)) ||
            (names(settings.questVulkanInstanceExtensions).containsAll(instances) &&
                names(settings.questVulkanDeviceExtensions).containsAll(devices))) {
            throw new IllegalStateException("The selected Vulkan driver could not enable OpenXR extensions: " + missing);
        }
        settings.questVulkanInstanceExtensions = instanceNames;
        settings.questVulkanDeviceExtensions = deviceNames;
        settings.saveOptions();
        return missing;
    }

    private static Set<String> advertised(MemoryStack stack, VkDevice device, boolean instance) {
        IntBuffer count = stack.callocInt(1);
        int result = instance ? vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null) :
            vkEnumerateDeviceExtensionProperties(device.getPhysicalDevice(), (ByteBuffer) null, count, null);
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan extension enumeration failed: " + result);
        var properties = VkExtensionProperties.calloc(count.get(0), stack);
        result = instance ? vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties) :
            vkEnumerateDeviceExtensionProperties(device.getPhysicalDevice(), (ByteBuffer) null, count, properties);
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan extension enumeration failed: " + result);
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < count.get(0); i++) names.add(properties.get(i).extensionNameString());
        return names;
    }

    private static Set<String> queryExtensions(MemoryStack stack, XrInstance xr, long system, boolean instance) {
        IntBuffer count = stack.callocInt(1);
        String call = instance ? "xrGetVulkanInstanceExtensionsKHR" : "xrGetVulkanDeviceExtensionsKHR";
        MCOpenXR.checkResult(query(xr, system, instance, count, null), call);
        if (count.get(0) == 0) return new LinkedHashSet<>();
        ByteBuffer buffer = stack.calloc(count.get(0));
        MCOpenXR.checkResult(query(xr, system, instance, count, buffer), call);
        return names(memASCII(buffer, Math.max(0, count.get(0) - 1)));
    }

    private static int query(XrInstance xr, long system, boolean instance, IntBuffer count, ByteBuffer buffer) {
        return instance ? KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR(xr, system, count, buffer) :
            KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR(xr, system, count, buffer);
    }

    private static Set<String> names(String extensions) {
        Set<String> result = new LinkedHashSet<>();
        if (!extensions.isBlank()) result.addAll(Arrays.asList(extensions.trim().split(" +")));
        return result;
    }
}
