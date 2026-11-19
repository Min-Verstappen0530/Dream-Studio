package dev.physicalcamera.mixin;

import dev.physicalcamera.GroundEquipmentPass;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({ItemEntityRenderState.class, ItemFrameRenderState.class})
abstract class DroppedCameraStateMixin implements GroundEquipmentPass.EquipmentState {
    @Unique private boolean physicalCamera$equipment;

    @Override public void physicalCamera$equipment(boolean equipment) { physicalCamera$equipment = equipment; }
    @Override public boolean physicalCamera$equipment() { return physicalCamera$equipment; }
}
