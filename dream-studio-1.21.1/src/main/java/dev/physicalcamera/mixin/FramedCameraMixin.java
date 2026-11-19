package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemFrameRenderer.class)
abstract class FramedCameraMixin {
    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), argsOnly = true)
    private int physicalCamera$readableMaterial(int light, ItemFrame frame) {
        return CameraClient.isCameraEquipment(frame.getItem())
                ? LightTexture.pack(Math.max(8,LightTexture.block(light)),LightTexture.sky(light)) : light;
    }
}
