package dev.physicalcamera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.RemixItemCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemRenderer.class)
abstract class RemixItemRendererMixin {
    @WrapMethod(method = "render")
    private void physicalCamera$renderEquipment(ItemStack stack, ItemDisplayContext display, boolean leftHand,
                                               PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                                               BakedModel model, Operation<Void> original) {
        boolean previous = RemixItemCapture.begin(CameraClient.isCameraEquipment(stack));
        try {
            original.call(stack,display,leftHand,pose,buffers,light,overlay,model);
        } finally {
            RemixItemCapture.end(previous);
        }
    }
}
