package dev.physicalcamera;

import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Vector2fc;

final class IrisCompatibility {
    enum PackState { INACTIVE, ACTIVE, UNKNOWN }

    private static final Supplier<PackState> PACK_STATE = findPackState();

    static PackState packState() { return PACK_STATE.get(); }

    private static final Supplier<String> PACK_NAME = findPackName();
    private static final Supplier<PackOptions> PACK_OPTIONS = findPackOptions();
    private static final IntSupplier FRAME_COUNTER = findFrameCounter();

    static IrisCaptureProfile captureProfile() {
        if (packState() != PackState.ACTIVE) return IrisCaptureProfile.SINGLE;
        var options = PACK_OPTIONS.get();
        return IrisCaptureProfile.detect(PACK_NAME.get(),options != PackOptions.EMPTY,options::enabled,options::value);
    }

    private record PackOptions(Object values, Map<?,?> booleans, Map<?,?> strings, Method bool, Method string) {
        static final PackOptions EMPTY = new PackOptions(null,Map.of(),Map.of(),null,null);

        boolean enabled(String name) {
            if (!booleans.containsKey(name)) return false;
            try { return Boolean.TRUE.equals(bool.invoke(values,name)); }
            catch (ReflectiveOperationException | LinkageError error) { return false; }
        }

        String value(String name) {
            if (!strings.containsKey(name)) return "";
            try { return string.invoke(values,name) instanceof String value ? value : ""; }
            catch (ReflectiveOperationException | LinkageError error) { return ""; }
        }
    }

    private static Supplier<PackOptions> findPackOptions() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> PackOptions.EMPTY;
        try {
            var iris = Class.forName("net.irisshaders.iris.Iris");
            var currentPack = iris.getMethod("getCurrentPack");
            var pack = Class.forName("net.irisshaders.iris.shaderpack.ShaderPack");
            var settings = pack.getMethod("getShaderPackOptions");
            var options = settings.getReturnType().getMethod("getOptionValues");
            var type = options.getReturnType();
            var getSet = type.getMethod("getOptionSet");
            var booleans = getSet.getReturnType().getMethod("getBooleanOptions");
            var strings = getSet.getReturnType().getMethod("getStringOptions");
            var bool = type.getMethod("getBooleanValueOrDefault",String.class);
            var string = type.getMethod("getStringValueOrDefault",String.class);
            return new Supplier<>() {
                private Object previous;
                private PackOptions cached = PackOptions.EMPTY;

                @Override public PackOptions get() {
                    try {
                        if (!(currentPack.invoke(null) instanceof Optional<?> current)) return PackOptions.EMPTY;
                        Object active = current.orElse(null);
                        if (active != previous) {
                            previous = active;
                            cached = PackOptions.EMPTY;
                            if (active != null) {
                                var values = options.invoke(settings.invoke(active));
                                var set = getSet.invoke(values);
                                cached = new PackOptions(values,(Map<?,?>)booleans.invoke(set),
                                        (Map<?,?>)strings.invoke(set),bool,string);
                            }
                        }
                        return cached;
                    } catch (ReflectiveOperationException | LinkageError error) {
                        return PackOptions.EMPTY;
                    }
                }
            };
        } catch (ReflectiveOperationException | LinkageError error) {
            return () -> PackOptions.EMPTY;
        }
    }

    private static IntSupplier findFrameCounter() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> -1;
        try {
            var time = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            if (time.getField("COUNTER").get(null) instanceof IntSupplier counter) return counter;
        } catch (ReflectiveOperationException | LinkageError error) {
            // A different Iris version can still use the full-size depth viewport.
        }
        return () -> -1;
    }

    private static float scale(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value);
            return parsed > 0 && parsed <= 1 ? parsed : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static float halton(int index, int base) {
        float fraction = 1, sum = 0;
        for (; index > 0; index /= base) {
            fraction /= base;
            sum += fraction*(index%base);
        }
        return sum;
    }

    private static Supplier<String> findPackName() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> "";
        try {
            var name = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName");
            return () -> {
                try {
                    return name.invoke(null) instanceof String active ? active : "";
                } catch (ReflectiveOperationException | LinkageError error) {
                    return "";
                }
            };
        } catch (ReflectiveOperationException | LinkageError error) {
            return () -> "";
        }
    }

    private static final Supplier<Z8Depth.Viewport> DEPTH_VIEWPORT = findDepthViewport();

    static Z8Depth.Viewport depthViewport(int width, int height) {
        var generic = DEPTH_VIEWPORT.get();
        if (generic.remapped()) return generic;
        var priority = PriorityShader.fromPackName(PACK_NAME.get());
        if (priority == null) return generic;
        var options = PACK_OPTIONS.get();
        if (!options.enabled("TAA")) return generic;
        if (priority == PriorityShader.BLISS && options.enabled("TAA_UPSCALING")) {
            float scale = scale(options.value("SCALE_FACTOR"),.75f);
            return new Z8Depth.Viewport(scale,scale,0,0);
        }
        if (priority == PriorityShader.PHOTON) {
            boolean upscale = options.enabled("TAAU");
            float scale = upscale ? scale(options.value("TAAU_RENDER_SCALE"),.75f) : 1;
            int frame = FRAME_COUNTER.getAsInt();
            if (frame < 0) return new Z8Depth.Viewport(scale,scale,0,0);
            float amplitude = upscale ? .5f : .33f;
            return new Z8Depth.Viewport(scale,scale,
                    amplitude*centredFraction(1.3247179572,frame)/width,
                    amplitude*centredFraction(1.7548776662,frame)/width);
        }
        if (priority == PriorityShader.RAY) {
            int frame = FRAME_COUNTER.getAsInt();
            if (frame >= 0) {
                int sample = (frame%16)+1;
                return new Z8Depth.Viewport(1,1,(halton(sample,2)-.5f)/width,(halton(sample,3)-.5f)/height);
            }
        }
        return generic;
    }

    private static float centredFraction(double step, int frame) {
        float phase = (float)step*frame+.5f;
        return (float)((phase-Math.floor(phase))*2-1);
    }

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
