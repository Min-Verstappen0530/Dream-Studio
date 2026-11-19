package dev.physicalcamera;

import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Vector2fc;

final class IrisCompatibility {
    enum PackState { INACTIVE, ACTIVE, UNKNOWN }

    private static final Supplier<PackState> PACK_STATE = findPackState();

    static PackState packState() { return PACK_STATE.get(); }

    private static final Supplier<Z8Depth.Viewport> DEPTH_VIEWPORT = findDepthViewport();

    static Z8Depth.Viewport depthViewport() { return DEPTH_VIEWPORT.get(); }

    private static Supplier<Z8Depth.Viewport> findDepthViewport() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> Z8Depth.Viewport.FULL;
        try {
            var iris = Class.forName("net.irisshaders.iris.Iris");
            var manager = iris.getMethod("getPipelineManager").invoke(null);
            var pipeline = manager.getClass().getMethod("getPipelineNullable");
            var irisPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            var uniforms = irisPipeline.getMethod("getCustomUniforms");
            var variable = uniforms.getReturnType().getMethod("getVariable",String.class);
            var hasVariable = uniforms.getReturnType().getMethod("hasVariable",String.class);
            var context = Class.forName("kroppeb.stareval.function.FunctionContext");
            var resultType = Class.forName("kroppeb.stareval.function.FunctionReturn");
            var evaluate = variable.getReturnType().getMethod("evaluateTo",context,resultType);
            var result = resultType.getConstructor().newInstance();
            var value = resultType.getField("objectReturn");
            return () -> {
                try {
                    var active = pipeline.invoke(manager);
                    if (!irisPipeline.isInstance(active)) return Z8Depth.Viewport.FULL;
                    var values = uniforms.invoke(active);
                    // iterationRP renders depth into the lower-left FSR 2 viewport.
                    // Use the active uniforms so preset changes and resized targets follow the pack.
                    if (!Boolean.TRUE.equals(hasVariable.invoke(values,"fsrRenderScale"))
                            || !Boolean.TRUE.equals(hasVariable.invoke(values,"fsrScreenSize"))) return Z8Depth.Viewport.FULL;
                    var scaleUniform = variable.invoke(values,"fsrRenderScale");
                    evaluate.invoke(scaleUniform,values,result);
                    if (!(value.get(result) instanceof Vector2fc scale) ||
                            !(scale.x() > 0 && scale.x() <= 1 && scale.y() > 0 && scale.y() <= 1)) {
                        return Z8Depth.Viewport.FULL;
                    }
                    float x = scale.x(), y = scale.y();
                    if (Boolean.TRUE.equals(hasVariable.invoke(values,"taaJitter"))) {
                        evaluate.invoke(variable.invoke(values,"taaJitter"),values,result);
                        if (value.get(result) instanceof Vector2fc jitter && Math.abs(jitter.x()) < .01f && Math.abs(jitter.y()) < .01f) {
                            return new Z8Depth.Viewport(x,y,jitter.x()*.5f,jitter.y()*.5f);
                        }
                    }
                    return new Z8Depth.Viewport(x,y,0,0);
                } catch (ReflectiveOperationException | LinkageError error) {
                    return Z8Depth.Viewport.FULL;
                }
            };
        } catch (ReflectiveOperationException | LinkageError error) {
            return () -> Z8Depth.Viewport.FULL;
        }
    }

    static final BooleanSupplier SHADOW_PASS = findShadowPass();

    private static BooleanSupplier findShadowPass() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> true;
        try {
            var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var instance = api.getMethod("getInstance").invoke(null);
            var shadow = api.getMethod("isRenderingShadowPass");
            return () -> {
                try { return Boolean.TRUE.equals(shadow.invoke(instance)); }
                catch (ReflectiveOperationException | LinkageError error) { return true; }
            };
        } catch (ReflectiveOperationException | LinkageError error) {
            return () -> true;
        }
    }

    private static Supplier<PackState> findPackState() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> PackState.INACTIVE;
        try {
            var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var instance = api.getMethod("getInstance").invoke(null);
            var inUse = api.getMethod("isShaderPackInUse");
            return () -> {
                try {
                    return Boolean.TRUE.equals(inUse.invoke(instance)) ? PackState.ACTIVE : PackState.INACTIVE;
                } catch (ReflectiveOperationException | LinkageError error) {
                    return PackState.UNKNOWN;
                }
            };
        } catch (ReflectiveOperationException | LinkageError error) {
            return () -> PackState.UNKNOWN;
        }
    }
}
