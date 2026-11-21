package org.vivecraft.client_vr.provider.openxr.compat;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vivecraft.client_vr.provider.openxr.QuestVulkanContext;
import org.vivecraft.util.VLoader;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.Set;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public final class QuestVulkanFlatSurface implements GpuSurfaceBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vivecraft/QuestPanel");
    private final VulkanDevice device;
    private GpuSurface.Configuration config;
    private Panel panel;
    private boolean needsConfigure;
    private boolean acquired;
    private long presentedGeneration;

    public QuestVulkanFlatSurface(VulkanDevice device) {
        this.device = device;
    }

    @Override
    public void configure(GpuSurface.Configuration config) {
        this.config = config;
        this.needsConfigure = true;
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return this.panel == null ? Set.of(GpuSurface.PresentMode.FIFO) : this.panel.supportedPresentModes();
    }

    @Override
    public boolean isSuboptimal() {
        return this.panel != null && this.panel.isSuboptimal();
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        long generation = QuestVulkanContext.immersive() ? 0 : VLoader.getVulkanFlatSurfaceGeneration();
        if (this.panel != null && generation != this.panel.generation) this.close();
        if (generation == 0) return;
        try {
            if (this.panel == null) {
                this.panel = this.createPanel(generation);
                this.needsConfigure = true;
            }
            if (this.needsConfigure) {
                if (!this.configurePanel()) return;
                this.needsConfigure = false;
            }
            this.panel.acquireNextTexture();
            this.acquired = true;
        } catch (WindowChanged change) {
            this.invalidate(change);
        }
    }

    private Panel createPanel(long generation) {
        VkPhysicalDevice gpu = this.device.vkDevice().getPhysicalDevice();
        VkInstance instance = gpu.getInstance();
        long[] surface = new long[1];
        int result = VLoader.createVulkanFlatSurface(generation,
            VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), instance.address(), surface);
        check(result, "createVulkanFlatSurface");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int family = this.device.graphicsQueue().queueFamilyIndex();
            IntBuffer supported = stack.callocInt(1);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(gpu, family, surface[0], supported),
                "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (supported.get(0) == VK_FALSE) {
                throw new IllegalStateException("FCL flat surface cannot present on graphics queue family " + family);
            }
            return new Panel(this.device, surface[0], generation);
        } catch (RuntimeException | Error failure) {
            vkDestroySurfaceKHR(instance, surface[0], null);
            throw failure;
        }
    }

    private boolean configurePanel() throws SurfaceException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.device.vkDevice().getPhysicalDevice(),
                this.panel.handle, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            normalizeExtent(caps);
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new IllegalStateException("FCL flat surface does not support transfer destination images");
            }
            int width = caps.currentExtent().width();
            int height = caps.currentExtent().height();
            if (width == -1) {
                width = Math.clamp(this.config.width(), caps.minImageExtent().width(), caps.maxImageExtent().width());
                height = Math.clamp(this.config.height(), caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            if (width <= 0 || height <= 0) return false;
            GpuSurface.PresentMode mode = this.panel.supportedPresentModes().contains(this.config.presentMode()) ?
                this.config.presentMode() : GpuSurface.PresentMode.FIFO;
            this.panel.configure(new GpuSurface.Configuration(width, height, mode));
            return true;
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView source) {
        if (this.acquired) this.panel.blitFromTexture(encoder, source);
    }

    @Override
    public void present() {
        if (!this.acquired) return;
        this.acquired = false;
        try {
            this.panel.present();
            if (this.presentedGeneration != this.panel.generation && !QuestVulkanContext.immersive() &&
                VLoader.getVulkanFlatSurfaceGeneration() == this.panel.generation) {
                this.presentedGeneration = this.panel.generation;
                VLoader.onVulkanFlatFramePresented(this.presentedGeneration);
                LOGGER.info("FCL flat Vulkan panel generation {} presented, VkResult {}",
                    this.presentedGeneration, this.panel.presentResult);
            }
        } catch (WindowChanged change) {
            this.invalidate(change);
        }
    }

    private void invalidate(WindowChanged change) {
        if (change.result == VK_ERROR_OUT_OF_DATE_KHR) this.needsConfigure = true;
        if (change.result == VK_ERROR_SURFACE_LOST_KHR) this.close();
    }

    @Override
    public void close() {
        if (this.panel != null) {
            this.panel.close();
            this.panel = null;
        }
        this.acquired = false;
    }

    public static int checkAcquire(int result) {
        if (result == VK_TIMEOUT || result == VK_NOT_READY) throw new WindowChanged(result);
        return check(result, "vkAcquireNextImageKHR");
    }

    public static void normalizeExtent(VkSurfaceCapabilitiesKHR caps) {
        // Minecraft compares unsigned Vulkan limits as signed ints.
        if (caps.maxImageExtent().width() < 0) caps.maxImageExtent().width(Integer.MAX_VALUE);
        if (caps.maxImageExtent().height() < 0) caps.maxImageExtent().height(Integer.MAX_VALUE);
    }

    public static int check(int result, String operation) {
        if (result == VK_ERROR_SURFACE_LOST_KHR || result == VK_ERROR_OUT_OF_DATE_KHR) {
            throw new WindowChanged(result);
        }
        if (result < 0) throw new IllegalStateException(operation + " failed with VkResult " + result);
        return result;
    }

    private static final class WindowChanged extends RuntimeException {
        final int result;

        WindowChanged(int result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    public static final class Panel extends VulkanGpuSurface {
        private final long handle;
        private final long generation;
        private int presentResult;

        private Panel(VulkanDevice device, long surface, long generation) {
            super(device, surface);
            this.handle = surface;
            this.generation = generation;
        }

        public int presented(int result) {
            this.presentResult = check(result, "vkQueuePresentKHR");
            return result;
        }
    }
}
