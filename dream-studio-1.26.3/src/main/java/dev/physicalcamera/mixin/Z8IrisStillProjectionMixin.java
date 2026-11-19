package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms$Previous", remap = false)
abstract class Z8IrisStillProjectionMixin {
    @Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("RETURN"), cancellable = true,
            locals = LocalCapture.CAPTURE_FAILSOFT, require = 0)
    private void physicalCamera$holdStill(CallbackInfoReturnable<Matrix4fc> callback, Matrix4f current) {
        // Keep the normal history update, but remove the virtual zoom from the still frame's motion vectors.
        if (CameraClient.pendingIrisPhoto() && !CameraClient.accumulatingIrisHistory()
                && CameraClient.capturingWorldFrame()) {
            callback.setReturnValue(current);
        }
    }
}
