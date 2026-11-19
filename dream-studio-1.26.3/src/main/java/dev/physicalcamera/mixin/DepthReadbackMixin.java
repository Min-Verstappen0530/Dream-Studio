package dev.physicalcamera.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.physicalcamera.CameraClient;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
abstract class DepthReadbackMixin {
    @Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            require = 0,
            at = @At(value = "INVOKE", target =
                    "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glFramebufferTexture2D(IIIII)V"))
    private void physicalCamera$restoreReadAttachment(GpuTexture source, GpuBuffer buffer, long offset,
            Runnable callback, int mip, int x, int y, int width, int height, CallbackInfo info) {
        if (CameraClient.ownsDepthReadback(buffer)) {
            // The encoder temporarily selects GL_NONE and reuses this FBO for colour copies.
            GlStateManager._glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        }
    }
}
