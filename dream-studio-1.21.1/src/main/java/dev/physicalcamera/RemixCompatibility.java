package dev.physicalcamera;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;

final class RemixCompatibility {
    private static final Api REMIX = findApi();

    private RemixCompatibility() {}

    static boolean active() {
        if (REMIX == null) return false;
        try {
            Object renderer = REMIX.renderer().invoke();
            return renderer != null && (boolean)REMIX.initialized().invoke(renderer)
                    && (boolean)REMIX.enabled().invoke();
        } catch (Throwable error) {
            throw new IllegalStateException("Could not query RTX Remix state",error);
        }
    }

    static int depthTexture() {
        try {
            return (int)REMIX.depth().invoke(REMIX.renderer().invoke());
        } catch (Throwable error) {
            throw new IllegalStateException("Could not access the RTX Remix depth texture",error);
        }
    }

    private static Api findApi() {
        if (!FabricLoader.getInstance().isModLoaded("rtx-remix-renderer")) return null;
        try {
            var lookup = MethodHandles.publicLookup();
            var mod = Class.forName("com.nvidia.rtxremix.minecraft.RtxRemixMod");
            var renderer = Class.forName("com.nvidia.rtxremix.minecraft.RemixRenderer");
            return new Api(lookup.findStaticGetter(mod,"renderer",renderer),
                    lookup.findVirtual(renderer,"isInitialized",MethodType.methodType(boolean.class)),
                    lookup.findStatic(mod,"isRtxEnabled",MethodType.methodType(boolean.class)),
                    lookup.findVirtual(renderer,"getInteropDepthTexture",MethodType.methodType(int.class)));
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("RTX Remix 0.1.1 renderer entry points are unavailable",error);
        }
    }

    private record Api(MethodHandle renderer, MethodHandle initialized, MethodHandle enabled, MethodHandle depth) {}
}
