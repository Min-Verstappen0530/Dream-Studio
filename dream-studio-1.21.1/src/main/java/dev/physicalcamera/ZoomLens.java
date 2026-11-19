package dev.physicalcamera;

enum ZoomLens {
    Z24_120(24,120), Z100_400(100,400), Z58_NOCT(58,58), Z600_TC(600,840), Z14_24(14,24), XCD35_100(35,100);

    private static final double[] LONG_APERTURES = {4.5,4.8,5,5.3,5.6,6.3,7.1,8,9,10,11,13,14,16,18,20,22,25,29,32,36,40};
    private static final double[] NOCT_APERTURES = {.95,1.1,1.2,1.4,1.6,1.8,2,2.2,2.5,2.8,3.2,3.5,
            4,4.5,5,5.6,6.3,7.1,8,9,10,11,13,14,16};
    private static final double[] NOCT_VIGNETTE_STOPS = {.95,1.4,2,2.8,4,16};
    private static final double[] NOCT_CORNER_EV = {2.0,1.15,.48,.28,.22,.22};
    private static final double[] SUPERTELE_APERTURES = {4,4.5,5,5.6,6.3,7.1,8,9,10,11,13,14,16,18,20,22,25,29,32,36,40,45};
    private static final double[] ULTRAWIDE_APERTURES = {2.8,3.2,3.5,4,4.5,5,5.6,6.3,7.1,8,9,10,11,13,14,16,18,20,22};
    private static final double[] XCD_APERTURES = {2.8,3.2,3.5,4,4.5,5,5.6,6.3,7.1,8,9,10,11,13,14,16,18,20,22,25,29,32};
    private static final double[] XCD_FIELD_FOCALS = {35,45,60,100};
    private static final double[] XCD_DISTORTION = {-.032,-.011,0,.009};
    // Reduced-order fit: Kasson's uncorrected wide shot has the strongest falloff.
    private static final double[] XCD_OPEN_CORNER_EV = {1.62,1.12,.72,.81};
    private static final double[] XCD_CLOSED_CORNER_EV = {.66,.48,.30,.35};
    private static final double[] XCD_PUPIL_CLIP = {.30,.22,.15,.17};
    private static final double[] XCD_CA_FOCALS = {35,45,60,75,100};
    private static final double[] XCD_CA_MM = {.005,.005,.004,.0015,.0045};
    final int wide, tele;
    ZoomLens(int wide, int tele) { this.wide = wide; this.tele = tele; }

    static ZoomLens fromMountedFocal(int focal) {
        return switch (focal) {
            case 14 -> Z14_24;
            case 35 -> XCD35_100;
            case 58 -> Z58_NOCT;
            case 100 -> Z100_400;
            case 600 -> Z600_TC;
            default -> Z24_120;
        };
    }

    boolean zoomable() { return tele > wide; }
    boolean supportsAutofocus() { return this != Z58_NOCT; }
    int focusLimitMeters() { return this == Z600_TC ? 10 : this == Z100_400 ? 3 : 0; }
    boolean hasBuiltInConverter() { return this == Z600_TC; }
    boolean converterEngaged(double focal) { return hasBuiltInConverter() && focal >= (wide+tele)/2.0; }
    int apertureBlades() { return this == Z58_NOCT ? 11 : 9; }
    double transmission() { return this == Z58_NOCT ? Math.pow(.95/1.1,2) : .84; }
    double opticalFocal(double indicatedFocal) {
        // The barrel's zoom scale is nominal. The XCD data sheet measures 36–97 mm.
        return this == XCD35_100 ? Math.fma(61,fraction(indicatedFocal),36) : indicatedFocal;
    }

    double projectionFocal(double focal, double metres) {
        // Fit an inverse-distance trend to CameraLabs' framing measurements.
        double breathing = switch (this) {
            case Z24_120 -> -.03*1.28*fraction(focal)/Math.max(.7,metres);
            case Z100_400 -> .002*2.09/Math.max(1.3,metres);
            case Z58_NOCT -> .16*.79/Math.max(.5,metres);
            case Z600_TC -> .16*6.1/Math.max(4.3,metres);
            case Z14_24 -> 0;
            // Estimate near-limit breathing from the datasheet's 1:8 and
            // 1:4 reproduction ratios and 0.40/0.50 m subject distances.
            case XCD35_100 -> (.10-.275*fraction(focal))*minFocus(focal)/Math.max(minFocus(focal),metres);
        };
        return opticalFocal(focal)*(1+breathing);
    }

    double pupilClipping(double focal, double fNumber) {
        // Approximate the shape trends of the measured FX-corner blur discs.
        double corner = switch (this) {
            case Z24_120 -> .40+.10*fraction(focal);
            case Z100_400 -> .22+.24*fraction(focal);
            case Z58_NOCT -> .58;
            case Z600_TC -> converterEngaged(focal) ? .39 : .48;
            case Z14_24 -> .29-.08*fraction(focal);
            case XCD35_100 -> interpolate(focal,XCD_FIELD_FOCALS,XCD_PUPIL_CLIP);
        };
        double opening = wideOpen(focal)/fNumber;
        double falloff = this == Z24_120 ? opening : opening*opening;
        return corner*falloff;
    }

    double lateralColor(double focal, double opening) {
        if (this == Z58_NOCT) return .009/21.6*(.45+.55*opening);
        // Approximate the weak zoom-dependent fringing in the DCW test.
        if (this == XCD35_100) return interpolate(focal,XCD_CA_FOCALS,XCD_CA_MM)/27.4;
        return 0;
    }

    double fraction(double focal) { return zoomable() ? Math.clamp((focal-wide)/(tele-wide),0,1) : 0; }
    private static final int[] LONG_ZOOM_STOPS = java.util.stream.IntStream.concat(
            java.util.stream.IntStream.rangeClosed(100,200),
            java.util.stream.IntStream.concat(java.util.stream.IntStream.rangeClosed(41,60).map(i -> i*5),
                    java.util.stream.IntStream.rangeClosed(31,40).map(i -> i*10))).toArray();

    double focal(double fraction) {
        return snapFocal(Math.fma(tele-wide,Math.clamp(fraction,0,1),wide));
    }

    int snapFocal(double focal) {
        if (hasBuiltInConverter()) return converterEngaged(focal) ? tele : wide;
        int rounded = (int)Math.round(Math.clamp(focal,wide,tele));
        if (this != Z100_400) return rounded;
        int i = java.util.Arrays.binarySearch(LONG_ZOOM_STOPS,rounded);
        if (i >= 0) return rounded;
        int next = -i-1;
        return focal-LONG_ZOOM_STOPS[next-1] < LONG_ZOOM_STOPS[next]-focal
                ? LONG_ZOOM_STOPS[next-1] : LONG_ZOOM_STOPS[next];
    }

    int stepFocal(double focal, int direction) {
        int current = snapFocal(focal);
        if (hasBuiltInConverter()) return direction > 0 ? tele : direction < 0 ? wide : current;
        if (this != Z100_400) return Math.clamp(current+direction,wide,tele);
        int index = java.util.Arrays.binarySearch(LONG_ZOOM_STOPS,current);
        return LONG_ZOOM_STOPS[Math.clamp(index+direction,0,LONG_ZOOM_STOPS.length-1)];
    }

    double minFocus(double focal) {
        if (this == Z14_24) return .28;
        if (this == XCD35_100) return .4+.1*fraction(focal);
        if (this == Z600_TC) return 4.3;
        if (this == Z58_NOCT) return .5;
        return this == Z24_120 ? .35 : interpolate(focal,
                new double[]{100,135,200,300,400},new double[]{.75,.78,.80,.87,.98});
    }

    int firstAperture(double focal) {
        if (this == Z600_TC) return converterEngaged(focal) ? 3 : 0;
        // Hasselblad's sample EXIF and independent zoom-stop observations.
        if (this == XCD35_100) return focal < 36 ? 0 : focal < 55 ? 1 : focal < 72 ? 2 : 3;
        if (this != Z100_400) return 0;
        // EXIF measurements near each transition from the Savazzi lens review.
        if (focal < 125) return 0;
        if (focal < 185) return 1;
        if (focal < 270) return 2;
        return focal < 360 ? 3 : 4;
    }

    int lastAperture(double focal) {
        if (this == Z600_TC) return converterEngaged(focal) ? 21 : 18;
        if (this != Z100_400) return apertures().length-1;
        return focal < 125 ? 19 : focal < 360 ? 20 : 21;
    }

    double[] apertures() {
        return switch (this) {
            case Z24_120 -> Z8Settings.APERTURES;
            case Z100_400 -> LONG_APERTURES;
            case Z58_NOCT -> NOCT_APERTURES;
            case Z600_TC -> SUPERTELE_APERTURES;
            case Z14_24 -> ULTRAWIDE_APERTURES;
            case XCD35_100 -> XCD_APERTURES;
        };
    }
    double wideOpen(double focal) { return apertures()[firstAperture(focal)]; }

    Z24120Field field(double focal, double stop) {
        if (this == Z24_120) return Z24120Field.at(focal,stop);
        if (this == Z14_24) {
            // Small residual distortion and approximate shading after lens correction.
            double zoom = fraction(focal);
            double stops = Math.max(0,2*Math.log(stop/2.8)/Math.log(2));
            return new Z24120Field(.13+(1.10-.45*zoom)*Math.exp(-.92*stops),-.006+.008*zoom);
        }
        if (this == XCD35_100) {
            double stops = Math.max(0,2*Math.log(stop/wideOpen(focal))/Math.log(2));
            double open = interpolate(focal,XCD_FIELD_FOCALS,XCD_OPEN_CORNER_EV);
            double closed = interpolate(focal,XCD_FIELD_FOCALS,XCD_CLOSED_CORNER_EV);
            double distortion = interpolate(focal,XCD_FIELD_FOCALS,XCD_DISTORTION);
            return new Z24120Field(closed+(open-closed)*Math.exp(-.98*stops),distortion);
        }
        if (this == Z58_NOCT) {
            // DXOMARK's uncorrected corner falloff and very slight pincushion.
            return new Z24120Field(interpolate(stop,NOCT_VIGNETTE_STOPS,NOCT_CORNER_EV),.002);
        }
        if (this == Z600_TC) {
            boolean tc = converterEngaged(focal);
            // Small residuals after in-camera correction. CameraLabs shows little falloff one stop down.
            double stops = Math.max(0,2*Math.log(stop/wideOpen(focal))/Math.log(2));
            return new Z24120Field(.035+(tc ? .245 : .185)*Math.exp(-2.1*stops),tc ? .0008 : -.0004);
        }
        // Corrected field estimates based on the CameraLabs samples. The test's
        // uncorrected pincushion values are reduced to a small residual.
        double open = interpolate(focal,new double[]{100,200,300,400},new double[]{.66,.52,.69,1.00});
        double wideStop = wideOpen(focal);
        double corner = .10+(open-.10)*Math.exp(-2*Math.log(stop/wideStop)/Math.log(2));
        double residual = interpolate(focal,new double[]{100,200,300,400},new double[]{.0004,.00196,.00206,.00188})*.5;
        return new Z24120Field(corner,residual);
    }

    private static double interpolate(double value, double[] marks, double[] data) {
        int i = 0;
        while (i+2 < marks.length && value > marks[i+1]) i++;
        double t = Math.clamp((value-marks[i])/(marks[i+1]-marks[i]),0,1);
        return Math.fma(data[i+1]-data[i],t,data[i]);
    }
}
