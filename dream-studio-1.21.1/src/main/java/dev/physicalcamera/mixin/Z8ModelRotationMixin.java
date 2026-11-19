package dev.physicalcamera.mixin;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.block.model.BlockElement$Deserializer")
abstract class Z8ModelRotationMixin {
    @Inject(method = "getAngle", at = @At("HEAD"), cancellable = true)
    private void physicalCamera$allowAuthoredAngles(JsonObject rotation, CallbackInfoReturnable<Float> ci) {
        if (GsonHelper.getAsBoolean(rotation,"dream_studio_rotation",false)) {
            float angle = GsonHelper.getAsFloat(rotation,"angle");
            if (Float.isFinite(angle) && Math.abs(angle) <= 180) ci.setReturnValue(angle);
        }
    }
}
