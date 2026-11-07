package org.vivecraft.client_vr.provider.openxr;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;

public final class OpenXRSwapchainTarget extends RenderTarget {
    public OpenXRSwapchainTarget(String name, int width, int height, int texture, int layer, long format) {
        super(name, false, gpuFormat(format));
        RenderSystem.assertOnRenderThread();
        if (layer < 0 || layer > 1) throw new IllegalArgumentException("Invalid stereo layer: " + layer);
        if (width <= 0 || height <= 0 || texture == 0) {
            throw new IllegalArgumentException("Invalid OpenXR swapchain image");
        }
        this.width = width;
        this.height = height;
        FrameBufferCache cache = new FrameBufferCache();
        this.colorTexture = new ExternalTexture(name, this.format, width, height, texture, cache);
        this.colorTextureView = new LayerView((GlTexture) this.colorTexture, layer, cache);
    }

    private static GpuFormat gpuFormat(long format) {
        // GpuFormat describes channel layout; OpenXR retains the original sRGB storage.
        return switch ((int) format) {
            case GL21C.GL_SRGB8_ALPHA8, GL11C.GL_RGBA8 -> GpuFormat.RGBA8_UNORM;
            case GL21C.GL_SRGB8 -> GpuFormat.RGB8_UNORM;
            case GL11C.GL_RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
            case GL30C.GL_RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case GL30C.GL_RGB16F -> GpuFormat.RGB16_FLOAT;
            case GL31C.GL_RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            default -> throw new IllegalArgumentException("Unsupported OpenXR swapchain format: " + format);
        };
    }

    public LayerView layerView() {
        return (LayerView) this.colorTextureView;
    }

    @Override
    public void resize(int width, int height) {
        throw new UnsupportedOperationException("OpenXR owns the swapchain image dimensions");
    }

    @Override
    public void createBuffers(int width, int height) {
        throw new UnsupportedOperationException("OpenXR owns the swapchain images");
    }

    private static final class ExternalTexture extends GlTexture {
        private ExternalTexture(String name, GpuFormat format, int width, int height, int texture, FrameBufferCache cache) {
            // Expose one logical layer to RenderPass validation.
            super(GpuTexture.USAGE_RENDER_ATTACHMENT, name, format, width, height, 1, 1, texture, cache);
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public void addViews() {}

        @Override
        public void removeViews() {}
    }

    public static final class LayerView extends GlTextureView {
        private final int layer;
        private int framebuffer;

        private LayerView(GlTexture texture, int layer, FrameBufferCache cache) {
            super(texture, 0, 1, cache);
            this.layer = layer;
        }

        public int framebuffer() {
            if (this.isClosed() || this.texture().isClosed()) {
                throw new IllegalStateException("OpenXR swapchain view is closed");
            }
            if (this.framebuffer != 0) return this.framebuffer;
            int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            this.framebuffer = GL30C.glGenFramebuffers();
            if (this.framebuffer == 0) throw new IllegalStateException("Could not create OpenXR layer framebuffer");
            try {
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
                GL30C.glFramebufferTextureLayer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    this.glId(), 0, this.layer);
                int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
                if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("OpenXR layer framebuffer incomplete: " + status);
                }
                int actualLayer = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER,
                    GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER);
                if (actualLayer != this.layer) {
                    throw new IllegalStateException("OpenXR layer mismatch: " + actualLayer + " != " + this.layer);
                }
                return this.framebuffer;
            } catch (RuntimeException exception) {
                GlStateManager._glDeleteFramebuffers(this.framebuffer);
                this.framebuffer = 0;
                throw exception;
            } finally {
                GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            }
        }

        @Override
        public void close() {
            if (this.framebuffer != 0) {
                GlStateManager._glDeleteFramebuffers(this.framebuffer);
                this.framebuffer = 0;
            }
            super.close();
        }
    }
}
