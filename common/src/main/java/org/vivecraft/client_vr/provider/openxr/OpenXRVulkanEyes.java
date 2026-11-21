package org.vivecraft.client_vr.provider.openxr;

import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import org.lwjgl.openxr.XR10;
import org.lwjgl.openxr.XrSwapchain;
import org.lwjgl.openxr.XrSwapchainImageBaseHeader;
import org.lwjgl.openxr.XrSwapchainImageVulkan2KHR;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.vivecraft.client_vr.VRTextureTarget;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;
import org.vivecraft.client_vr.render.helpers.graphics.VulkanHelper;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

final class OpenXRVulkanEyes implements AutoCloseable {
    private final VulkanDevice device;
    private final long[] images;
    private long stagingImage;
    private long stagingAllocation;
    private final boolean stereoSync = "1".equals(System.getenv("FCL_VR_STEREO_SYNC"));
    private GpuFence lastCopy;

    static int selectFormat(LongBuffer formats) {
        VulkanDevice device = ((VulkanHelper) GraphicsHelper.INSTANCE).getVulkanDevice();
        long[] offered = new long[formats.remaining()];
        formats.get(formats.position(), offered);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VK_FORMAT_R8G8B8A8_SRGB, properties);
            int stageFeatures = VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT | VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT |
                VK_FORMAT_FEATURE_BLIT_SRC_BIT;
            boolean canStage = (properties.optimalTilingFeatures() & stageFeatures) == stageFeatures;
            for (int format : new int[]{VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_A8B8G8R8_SRGB_PACK32,
                VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM}) {
                if (Arrays.stream(offered).noneMatch(value -> value == format)) continue;
                vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), format, properties);
                int required = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
                if (needsBlit(format)) {
                    if (!canStage) continue;
                    required |= VK_FORMAT_FEATURE_BLIT_DST_BIT;
                }
                if ((properties.optimalTilingFeatures() & required) == required) return format;
            }
        }
        throw new IllegalStateException("No OpenXR swapchain format compatible with RGBA8 eye images: " +
            Arrays.toString(offered));
    }

    private static boolean needsBlit(long format) {
        return format != VK_FORMAT_R8G8B8A8_SRGB && format != VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    }

    OpenXRVulkanEyes(XrSwapchain swapchain, IntBuffer count, long format, int width, int height, MemoryStack stack) {
        this.device = ((VulkanHelper) GraphicsHelper.INSTANCE).getVulkanDevice();
        int imageCount = count.get(0);
        var headers = XrSwapchainImageVulkan2KHR.calloc(imageCount, stack);
        for (int i = 0; i < imageCount; i++) headers.get(i).type$Default();
        int result = XR10.xrEnumerateSwapchainImages(swapchain, count,
            XrSwapchainImageBaseHeader.create(headers.address(), imageCount));
        if (result != XR10.XR_SUCCESS || count.get(0) != imageCount) {
            throw new IllegalStateException("xrEnumerateSwapchainImages(Vulkan) failed: " + result);
        }
        this.images = new long[imageCount];
        for (int i = 0; i < imageCount; i++) {
            this.images[i] = headers.get(i).image();
            if (this.images[i] == VK_NULL_HANDLE) throw new IllegalStateException("Null OpenXR VkImage at " + i);
        }
        if (needsBlit(format)) {
            // Minecraft stores gamma-encoded RGB in UNORM targets.
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_SRGB).mipLevels(1).arrayLayers(2).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            var allocationInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var handle = stack.mallocLong(1);
            var allocation = stack.mallocPointer(1);
            VulkanUtils.crashIfFailure(this.device, Vma.vmaCreateImage(this.device.vma(), info, allocationInfo,
                handle, allocation, null), "Failed to allocate OpenXR format conversion image");
            this.stagingImage = handle.get(0);
            this.stagingAllocation = allocation.get(0);
        }
    }

    void submit(int index, VRTextureTarget[] eyes, MemoryStack stack) {
        var encoder = this.device.createCommandEncoder();
        VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
        recordCopy(commands, GraphicsHelper.INSTANCE.getTextureHandle(eyes[0].getColorTexture()),
            GraphicsHelper.INSTANCE.getTextureHandle(eyes[1].getColorTexture()), this.images[index],
            this.stagingImage, eyes[0].width, eyes[0].height, stack);
        VulkanUtils.crashIfFailure(this.device, vkEndCommandBuffer(commands), "Failed to end OpenXR stereo copy");
        encoder.execute(commands);
        GpuFence completion = encoder.createFence();
        try {
            encoder.submit();
        } catch (RuntimeException | Error e) {
            completion.close();
            throw e;
        }
        if (this.lastCopy != null) this.lastCopy.close();
        this.lastCopy = completion;
        if (this.stereoSync) awaitLastCopy();
    }

    private static void recordCopy(VkCommandBuffer commands, long left, long right, long destination,
        long staging, int width, int height, MemoryStack stack)
    {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(staging != VK_NULL_HANDLE ? 4 : 3, stack);
        barrier(barriers.get(0), destination, 2, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT);
        for (int eye = 0; eye < 2; eye++) {
            barrier(barriers.get(eye + 1), eye == 0 ? left : right,
                1, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        }
        if (staging != VK_NULL_HANDLE) {
            barrier(barriers.get(3), staging, 2, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT);
        }
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, null, null, barriers);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        for (int eye = 0; eye < 2; eye++) {
            region.dstSubresource().baseArrayLayer(eye);
            region.extent().set(width, height, 1);
            vkCmdCopyImage(commands, barriers.get(eye + 1).image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                staging != VK_NULL_HANDLE ? staging : destination, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        if (staging != VK_NULL_HANDLE) {
            barriers.position(3);
            barrier(barriers.get(3), staging, 2, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                null, null, barriers);
            var blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(2);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(2);
            blit.srcOffsets(1).set(width, height, 1);
            blit.dstOffsets(1).set(width, height, 1);
            vkCmdBlitImage(commands, staging, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            barriers.position(0).limit(3);
        }
        for (int i = 0; i < 3; i++) {
            var barrier = barriers.get(i);
            int originalLayout = barrier.oldLayout();
            barrier.oldLayout(barrier.newLayout()).newLayout(originalLayout);
            barrier.srcAccessMask(barrier.dstAccessMask()).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        }
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0, null, null, barriers);
    }

    private static void barrier(VkImageMemoryBarrier barrier, long image, int layers, int oldLayout,
        int newLayout, int srcAccess, int dstAccess)
    {
        barrier.sType$Default().srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(layers);
    }

    private void awaitLastCopy() {
        if (this.lastCopy == null) return;
        if (!this.lastCopy.awaitCompletion(Long.MAX_VALUE)) throw new IllegalStateException("OpenXR stereo copy timed out");
        this.lastCopy.close();
        this.lastCopy = null;
    }

    @Override
    public void close() {
        awaitLastCopy();
        if (this.stagingImage != VK_NULL_HANDLE) {
            Vma.vmaDestroyImage(this.device.vma(), this.stagingImage, this.stagingAllocation);
            this.stagingImage = VK_NULL_HANDLE;
        }
    }
}
