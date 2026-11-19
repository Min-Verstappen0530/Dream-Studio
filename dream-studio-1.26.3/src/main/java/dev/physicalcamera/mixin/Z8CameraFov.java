package dev.physicalcamera.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface Z8CameraFov {
    @Accessor("hudFov")
    float physicalCamera$hudFov();
}
