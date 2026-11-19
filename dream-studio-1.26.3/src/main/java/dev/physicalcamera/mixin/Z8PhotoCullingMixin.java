package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
abstract class Z8PhotoCullingMixin {
    @Inject(method = "createProjectionMatrixForCulling", at = @At("RETURN"), cancellable = true, require = 0)
    private void physicalCamera$includeFullPhotoEdges(CallbackInfoReturnable<Matrix4f> callback) {
        if (!CameraClient.needsCleanWorldFrame()) return;
        double halfTan = CameraClient.pendingFullPhotoHalfTan();
        var window = Minecraft.getInstance().getWindow();
        float aspect = window.getWidth()/(float)window.getHeight();
        var size = CameraClient.pendingNativePhotoSize();
        halfTan = halfTan > 0 && size != null ? halfTan*Math.max(1,size.width/(double)size.height/aspect)
                : CameraClient.liveProjectionHalfTan(aspect);
        var projection = callback.getReturnValue();
        if (halfTan > 1/projection.m11()) {
            callback.setReturnValue(new Matrix4f(projection)
                    .m00((float)(1/(aspect*halfTan))).m11((float)(1/halfTan)));
        }
    }
}
