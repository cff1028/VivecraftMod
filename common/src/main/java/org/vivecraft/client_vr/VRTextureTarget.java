package org.vivecraft.client_vr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL30;
import org.vivecraft.Xplat;
import org.vivecraft.client.extensions.RenderTargetExtension;
import org.vivecraft.client_vr.render.helpers.graphics.GraphicsHelper;

/**
 * extension of a regular RenderTarget that sets Vivecraft features on creation
 */
public class VRTextureTarget extends RenderTarget {

    private final String name;

    // Swapchain textures belong to OpenXR.
    private boolean externalTexture;

    public boolean anisotropicFiltering;

    private VRTextureTarget(
        String name, int width, int height, boolean useDepth, int texId, boolean linearFilter, boolean mipmaps,
        boolean anisotropicFiltering, boolean useStencil, Vector4fc clearColor)
    {
        super(useDepth);

        this.setClearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());

        this.name = name;
        RenderSystem.assertOnRenderThreadOrInit();
        ((RenderTargetExtension) this).vivecraft$setTexId(texId);
        ((RenderTargetExtension) this).vivecraft$setLinearFilter(linearFilter);
        ((RenderTargetExtension) this).vivecraft$setMipmaps(mipmaps);
        this.anisotropicFiltering = anisotropicFiltering;

        // need to set this first, because the forge/neoforge stencil enabled does a resize
        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;

        if (useStencil && !Xplat.enableRenderTargetStencil(this)) {
            // use our stencil only if the modloader doesn't support it
            ((RenderTargetExtension) this).vivecraft$setStencil(true);
        }
        this.resize(width, height);
    }

    /** Wraps a swapchain layer without taking ownership of its texture. */
    public VRTextureTarget(String name, int width, int height, int colorId, int layer) {
        super(false);
        this.name = name;
        this.externalTexture = true;
        RenderSystem.assertOnRenderThreadOrInit();
        this.width = this.viewWidth = width;
        this.height = this.viewHeight = height;
        this.colorTextureId = colorId;
        this.frameBufferId = GlStateManager.glGenFramebuffers();
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBufferId);
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, colorId, 0, layer);
            this.checkStatus();
        } catch (RuntimeException exception) {
            this.destroyBuffers();
            throw exception;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        this.setClearColor(0F, 0F, 0F, 0F);
    }

    @Override
    public void destroyBuffers() {
        if (this.externalTexture) {
            // Prevent RenderTarget from deleting the runtime texture.
            this.colorTextureId = -1;
        }
        super.destroyBuffers();
    }

    @Override
    public void createBuffers(int width, int height) {
        super.createBuffers(width, height);

        if (((RenderTargetExtension) this).vivecraft$hasMipmaps()) {
            if (this.anisotropicFiltering) {
                GraphicsHelper.INSTANCE.enableAnisotropicFiltering(this);
            }
            // generate mipmaps so they are initialized
            GraphicsHelper.INSTANCE.genMipmaps(this);
        }
    }

    @Override
    public String toString() {
        return """
            
            Vivecraft RenderTarget: %s
            Size: %s x %s
            FB ID: %s
            Tex ID: %s"""
            .formatted(
                this.name,
                this.viewWidth, this.viewHeight,
                this.frameBufferId,
                this.colorTextureId);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;

        private int width;
        private int height;

        private boolean useDepth;
        private int texId = -1;

        private boolean linearFilter;

        private boolean mipmaps;
        private boolean anisotropicFiltering;

        private boolean stencil;

        private final Vector4f clearColor = new Vector4f(0);

        private Builder(String name) {
            this.name = name;
        }

        public Builder withSize(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder withTexId(int texId) {
            this.texId = texId;
            return this;
        }

        public Builder withDepth() {
            this.useDepth = true;
            return this;
        }

        public Builder withLinearFilter() {
            this.linearFilter = true;
            return this;
        }

        public Builder withMipmaps(boolean useMipmaps) {
            this.mipmaps = useMipmaps;
            return this;
        }

        public Builder withAnisotropicFiltering(boolean useAF) {
            this.anisotropicFiltering = useAF;
            return this;
        }

        public Builder withStencil(boolean useStencil) {
            this.stencil = useStencil;
            return this;
        }

        public Builder withClearColor(float red, float green, float blue, float alpha) {
            this.clearColor.set(red, green, blue, alpha);
            return this;
        }

        public VRTextureTarget build() {
            if (this.width <= 0 || this.height <= 0) {
                throw new IllegalArgumentException("Width and height must be greater than 0");
            }
            return new VRTextureTarget(
                this.name,
                this.width, this.height,
                this.useDepth,
                this.texId,
                this.linearFilter,
                this.mipmaps,
                this.anisotropicFiltering,
                this.stencil,
                this.clearColor);
        }
    }
}
