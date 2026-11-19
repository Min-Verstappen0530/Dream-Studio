package dev.physicalcamera.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.CameraRender1211;
import dev.physicalcamera.Z8Presentation;
import dev.physicalcamera.Z8ZoomModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class Z8HandsMixin {
    @Shadow public abstract void renderItem(net.minecraft.world.entity.LivingEntity entity, ItemStack item,
            ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers, int light);

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void physicalCamera$excludeHands(float delta, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                             LocalPlayer player, int light, CallbackInfo ci) {
        if (!CameraClient.drawingDeferredHands() && CameraRender1211.deferMonitorHands()) ci.cancel();
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void physicalCamera$holdZ8(AbstractClientPlayer player, float delta, float pitch, InteractionHand hand,
                                      float swing, ItemStack stack, float equippedOffset, PoseStack pose,
                                      MultiBufferSource buffers, int light, CallbackInfo ci) {
        int focal = CameraClient.focalOf(stack);
        if (focal < 0 || player.isInvisible() || player.isScoping()) return;
        ci.cancel();
        if (CameraClient.capturingWorldFrame() || CameraClient.hasZ8View() && !CameraClient.showZ8Monitor()) return;
        if (hand == InteractionHand.OFF_HAND && CameraClient.focalOf(player.getMainHandItem()) >= 0) return;

        float lift = CameraClient.z8Lift()*Math.clamp(1-equippedOffset,0,1);
        pose.pushPose();
        if (CameraClient.showZ8Monitor()) {
            pose.setIdentity();
            pose.mulPose(new Matrix4f(RenderSystem.getModelViewMatrix()).invert());
        }
        pose.translate(Z8Presentation.x(lift),Z8Presentation.y(lift),0);
        pose.pushPose();
        pose.translate(0,0,Z8Presentation.DEPTH);
        pose.scale(Z8Presentation.SCALE,Z8Presentation.SCALE,Z8Presentation.SCALE);
        if (focal > 0) {
            Z8ZoomModel.submit(pose,buffers,light,focal,CameraClient.zoomFraction(focal));
        } else {
            renderItem(player,stack,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,false,pose,buffers,light);
        }
        pose.popPose();

        pose.translate(.380f,-.035f,-.492f);
        pose.mulPose(new Quaternionf().rotationTo(0,1,0,-.22f,.63f,-.74f));
        pose.scale(.78f,.78f,.78f);
        pose.translate(.375f,-.75f,0);
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        if (renderer instanceof PlayerRenderer arm) arm.renderRightHand(pose,buffers,light,player);
        pose.popPose();
    }
}
