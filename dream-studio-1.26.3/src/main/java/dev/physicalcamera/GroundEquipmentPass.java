package dev.physicalcamera;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

/** Draw dropped and framed camera equipment with vanilla diffuse shading against the existing world depth. */
public final class GroundEquipmentPass implements AutoCloseable {
    public interface EquipmentState {
        void physicalCamera$equipment(boolean equipment);
        boolean physicalCamera$equipment();
    }

    private static final ThreadLocal<GroundEquipmentPass> CURRENT = new ThreadLocal<>();
    private static boolean unavailable;
    private final GroundEquipmentPass previous;
    private SubmitNodeStorage nodes;
    private Matrix4f modelView;
    private GpuBufferSlice projection, worldFog;

    private GroundEquipmentPass(GroundEquipmentPass previous) { this.previous = previous; }

    public static GroundEquipmentPass begin() {
        if (unavailable || IrisCompatibility.packState() != IrisCompatibility.PackState.ACTIVE) return null;
        var pass = new GroundEquipmentPass(CURRENT.get());
        CURRENT.set(pass);
        return pass;
    }

    public static SubmitNodeStorage deferredItems() {
        var pass = CURRENT.get();
        if (pass == null || pass.projection == null || IrisCompatibility.SHADOW_PASS.getAsBoolean()) return null;
        if (pass.nodes == null) {
            pass.modelView = new Matrix4f(RenderSystem.getModelViewStack());
            pass.nodes = new SubmitNodeStorage();
        }
        return pass.nodes;
    }

    public static void worldProjection(GpuBufferSlice fog) {
        var pass = CURRENT.get();
        if (pass == null) return;
        pass.projection = RenderSystem.getProjectionMatrixBuffer();
        pass.worldFog = fog;
    }

    public static void draw(GameRenderer renderer, RenderTarget target) {
        var frame = CURRENT.get();
        if (frame == null || frame.nodes == null) return;
        var oldProjection = RenderSystem.getProjectionMatrixBuffer();
        var oldProjectionType = RenderSystem.getProjectionType();
        var oldFog = RenderSystem.getShaderFog();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().set(frame.modelView);
        try {
            RenderSystem.setProjectionMatrix(frame.projection,ProjectionType.PERSPECTIVE);
            RenderSystem.setShaderFog(frame.worldFog);
            renderer.lighting().setupFor(Lighting.Entry.LEVEL);
            try (var features = renderer.featureRenderDispatcher().prepareFrame(frame.nodes);
                 var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                         () -> "Dream Studio world equipment",target.getColorTextureView(),Optional.empty(),
                         target.getDepthTextureView(),OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                FeatureRenderDispatcher.renderAllFeatures(pass,features);
            }
        } catch (RuntimeException error) {
            unavailable = true;
            LoggerFactory.getLogger("physical_camera").warn("Could not draw camera equipment after Iris; using the normal entity renderer",error);
        } finally {
            RenderSystem.setProjectionMatrix(oldProjection,oldProjectionType);
            RenderSystem.setShaderFog(oldFog);
            modelView.popMatrix();
        }
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
