package dev.physicalcamera.mixin;

import dev.physicalcamera.RemixQuadIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter", remap = false)
abstract class RemixCameraShellMixin {
    @Shadow private static boolean standsOnQuad(float[] px,float[] py,float[] pz,int face,
                                               float x,float y,float z,float nx,float ny,float nz) {
        throw new AssertionError();
    }

    @Inject(method = "quadShellOffsets", at = @At("HEAD"), cancellable = true)
    private static void physicalCamera$indexDenseShell(float[] px,float[] py,float[] pz,float[] nx,float[] ny,float[] nz,
                                                      int start,int count,float depth,int cardFace,
                                                      float[] ox,float[] oy,float[] oz,CallbackInfo ci) {
        if (count > 4096 && RemixQuadIndex.offsets(px,py,pz,nx,ny,nz,start,count,depth,cardFace,ox,oy,oz,
                RemixCameraShellMixin::standsOnQuad)) ci.cancel();
    }
}
