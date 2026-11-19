package dev.physicalcamera;

import java.util.Locale;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

final class Z8Settings {
    enum Mode { P, S, A, M }
    enum Meter { MATRIX, CENTER, SPOT }

    private static final int[] DENOMINATORS = {32000,25600,20000,16000,12800,10000,8000,6400,5000,
            4000,3200,2500,2000,1600,1250,1000,800,640,500,400,320,250,200,160,125,100,80,60,
            50,40,30,25,20,15,13,10,8,6,5,4};
    static final double[] SHUTTERS = java.util.stream.DoubleStream.concat(
            Arrays.stream(DENOMINATORS).mapToDouble(i -> 1.0/i),
            java.util.stream.DoubleStream.of(.3,.4,.5,.6,.8,1,1.3,1.6,2,2.5,3,4,5,6,8,10,13,15,
                    20,25,30)).toArray();
    static final double[] APERTURES = {4,4.5,5,5.6,6.3,7.1,8,9,10,11,13,14,16,18,20,22};
    static final int[] ISOS = {64,80,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,
            2500,3200,4000,5000,6400,8000,10000,12800,16000,20000,25600,32000,40000,51200,102400};
    private static final int[] X2D_ISOS = {50,100,200,400,800,1600,3200,6400,12800,25600};
    private static final double[] XCD_SHUTTERS = Arrays.copyOfRange(SHUTTERS,Arrays.binarySearch(SHUTTERS,1.0/4000),SHUTTERS.length);
    private static final int STANDARD_ISO_COUNT = Arrays.binarySearch(ISOS,25600)+1;
    static final double[] DISTANCES = {.28, .35, .5, .7, 1, 1.5, 2, 3, 5, 10, 30, Double.POSITIVE_INFINITY};
    static final int[] FOCALS = Z24120Optics.ZOOM_MARKS;
    Mode mode = Mode.M;
    Meter meter = Meter.MATRIX;
    int shutter = Arrays.binarySearch(SHUTTERS,1.0/60), aperture, sensitivity = Arrays.binarySearch(ISOS,100);
    int manualDistance = Arrays.binarySearch(DISTANCES,2);
    double focal = 24;
    ZoomLens lens = ZoomLens.Z24_120;
    int compensationThirds;
    boolean autofocus = true, autoIso, focusLimit;
    private boolean extendedIso;
    boolean grid, horizon, histogram, playbackHistogram;
    Z8PhotoSize photoSize = Z8PhotoSize.HD;
    PhotoFormat photoFormat = PhotoFormat.BOTH;
    private double lockedDistance = 2;
    private boolean focusConfirmed;
    private double touchDistance = Double.NaN;
    private Exposure lastLensExposure, lastOpenMountExposure;

    record Exposure(double seconds, double aperture, int iso, double ev) {}

    int[] isoSteps() { return mediumFormat() ? X2D_ISOS : ISOS; }
    int iso() { return isoSteps()[sensitivity]; }
    int isoCount() { return mediumFormat() ? X2D_ISOS.length : extendedIso ? ISOS.length : STANDARD_ISO_COUNT; }
    double[] shutterSteps() { return mediumFormat() ? XCD_SHUTTERS : SHUTTERS; }
    void setIsoExtension(boolean enabled) {
        extendedIso = enabled;
        sensitivity = Math.min(sensitivity,isoCount()-1);
        lastLensExposure = lastOpenMountExposure = null;
    }
    Exposure lastExposure(boolean attached) { return attached ? lastLensExposure : lastOpenMountExposure; }
    void rememberExposure(boolean attached, Exposure exposure) {
        if (attached) lastLensExposure = exposure;
        else lastOpenMountExposure = exposure;
    }
    double focalLength() { return focal; }
    boolean usesAutofocus() { return autofocus && lens.supportsAutofocus(); }
    double projectionFocal() { return lens.projectionFocal(focal,distance()); }
    boolean mediumFormat() { return photoSize.sensor() == SensorFormat.X2D_II; }
    void setSensor(SensorFormat sensor) {
        if (photoSize.sensor() == sensor) return;
        int previousIso = iso();
        double previousShutter = shutterSteps()[shutter];
        photoSize = Z8PhotoSize.forSensor(sensor)[photoSize.sizeIndex()];
        var steps = isoSteps();
        int index = Arrays.binarySearch(steps,previousIso);
        if (index < 0) {
            int upper = Math.min(-index-1,steps.length-1);
            int lower = Math.max(0,upper-1);
            index = previousIso-steps[lower] <= steps[upper]-previousIso ? lower : upper;
        }
        sensitivity = Math.min(index,isoCount()-1);
        var shutters = shutterSteps();
        shutter = Arrays.binarySearch(shutters,closest(shutters,previousShutter));
        lastLensExposure = lastOpenMountExposure = null;
    }
    boolean usesFocusLimit() { return usesAutofocus() && lens.focusLimitMeters() > 0 && focusLimit; }
    double autofocusNear() { return usesFocusLimit() ? lens.focusLimitMeters() : lens.minFocus(focal); }
    int firstAperture() { return lens.firstAperture(focal); }
    int lastAperture() { return lens.lastAperture(focal); }
    int apertureIndex() { return Math.clamp(aperture,firstAperture(),lastAperture()); }
    int firstDistance() {
        int i = Arrays.binarySearch(DISTANCES,lens.minFocus(focal));
        return i < 0 ? Math.max(0,-i-2) : i;
    }
    void setLens(ZoomLens lens, double focal) {
        if (this.lens != lens) {
            int current = apertureIndex();
            double stop = this.lens.apertures()[current];
            aperture = current == firstAperture() ? lens.firstAperture(focal)
                    : Arrays.binarySearch(lens.apertures(),closest(lens.apertures(),stop));
            focusLimit = false;
        }
        this.lens = lens;
        this.focal = lens.snapFocal(focal);
        clearFocus();
        clearTouchDistance();
        lastLensExposure = null;
    }
    void changeFocal(double next) {
        next = lens.snapFocal(next);
        if (next == focal) return;
        if (lens.hasBuiltInConverter()) {
            aperture = apertureIndex()-firstAperture()+lens.firstAperture(next);
        }
        focal = next;
        clearFocus();
        lastLensExposure = null;
    }
    Z24120Optics.Exposure lensExposure(double fNumber) {
        return Z24120Optics.focus(lens,focal,distance(),fNumber);
    }
    double distance() {
        double selected = Double.isNaN(touchDistance) ? usesAutofocus() ? lockedDistance : DISTANCES[manualDistance] : touchDistance;
        return Math.max(lens.minFocus(focal),selected);
    }
    boolean confirmed() { return !Double.isNaN(touchDistance) || usesAutofocus() && focusConfirmed; }
    void clearFocus() { focusConfirmed = false; }
    void clearTouchDistance() { touchDistance = Double.NaN; }

    void touchFocus(double metres) {
        if (!lens.supportsAutofocus()) return;
        if (usesAutofocus()) focus(metres);
        else manualFocus(metres);
    }

    void manualFocus(double metres) { touchDistance = Math.max(metres,lens.minFocus(focal)); }

    void focus(double hitMeters) {
        if (!usesAutofocus()) return;
        clearTouchDistance();
        focusConfirmed = Double.isFinite(hitMeters);
        lockedDistance = focusConfirmed ? Math.max(hitMeters,autofocusNear()) : Double.POSITIVE_INFINITY;
    }

    Exposure exposure(double radiance) {
        return exposure(radiance,false);
    }

    Exposure exposure(double radiance, boolean openMount) {
        return exposure(ignored -> radiance,openMount);
    }

    Exposure exposure(DoubleUnaryOperator meter, boolean openMount) {
        double seconds = shutterSteps()[shutter];
        double f = openMount ? OpenMount.equivalentAperture(photoSize.sensor()) : lens.apertures()[apertureIndex()];
        double reference = exposureSignal(meter.applyAsDouble(f));
        int baseIso = autoIso ? isoSteps()[0] : iso();
        double aim = .18 * Math.pow(2, compensationThirds / 3.0);
        if (!openMount && (mode == Mode.P || mode == Mode.S)) {
            double[] apertures = Arrays.copyOfRange(lens.apertures(),firstAperture(),lastAperture()+1);
            double signal = reference*baseIso/RecordingMedia.BASE_ISO;
            double selected = mode == Mode.P ? (signal > 110 ? 8 : signal > 32 ? 5.6 : apertures[0])
                    : autoIso ? boundedStep(apertures,Math.sqrt(signal*seconds/aim),true)
                    : closest(apertures,Math.sqrt(signal*seconds/aim));
            if (selected != f) {
                f = selected;
                reference = exposureSignal(meter.applyAsDouble(f));
            }
            if (mode == Mode.S && autoIso) {
                int step = Arrays.binarySearch(apertures,f);
                while (step+1 < apertures.length
                        && reference*baseIso*seconds > aim*f*f*RecordingMedia.BASE_ISO) {
                    f = apertures[++step];
                    reference = exposureSignal(meter.applyAsDouble(f));
                }
            }
        }
        if (mode == Mode.A || mode == Mode.P) {
            seconds = meterShutter(aim*f*f*RecordingMedia.BASE_ISO/(reference*baseIso));
        }
        int effectiveIso = baseIso;
        if (autoIso) {
            double required = aim*f*f*RecordingMedia.BASE_ISO/(reference*seconds);
            int maximum = isoSteps()[isoCount()-1];
            if ((mode == Mode.A || mode == Mode.P) && required > maximum) {
                seconds = boundedStep(shutterSteps(),aim*f*f*RecordingMedia.BASE_ISO/(reference*maximum),true);
                required = aim*f*f*RecordingMedia.BASE_ISO/(reference*seconds);
            }
            effectiveIso = (int)Math.round(Math.clamp(required,baseIso,maximum));
        }
        double ev = Math.log(reference*effectiveIso*seconds/(RecordingMedia.BASE_ISO*f*f*.18))/Math.log(2);
        return new Exposure(seconds,f,effectiveIso,ev);
    }

    private double meterShutter(double seconds) {
        double handheld = Math.min(1.0/60,1/focal);
        return autoIso ? boundedStep(shutterSteps(),Math.min(seconds,handheld),false) : closest(shutterSteps(),seconds);
    }

    private static double exposureSignal(double radiance) {
        return Math.max(1e-12,radiance*32768*RecordingMedia.Z8_SCENE_BRIGHTNESS);
    }

    private static double boundedStep(double[] steps, double target, boolean roundUp) {
        int index = Arrays.binarySearch(steps,target);
        if (index < 0) index = -index-(roundUp ? 1 : 2);
        return steps[Math.clamp(index,0,steps.length-1)];
    }

    static String shutterLabel(double seconds) {
        if (seconds <= .25) {
            return "1/" + Math.round(1/seconds);
        }
        return seconds == (int)seconds ? (int)seconds+"s" : String.format(Locale.ROOT,"%.1fs",seconds);
    }

    static String apertureLabel(double aperture) {
        if (aperture == .95) return "F0.95";
        return aperture == (int) aperture ? "F" + (int) aperture
                : String.format(Locale.ROOT, "F%.1f", aperture);
    }

    private static double closest(double[] choices, double target) {
        double best = choices[0];
        for (double choice : choices) {
            if (Math.abs(Math.log(choice / target)) < Math.abs(Math.log(best / target))) {
                best = choice;
            }
        }
        return best;
    }
}
