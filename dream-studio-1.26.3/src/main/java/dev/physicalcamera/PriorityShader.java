package dev.physicalcamera;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** The shader revisions verified from the local compatibility set. */
enum PriorityShader {
    ASTRALEX("astralex_by_lexboost_v93.0"),
    BLISS("bliss_v2.1.2_(chocapic13_shaders_edit)"),
    BSL("bsl_v10.1.8"),
    COMPLEMENTARY("complementaryreimagined_r5.10","complementaryunbound_r5.10"),
    DERIVATIVE("derivative 26.3"),
    MAKEUP("makeup-ultrafast-9.6"),
    PHOTON("photon_v1.4"),
    RAY("ray-0.1.0a0-5aa85e9-nightly"),
    SILDUR("sildur's vibrant shaders v2.02 extreme"),
    SOLAS("solas shader v3.9");

    private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9a-fk-or]",Pattern.CASE_INSENSITIVE);
    private final String[] files;

    PriorityShader(String... files) { this.files = files; }

    static PriorityShader fromPackName(String file) {
        String name = FORMAT_CODE.matcher(file).replaceAll("").strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) name = name.substring(0,name.length()-4);
        for (var pack : values()) {
            for (String known : pack.files) if (known.equals(name)) return pack;
        }
        return null;
    }

    int frames(Predicate<String> enabled, Function<String,String> value) {
        boolean taa = enabled.test("TAA");
        boolean volume = enabled.test("VOLUMETRIC_CLOUDS");
        return switch (this) {
            case ASTRALEX -> choice(value,"AA",0,0,5) >= 2
                    ? choice(value,"AA",0,0,5) >= 4 ? 24 : 16 : 0;
            case BLISS -> taa ? enabled.test("TAA_UPSCALING") ? 24 : volume ? 16 : 8 : 0;
            case BSL -> taa ? "2".equals(value.apply("CLOUDS")) ? 16
                    : "1".equals(value.apply("TAA_MODE")) ? 6 : 8 : 0;
            case COMPLEMENTARY -> taa ? complementaryClouds(value) ? 16 : 8 : 0;
            case DERIVATIVE -> volume ? cloudCycle(value,"TEMPORAL_UPSCALING",2)
                    : enabled.test("TAA_ENABLED") ? 16 : 0;
            case MAKEUP -> choice(value,"AA_TYPE",2,0,3) > 0 ? 8 : 0;
            case PHOTON -> photonClouds(enabled) ? cloudCycle(value,"CLOUDS_TEMPORAL_UPSCALING",4)
                    : enabled.test("TAAU") ? 24 : taa ? 12 : 0;
            case RAY -> !"0".equals(value.apply("GI_DENOISE")) || !"0".equals(value.apply("LIGHT_DENOISE"))
                    ? 32 : enabled.test("CLOUD_TEMPORAL") && choice(value,"CLOUDS",3,0,3) >= 2
                    ? cloudCycle(value,"CLOUD_RES",2) : taa ? 16 : 0;
            case SILDUR -> taa ? 8 : 0;
            case SOLAS -> taa ? volume ? 24 : 12 : 0;
        };
    }

    private static boolean complementaryClouds(Function<String,String> value) {
        String style = value.apply("CLOUD_STYLE_DEFINE");
        return !"0".equals(style) && !"50".equals(style) && !"0".equals(value.apply("CLOUD_QUALITY"));
    }

    private static boolean photonClouds(Predicate<String> enabled) {
        return enabled.test("CLOUDS_CUMULUS") || enabled.test("CLOUDS_CUMULUS_CONGESTUS")
                || enabled.test("CLOUDS_ALTOCUMULUS");
    }

    private static int cloudCycle(Function<String,String> value, String option, int fallback) {
        int grid = choice(value,option,fallback,1,4);
        return Math.max(16,2*grid*grid);
    }

    private static int choice(Function<String,String> value, String option, int fallback, int min, int max) {
        try { return Math.clamp(Integer.parseInt(value.apply(option)),min,max); }
        catch (NumberFormatException error) { return fallback; }
    }
}
