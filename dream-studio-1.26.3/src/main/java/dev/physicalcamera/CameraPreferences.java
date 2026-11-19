package dev.physicalcamera;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.LoggerFactory;

final class CameraPreferences {
    enum RenderMode { VIEWPORT, NATIVE, IRIS }

    static final int MIN_SAMPLES = 16;
    static final int MAX_SAMPLES = 1024;
    private final Path file;
    private int apertureSamples = 24;
    private int previewThreadLimit;
    boolean allCores = true;
    boolean touchFocus = true;
    boolean extendedIso, starlightView;
    RenderMode renderMode = RenderMode.NATIVE;
    IrisCaptureProfile.Selection irisHistory = IrisCaptureProfile.Selection.AUTO;

    CameraPreferences(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
            touchFocus = Boolean.parseBoolean(values.getProperty("touchFocus", "true"));
            allCores = Boolean.parseBoolean(values.getProperty("allCores", "true"));
            extendedIso = Boolean.parseBoolean(values.getProperty("extendedIso","false"));
            starlightView = Boolean.parseBoolean(values.getProperty("starlightView","false"));
            setSamples(Integer.parseInt(values.getProperty("apertureSamples", "24")));
            setPreviewThreadLimit(Integer.parseInt(values.getProperty("previewThreads","0")));
            String savedMode = values.getProperty("renderMode",RenderMode.NATIVE.name());
            renderMode = savedMode.equals("PATH") ? RenderMode.NATIVE : RenderMode.valueOf(savedMode);
            irisHistory = IrisCaptureProfile.Selection.valueOf(values.getProperty("irisHistory","AUTO"));
        } catch (IOException | IllegalArgumentException error) {
            LoggerFactory.getLogger("physical_camera").warn("Could not read camera settings",error);
        }
    }

    int samples() { return apertureSamples; }
    void setSamples(int value) { apertureSamples = Math.clamp(value,MIN_SAMPLES,MAX_SAMPLES); }
    static int availableProcessors() { return Math.clamp(Runtime.getRuntime().availableProcessors(),1,32767); }
    int workers() { return allCores ? availableProcessors() : Math.max(1,availableProcessors()-1); }
    int previewThreadLimit() { return Math.min(previewThreadLimit,availableProcessors()); }
    void setPreviewThreadLimit(int limit) { previewThreadLimit = Math.clamp(limit,0,availableProcessors()); }
    int previewWorkers() {
        int limit = previewThreadLimit();
        return limit == 0 ? Math.max(1,availableProcessors()/4) : limit;
    }

    void save() throws IOException {
        Files.createDirectories(file.getParent());
        var values = new Properties();
        values.setProperty("apertureSamples",Integer.toString(samples()));
        values.setProperty("previewThreads",Integer.toString(previewThreadLimit()));
        values.setProperty("allCores",Boolean.toString(allCores));
        values.setProperty("touchFocus",Boolean.toString(touchFocus));
        values.setProperty("extendedIso",Boolean.toString(extendedIso));
        values.setProperty("starlightView",Boolean.toString(starlightView));
        values.setProperty("renderMode",renderMode.name());
        values.setProperty("irisHistory",irisHistory.name());
        try (var output = Files.newOutputStream(file)) {
            values.store(output,"Dream Studio client settings");
        }
    }
}
