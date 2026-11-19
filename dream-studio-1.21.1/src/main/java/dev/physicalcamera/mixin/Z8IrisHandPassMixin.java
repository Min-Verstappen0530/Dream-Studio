package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
abstract class Z8IrisHandPassMixin {
    @Inject(method = {"renderSolid","renderTranslucent"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void physicalCamera$excludeHandsFromScene(CallbackInfo callback) {
        if (CameraClient.capturingWorldFrame() || CameraClient.hasZ8View()) {
            callback.cancel();
        }
    }

    @Inject(method = "isRenderingSolid", at = @At("RETURN"), cancellable = true)
    private void physicalCamera$drawCameraAfterComposite(CallbackInfoReturnable<Boolean> callback) {
        if (CameraClient.drawingDeferredHands()) callback.setReturnValue(true);
    }
}
