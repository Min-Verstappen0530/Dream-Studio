package dev.physicalcamera;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3fc;

final class WorldScene implements Capture.Scene {
    private static final double PHOTON_SCALE = 8e7;
    private static final int[] CHANNEL_SHIFT = {16, 8, 0};
    private final ClientLevel level;
    private final Vec3 position;
    private final Vec3 forward;
    private final Vec3 right;
    private final Vec3 up;

    WorldScene(ClientLevel level, Camera camera) {
        this.level = level;
        position = camera.getPosition();
        forward = minecraftVector(camera.getLookVector());
        right = minecraftVector(camera.getLeftVector()).scale(-1);
        up = minecraftVector(camera.getUpVector());
    }

    @Override
    public double radiance(Optics.Ray ray, int band) {
        long sample = sample(ray);
        return PHOTON_SCALE * linearChannel((int)sample, band) * (sample >>> 24) / 65535;
    }

    private long sample(Optics.Ray ray) {
        Vec3 direction = toWorld(ray.direction());
        Vec3 origin = position.add(toWorld(ray.origin()).scale(0.001));
        var hit = level.clip(new ClipContext(origin, origin.add(direction.scale(64)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) {
            return level.dimensionType().hasSkyLight() ? pack(0x90BFFF, (15 - level.getSkyDarken()) / 15.0) : 0;
        }
        var pos = hit.getBlockPos();
        var state = level.getBlockState(pos);
        int color = state.getMapColor(level, pos).col;
        if (color == 0) {
            color = 0xB0B0B0;
        }
        int light = Math.max(level.getMaxLocalRawBrightness(pos.relative(hit.getDirection())),
                state.getLightEmission());
        double illumination = Math.pow(Math.clamp(light / 15.0, 0, 1), 2);
        double face = switch (hit.getDirection()) {
            case UP -> 1;
            case DOWN -> 0.5;
            case NORTH, SOUTH -> 0.8;
            case EAST, WEST -> 0.65;
        };
        return pack(color, illumination * face);
    }

    private static long pack(int rgb, double illumination) {
        return (Math.round(Math.clamp(illumination,0,1)*65535) << 24) | (rgb & 0xffffffL);
    }

    private Vec3 toWorld(Optics.Vector vector) {
        return right.scale(vector.x()).add(up.scale(vector.y())).add(forward.scale(vector.z()));
    }

    private static Vec3 minecraftVector(Vector3fc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static double linearChannel(int color, int band) {
        double encoded = ((color >> CHANNEL_SHIFT[band]) & 255) / 255.0;
        return encoded <= 0.04045 ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }
}
