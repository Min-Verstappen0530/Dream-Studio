package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
abstract class Z8IrisHandFallbackMixin {
    @Inject(method = "isPackInUseQuick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void physicalCamera$allowHandAfterShaders(CallbackInfoReturnable<Boolean> callback) {
        if (CameraClient.drawingDeferredHands()) {
            callback.setReturnValue(false);
        }
    }
}
