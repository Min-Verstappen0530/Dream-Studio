package dev.physicalcamera.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface PhotoTargetAttachments {
    @Accessor("colorTexture") void physicalCamera$setColor(GpuTexture texture);
    @Accessor("colorTextureView") void physicalCamera$setColorView(GpuTextureView view);
    @Accessor("depthTexture") void physicalCamera$setDepth(GpuTexture texture);
    @Accessor("depthTextureView") void physicalCamera$setDepthView(GpuTextureView view);
}
