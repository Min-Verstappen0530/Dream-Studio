package dev.physicalcamera;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.Arrays;

/** Estimates scene radiance relative to the EV100 15 daylight reference. */
record SceneRadiometry(double scale) {
    private static final int COLUMNS = 28, ROWS = 16;
    static final SceneRadiometry UNCALIBRATED = new SceneRadiometry(1);

    static SceneRadiometry measure(ClientLevel level, Camera camera, double halfTan, double aspect) {
        return measure(level,camera,halfTan,aspect,COLUMNS,ROWS);
    }

    static SceneRadiometry measurePreview(ClientLevel level, Camera camera, double halfTan, double aspect) {
        return measure(level,camera,halfTan,aspect,12,8);
    }

    private static SceneRadiometry measure(ClientLevel level, Camera camera, double halfTan, double aspect,
                                           int columns, int rows) {
        double sky = skyScale(level.getOverworldClockTime(),level.getRainLevel(1),level.getThunderLevel(1));
        boolean skylight = level.dimensionType().hasSkyLight();
        double ambient = skylight ? sky : 1.0/2048;
        var position = camera.position();
        var forward = new Vec3(camera.forwardVector());
        var left = new Vec3(camera.leftVector());
        var up = new Vec3(camera.upVector());
        var values = new float[columns*rows];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                var direction = forward.add(left.scale((1-2*(x+.5)/columns)*halfTan*aspect))
                        .add(up.scale((1-2*(y+.5)/rows)*halfTan)).normalize();
                var hit = level.clip(new ClipContext(position,position.add(direction.scale(96)),
                        ClipContext.Block.OUTLINE,ClipContext.Fluid.ANY,CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.MISS) {
                    values[y*columns+x] = (float)ambient;
                    continue;
                }
                var block = hit.getBlockPos();
                var face = block.relative(hit.getDirection());
                int skyLight = skylight ? level.getBrightness(LightLayer.SKY,face) : 15;
                int blockLight = level.getBrightness(LightLayer.BLOCK,face);
                double daylight = skyLight == 0 ? 0 : ambient*Math.pow(2,(skyLight-15)*.8);
                double artificial = blockLight == 0 ? 0 : Math.pow(2,-5+(blockLight-15)*.7);
                values[y*columns+x] = (float)(daylight+artificial);
            }
        }
        // The rendered RGB already includes local light falloff, shadows and reflections.
        // Calibrate its exposure from representative bright surfaces without painting a
        // second illumination map over the image or treating an emitter as a surface.
        Arrays.sort(values);
        return new SceneRadiometry(values[(values.length-1)*9/10]);
    }

    static double skyScale(long ticks, double rain, double thunder) {
        double sun = Math.cos(2*Math.PI*(Math.floorMod(ticks,24000)-6000)/24000);
        double clear = sun > 0 ? Math.max(1.0/128,Math.pow(sun,.65))
                : Math.scalb(1,-18) + Math.exp(35*sun)/128;
        return clear*Math.pow(2,-1.5*rain-thunder);
    }

}
