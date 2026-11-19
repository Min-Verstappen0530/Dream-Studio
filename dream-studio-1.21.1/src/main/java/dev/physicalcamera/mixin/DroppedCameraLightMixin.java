package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemEntityRenderer.class)
abstract class DroppedCameraLightMixin {
    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true)
    private int physicalCamera$readableMaterial(int light, ItemEntity entity) {
        return CameraClient.isCameraEquipment(entity.getItem())
                ? LightTexture.pack(Math.max(8,LightTexture.block(light)),LightTexture.sky(light)) : light;
    }
}
