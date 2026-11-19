package dev.physicalcamera.mixin;

import dev.physicalcamera.CameraClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class Z8HideHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void physicalCamera$hideHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (CameraClient.hasZ8View()) ci.cancel();
    }
}
