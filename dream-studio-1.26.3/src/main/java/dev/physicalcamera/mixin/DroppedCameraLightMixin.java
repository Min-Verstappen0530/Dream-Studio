package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import dev.physicalcamera.GroundEquipmentPass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
abstract class DroppedCameraLightMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void physicalCamera$keepDarkMaterialsReadable(ItemEntity entity, ItemEntityRenderState state,
                                                          float partialTick, CallbackInfo callback) {
        boolean equipment = CameraClient.isCameraEquipment(entity.getItem());
        ((GroundEquipmentPass.EquipmentState)state).physicalCamera$equipment(equipment);
        if (equipment && LightCoordsUtil.block(state.lightCoords) < 8) {
            state.lightCoords = LightCoordsUtil.withBlock(state.lightCoords,8);
        }
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"),
            require = 0)
    private void physicalCamera$submitDroppedCamera(PoseStack pose, SubmitNodeCollector collector, int light,
                                                   ItemClusterRenderState state, RandomSource random, AABB bounds,
                                                   Operation<Void> submit) {
        if (state instanceof GroundEquipmentPass.EquipmentState drop && drop.physicalCamera$equipment()) {
            var deferred = GroundEquipmentPass.deferredItems();
            if (deferred != null) collector = deferred;
        }
        submit.call(pose,collector,light,state,random,bounds);
    }
}
