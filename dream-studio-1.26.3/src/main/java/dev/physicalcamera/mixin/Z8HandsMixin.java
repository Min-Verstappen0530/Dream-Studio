package dev.physicalcamera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.Z8Presentation;
import dev.physicalcamera.Z8ZoomModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class Z8HandsMixin {
    @Shadow private void renderPlayerHand(PoseStack pose, SubmitNodeCollector collector, int light,
                                          HumanoidArm arm, PlayerRenderState player) {}

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void physicalCamera$holdZ8(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                       float partial, float pitch, InteractionHand hand, float swing, ItemStack stack,
                                       float equippedOffset, PoseStack pose, SubmitNodeCollector collector, int light,
                                       CallbackInfo callback) {
        var avatar = player.avatarRenderState;
        int focal = CameraClient.focalOf(stack);
        if (focal < 0 || avatar == null || avatar.isInvisible || hands.isScoping) return;
        callback.cancel();
        boolean main = hand == InteractionHand.MAIN_HAND;
        if (!main && CameraClient.focalOf(hands.mainHandItem) >= 0) return;
        float height = main ? hands.mainHandHeight : hands.offHandHeight;
        float previous = main ? hands.oldMainHandHeight : hands.oldOffHandHeight;
        float equip = Math.clamp(previous + partial*(height-previous),0,1);
        float lift = CameraClient.z8Lift()*equip;

        pose.pushPose();
        if (CameraClient.showZ8Monitor()) {
            // Keep the glass and its mouse targets aligned despite view bob and hurt roll.
            pose.setIdentity();
            var view = Minecraft.getInstance().gameRenderer.mainCamera().getViewRotationMatrix(new Matrix4f());
            pose.mulPose(view.invert());
        }
        pose.translate(Z8Presentation.x(lift),Z8Presentation.y(lift),0);
        pose.pushPose();
        pose.translate(0,0,Z8Presentation.DEPTH);
        pose.scale(Z8Presentation.SCALE,Z8Presentation.SCALE,Z8Presentation.SCALE);
        if (focal > 0 && Minecraft.getInstance().player != null) {
            Z8ZoomModel.submit(pose,collector,avatar.lightCoords,focal,CameraClient.zoomFraction(focal));
        } else {
            var item = main ? hands.mainHandRenderState : hands.offHandRenderState;
            item.submit(pose,collector,avatar.lightCoords,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,0);
        }
        pose.popPose();

        // Put the end of the vanilla arm next to the grip, with its shoulder below the viewport.
        pose.translate(.380f,-.035f,-.492f);
        pose.mulPose(new Matrix4f().rotation(new Quaternionf().rotationTo(0,1,0,-.22f,.63f,-.74f)));
        pose.scale(.78f,.78f,.78f);
        pose.translate(.375f,-.75f,0);
        renderPlayerHand(pose,collector,avatar.lightCoords,HumanoidArm.RIGHT,player);
        pose.popPose();
    }
}
