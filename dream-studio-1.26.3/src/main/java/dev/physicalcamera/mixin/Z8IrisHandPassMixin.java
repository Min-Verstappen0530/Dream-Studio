package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
abstract class Z8IrisHandPassMixin {
    @Inject(method = {"renderSolid","renderTranslucent"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void physicalCamera$excludeHandsFromScene(CallbackInfo callback) {
        if (CameraClient.capturingWorldFrame()) {
            callback.cancel();
        }
    }
}
