package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraRender1211;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.nvidia.rtxremix.minecraft.gl.TextureOverlay", remap = false)
abstract class RemixColourMixin {
    @Inject(method = "renderTextureOverlay", at = @At("RETURN"))
    private void physicalCamera$markColourReady(int texture, CallbackInfo ci) {
        CameraRender1211.remixColourReady();
    }
}
