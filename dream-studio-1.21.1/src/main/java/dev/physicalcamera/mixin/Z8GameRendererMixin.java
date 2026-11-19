package dev.physicalcamera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.CameraRender1211;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class Z8GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void physicalCamera$projection(Camera camera, float delta, boolean world, CallbackInfoReturnable<Double> ci) {
        ci.setReturnValue(CameraRender1211.fov(ci.getReturnValue(),world));
    }

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"))
    private void physicalCamera$photoAspect(double fov, CallbackInfoReturnable<Matrix4f> ci) {
        CameraRender1211.photoAspect(ci.getReturnValue());
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void physicalCamera$poll(DeltaTracker delta, boolean tick, CallbackInfo ci) {
        CameraRender1211.beginFrame();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void physicalCamera$render(GameRenderer renderer, DeltaTracker delta, Operation<Void> draw) {
        draw.call(renderer,delta);
        CameraRender1211.drawMonitorHands(delta);
        CameraRender1211.nativePhoto(() -> draw.call(renderer,delta));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void physicalCamera$world(DeltaTracker delta, CallbackInfo ci) {
        CameraRender1211.afterVanillaWorld();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void physicalCamera$cleanView(Camera camera, float delta, Matrix4f pose, CallbackInfo ci) {
        if (CameraClient.capturingWorldFrame()
                || CameraClient.hasZ8View() && !CameraClient.showZ8Monitor()) ci.cancel();
    }
}
