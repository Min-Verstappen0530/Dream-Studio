package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraRender1211;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.nvidia.rtxremix.minecraft.RtxRemixMod", remap = false)
abstract class RemixFrameMixin {
    @Inject(method = "submitRemixFrameIfReady", at = @At("TAIL"))
    private static void physicalCamera$readTracedFrame(CallbackInfo ci) {
        CameraRender1211.afterRemixWorld();
    }
}
