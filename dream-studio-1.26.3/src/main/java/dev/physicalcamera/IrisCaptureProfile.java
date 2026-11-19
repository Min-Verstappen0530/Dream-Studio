package dev.physicalcamera;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import java.util.function.Function;
import java.util.function.Predicate;

record IrisCaptureProfile(String name, String label, int frames, PriorityShader shader) {
    static final IrisCaptureProfile SINGLE = basic("SINGLE","screen.physical_camera.iris.single",0);
    private static final IrisCaptureProfile TEMPORAL_AA = basic("TEMPORAL_AA","screen.physical_camera.iris.taa",16);
    private static final IrisCaptureProfile VOLUMETRIC = basic("VOLUMETRIC","screen.physical_camera.iris.clouds",24);
    private static final IrisCaptureProfile VOXEL = basic("VOXEL","screen.physical_camera.iris.voxel",32);
    private static final IrisCaptureProfile RAY = basic("RAY","screen.physical_camera.iris.ray",32);

    private static IrisCaptureProfile basic(String name, String label, int frames) {
        return new IrisCaptureProfile(name,label,frames,null);
    }

    enum Selection {
        AUTO(-1), SINGLE(0), FRAMES_16(16), FRAMES_32(32), FRAMES_64(64);

        private final int frames;
        Selection(int frames) { this.frames = frames; }
        int frames(IrisCaptureProfile profile) { return this == AUTO ? profile.frames : frames; }
        Selection next() { return values()[(ordinal()+1)%values().length]; }

        Component label(IrisCaptureProfile profile) {
            var framesLabel = framesLabel(frames(profile));
            if (this != AUTO) return framesLabel;
            return Component.translatable("screen.physical_camera.iris.auto",
                    Component.translatable(profile.label), framesLabel);
        }

        private static Component framesLabel(int frames) {
            return frames == 0 ? Component.translatable("screen.physical_camera.iris.single")
                    : Component.translatable("screen.physical_camera.iris.frames",frames);
        }
    }

    static IrisCaptureProfile detect(String packName, boolean optionsAvailable,
                                     Predicate<String> enabled, Function<String,String> value) {
        var priority = PriorityShader.fromPackName(packName);
        if (priority != null && optionsAvailable) {
            return new IrisCaptureProfile(priority.name(),"screen.physical_camera.iris.priority",priority.frames(enabled,value),priority);
        }
        String name = packName.toLowerCase(Locale.ROOT);
        if (name.equals("ray") || name.equals("ray.zip") || name.startsWith("ray-")) return RAY;
        if (name.startsWith("rethinking-voxels") || name.startsWith("shrimple")
                || name.startsWith("seus ptgi") || name.startsWith("iterationrp")
                || enabled.test("PT_VOXEL")) return VOXEL;
        boolean taa = enabled.test("TAA") || enabled.test("ENABLE_TAA") || enabled.test("TEMPORAL_AA")
                || "taa".equalsIgnoreCase(value.apply("ANTIALIASING"));
        boolean clouds = enabled.test("VOLUMETRIC_CLOUDS") || enabled.test("VOLUMETRIC_CLOUDS_ENABLED")
                || enabled.test("CLOUDS_3D") || name.startsWith("bsl") && "2".equals(value.apply("CLOUDS"));
        boolean temporalClouds = enabled.test("CLOUD_TEMPORAL") || enabled.test("TEMPORAL_CLOUDS");
        if (temporalClouds && !"0".equals(value.apply("CLOUDS")) || taa && clouds) return VOLUMETRIC;
        return taa ? TEMPORAL_AA : SINGLE;
    }
}
