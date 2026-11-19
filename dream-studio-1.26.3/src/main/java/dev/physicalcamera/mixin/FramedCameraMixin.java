package dev.physicalcamera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.GroundEquipmentPass;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrameRenderer.class)
abstract class FramedCameraMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
            at = @At("RETURN"))
    private void physicalCamera$markFrameEquipment(ItemFrame frame, ItemFrameRenderState state,
                                                    float partialTick, CallbackInfo callback) {
        ((GroundEquipmentPass.EquipmentState)state).physicalCamera$equipment(CameraClient.isCameraEquipment(frame.getItem()));
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void physicalCamera$drawFramedEquipment(ItemStackRenderState item, PoseStack pose,
                                                     SubmitNodeCollector collector, int light, int overlay,
                                                     int outline, Operation<Void> submit, ItemFrameRenderState frame) {
        if (((GroundEquipmentPass.EquipmentState)frame).physicalCamera$equipment()) {
            var deferred = GroundEquipmentPass.deferredItems();
            if (deferred != null) collector = deferred;
        }
        submit.call(item,pose,collector,light,overlay,outline);
    }
}
