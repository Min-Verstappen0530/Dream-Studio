package dev.physicalcamera;

import java.util.ArrayList;
import java.util.List;

/** Compact, energy-preserving PSF fitted to the lens's published f/4 design curves. */
final class Z24120Mtf {
    private static final double[] HEIGHT_MM = {0,2.5,5,7.5,10,12.5,15,17.5,20,21.6};
    // S10, M10, S30, M30 sampled from the wide and tele charts respectively.
    private static final double[][] WIDE = {
            {.997,.997,.962,.962}, {.997,.993,.964,.942}, {.997,.991,.964,.917},
            {.997,.982,.962,.866}, {.995,.973,.957,.789}, {.993,.959,.937,.707},
            {.989,.939,.899,.619}, {.983,.901,.847,.525}, {.926,.881,.664,.540},
            {.872,.878,.497,.601}
    };
    private static final double[][] TELE = {
            {.997,.997,.954,.954}, {.997,.993,.958,.949}, {.997,.992,.958,.935},
            {.996,.984,.946,.862}, {.990,.969,.919,.765}, {.984,.954,.856,.670},
            {.970,.939,.760,.555}, {.947,.906,.606,.408}, {.936,.861,.560,.291},
            {.933,.824,.578,.215}
    };
    private static final double[] OFFSETS_MM = {0,.0075,.024};
    // NIKKOR Z 100–400 design curves sampled from Nikon's S/M 10 and 30 lp/mm charts.
    private static final double[][] LONG_WIDE = {
            {.988,.988,.910,.910},{.988,.985,.913,.908},{.988,.984,.913,.900},
            {.987,.979,.907,.869},{.987,.978,.896,.833},{.985,.968,.876,.785},
            {.982,.962,.854,.730},{.979,.952,.829,.679},{.958,.945,.772,.654},{.947,.940,.716,.650}
    };
    private static final double[][] LONG_TELE = {
            {.992,.992,.929,.929},{.992,.991,.933,.920},{.993,.982,.937,.915},
            {.993,.981,.942,.904},{.993,.981,.943,.889},{.993,.978,.941,.854},
            {.993,.973,.936,.814},{.992,.958,.919,.746},{.987,.942,.882,.650},{.985,.927,.855,.584}
    };
    // Nikon's Noct f/0.95 design curves, sampled at the same image heights.
    private static final double[][] NOCT = {
            {.956,.956,.749,.749},{.952,.952,.738,.709},{.948,.950,.725,.675},
            {.945,.959,.712,.677},{.940,.960,.696,.695},{.942,.961,.711,.713},
            {.945,.959,.727,.727},{.901,.955,.649,.731},{.803,.945,.481,.672},{.721,.925,.426,.564}
    };
    // Nikon's full-aperture S/M charts for the native lens and built-in 1.4× converter.
    private static final double[][] SUPERTELE = {
            {.997,.997,.981,.981},{.997,.996,.983,.983},{.997,.996,.985,.985},
            {.997,.996,.987,.987},{.997,.996,.988,.987},{.997,.996,.987,.984},
            {.997,.996,.986,.978},{.997,.995,.983,.965},{.996,.994,.979,.949},{.995,.991,.974,.941}
    };
    private static final double[][] SUPERTELE_TC = {
            {.996,.996,.971,.971},{.996,.995,.973,.972},{.996,.994,.975,.965},
            {.996,.993,.975,.954},{.996,.990,.976,.940},{.996,.989,.976,.923},
            {.995,.985,.973,.901},{.994,.980,.964,.866},{.992,.973,.942,.810},{.990,.966,.912,.775}
    };
    // Nikon's f/2.8 design charts at 14 and 24 mm, S/M 10 and 30 lp/mm.
    private static final double[][] ULTRAWIDE_WIDE = {
            {.990,.990,.908,.908},{.989,.989,.891,.889},{.987,.987,.885,.881},
            {.988,.989,.904,.894},{.990,.990,.925,.902},{.990,.987,.928,.895},
            {.989,.984,.922,.858},{.989,.978,.913,.838},{.989,.968,.902,.758},{.988,.960,.900,.710}
    };
    private static final double[][] ULTRAWIDE_TELE = {
            {.997,.997,.963,.963},{.990,.995,.952,.917},{.983,.990,.933,.887},
            {.984,.987,.898,.866},{.985,.985,.862,.854},{.983,.984,.824,.851},
            {.971,.976,.790,.845},{.938,.963,.644,.754},{.870,.938,.506,.613},{.789,.907,.500,.460}
    };
    // Reduced-order 10/30 lp/mm envelopes over the 55 mm image circle. They
    // follow the Hasselblad chart positions and the directional and tele-end
    // losses in the DCW and Kasson tests. Diffraction is handled separately.
    private static final double[] XCD_FOCALS = {35,45,60,100};
    private static final double[][] XCD_WIDE = {
            {.995,.995,.925,.925},{.995,.994,.924,.919},{.993,.991,.923,.907},{.991,.984,.916,.886},{.989,.980,.909,.850},
            {.988,.971,.894,.825},{.984,.960,.877,.786},{.976,.951,.857,.737},{.963,.938,.829,.689},{.953,.918,.790,.633}
    };
    private static final double[][] XCD_45 = {
            {.996,.996,.942,.942},{.994,.994,.938,.938},{.992,.992,.934,.930},{.991,.988,.924,.915},{.989,.985,.913,.895},
            {.986,.982,.901,.880},{.982,.977,.887,.850},{.978,.967,.865,.804},{.970,.954,.827,.757},{.955,.937,.772,.691}
    };
    private static final double[][] XCD_60 = {
            {.996,.996,.945,.945},{.995,.995,.944,.941},{.994,.993,.939,.935},{.993,.988,.933,.929},{.990,.986,.918,.919},
            {.987,.983,.904,.909},{.986,.981,.889,.890},{.979,.971,.868,.851},{.971,.962,.837,.806},{.947,.940,.772,.735}
    };
    private static final double[][] XCD_TELE = {
            {.989,.989,.884,.884},{.988,.987,.881,.877},{.985,.983,.869,.863},{.982,.977,.849,.839},{.980,.970,.832,.801},
            {.974,.960,.802,.758},{.963,.944,.759,.713},{.950,.928,.706,.654},{.934,.901,.642,.589},{.909,.870,.589,.515}
    };
    private static final double[][][] XCD = {XCD_WIDE,XCD_45,XCD_60,XCD_TELE};
    private static final double CORE_10 = loss(10,OFFSETS_MM[1]), HALO_10 = loss(10,OFFSETS_MM[2]);
    private static final double CORE_30 = loss(30,OFFSETS_MM[1]), HALO_30 = loss(30,OFFSETS_MM[2]);
    private static final double DETERMINANT = CORE_30*HALO_10-CORE_10*HALO_30;
    private static final int PROFILE_DENSITY = 12, PROFILE_END = 260;
    private static final int BLUR_SEQUENCE_SIZE = 256;

    record Tap(double radialMm, double tangentialMm, double weight) {}
    record Kernel(Tap[] taps, Tap[] blurSequence) {
        Tap forApertureSample(int i) { return blurSequence[i & (BLUR_SEQUENCE_SIZE-1)]; }
    }

    static final class Profile {
        private final Kernel[] field = new Kernel[PROFILE_END+1];
        private final double imageHeightScale;

        Profile(double focalLength) {
            this(ZoomLens.Z24_120,focalLength);
        }

        Profile(ZoomLens lens, double focalLength) {
            this(lens,focalLength,lens.wideOpen(focalLength));
        }

        Profile(ZoomLens lens, double focalLength, double aperture) {
            this(lens,focalLength,aperture,Double.POSITIVE_INFINITY);
        }

        Profile(ZoomLens lens, double focalLength, double aperture, double focusMeters) {
            double zoom = lens.fraction(focalLength);
            imageHeightScale = lens == ZoomLens.XCD35_100 ? 21.6/Math.hypot(43.8/2,32.9/2) : 1;
            int xcdInterval = focalLength < 45 ? 0 : focalLength < 60 ? 1 : 2;
            var wide = switch (lens) {
                case Z24_120 -> WIDE;
                case Z100_400 -> LONG_WIDE;
                case Z58_NOCT -> NOCT;
                case Z600_TC -> SUPERTELE;
                case Z14_24 -> ULTRAWIDE_WIDE;
                case XCD35_100 -> XCD[xcdInterval];
            };
            var tele = switch (lens) {
                case Z24_120 -> TELE;
                case Z100_400 -> LONG_TELE;
                case Z58_NOCT -> NOCT;
                case Z600_TC -> SUPERTELE_TC;
                case Z14_24 -> ULTRAWIDE_TELE;
                case XCD35_100 -> XCD[xcdInterval+1];
            };
            double zoomBlend = lens == ZoomLens.XCD35_100
                    ? Math.clamp((focalLength-XCD_FOCALS[xcdInterval])/(XCD_FOCALS[xcdInterval+1]-XCD_FOCALS[xcdInterval]),0,1)
                    : zoom;
            double stops = Math.max(0,2*Math.log(aperture/lens.wideOpen(focalLength))/Math.log(2));
            // At short distances the built-in TC loses some acuity, especially wide open.
            double close = lens.converterEngaged(focalLength)
                    ? .80*Math.pow(4.3/Math.max(4.3,focusMeters),2)*Math.exp(-1.4*stops) : 0;
            for (int i = 0; i < field.length; i++) {
                double height = i/(double)PROFILE_DENSITY;
                double loss = aberrationResidual(lens,zoom,height,stops);
                field[i] = kernel(wide,tele,zoomBlend,height,loss+close);
            }
        }

        Kernel at(double height) {
            return field[Math.min((int)Math.round(height*imageHeightScale*PROFILE_DENSITY),PROFILE_END)];
        }
    }

    private static double aberrationResidual(ZoomLens lens, double zoom, double height, double stops) {
        double field = Math.clamp(height/21.6,0,1);
        // Fit only the relative stop-down trend seen in OpticalLimits (24–120) and the
        // 100/400 mm test crops by PetaPixel. Nikon's curves supply the wide-open baseline.
        double rate = switch (lens) {
            case Z24_120 -> .13+field*(.28+.08*Math.sin(Math.PI*zoom));
            case Z100_400 -> mix(.43,.61,zoom)+field*mix(.34,.28,zoom);
            // CameraLabs centre is strong wide open. Corners catch up after stopping down.
            case Z58_NOCT -> .37+.29*field;
            case Z600_TC -> .23+.38*zoom+.17*field;
            case Z14_24 -> .24+.28*field;
            case XCD35_100 -> .20+(.64-.23*zoom)*field;
        };
        return .28+.72*Math.exp(-rate*stops);
    }

    private static double loss(double frequency, double offset) { return 1-Math.cos(Math.TAU*frequency*offset); }

    private static Kernel kernel(double[][] wide, double[][] tele, double zoom, double height, double residual) {
        int high = 1;
        while (high < HEIGHT_MM.length-1 && height > HEIGHT_MM[high]) high++;
        int low = high-1;
        double field = Math.clamp((height-HEIGHT_MM[low])/(HEIGHT_MM[high]-HEIGHT_MM[low]),0,1);
        // The stripe normal sets the blur direction: radial for M and tangential for S.
        double[] radial = weights(mtf(wide,tele,1,low,high,field,zoom,residual),mtf(wide,tele,3,low,high,field,zoom,residual));
        double[] tangent = weights(mtf(wide,tele,0,low,high,field,zoom,residual),mtf(wide,tele,2,low,high,field,zoom,residual));
        List<Tap> taps = new ArrayList<>();
        for (int r = 0; r < radial.length; r++) {
            for (int t = 0; t < tangent.length; t++) {
                double weight = radial[r]*tangent[t];
                if (weight > 1e-12) addSymmetric(taps,OFFSETS_MM[r],OFFSETS_MM[t],weight);
            }
        }
        Tap[] stencil = taps.toArray(Tap[]::new);
        Tap[] sequence = new Tap[BLUR_SEQUENCE_SIZE];
        for (int i = 0; i < sequence.length; i++) {
            double u = (Integer.reverse(i) >>> 24)/256.0 + .5/256;
            int tap = 0;
            while (tap < stencil.length-1 && u > stencil[tap].weight) u -= stencil[tap++].weight;
            sequence[i] = stencil[tap];
        }
        return new Kernel(stencil,sequence);
    }

    private static double mtf(double[][] wide, double[][] tele, int curve, int low, int high, double field,
                              double zoom, double residual) {
        double contrast = mix(mix(wide[low][curve],wide[high][curve],field),mix(tele[low][curve],tele[high][curve],field),zoom);
        return 1-(1-contrast)*residual;
    }

    private static double mix(double a, double b, double t) { return Math.fma(b-a,t,a); }

    private static double[] weights(double mtf10, double mtf30) {
        double loss10 = 1-mtf10, loss30 = 1-mtf30;
        double halo = Math.clamp((loss10*CORE_30-loss30*CORE_10)/DETERMINANT,0,loss30/HALO_30);
        double core = Math.clamp((loss30-halo*HALO_30)/CORE_30,0,1-halo);
        return new double[]{1-core-halo,core,halo};
    }

    private static void addSymmetric(List<Tap> taps, double r, double t, double mass) {
        int radialSigns = r == 0 ? 1 : 2;
        int tangentialSigns = t == 0 ? 1 : 2;
        double weight = mass/(radialSigns*tangentialSigns);
        for (int i = 0; i < radialSigns; i++) {
            for (int j = 0; j < tangentialSigns; j++) {
                taps.add(new Tap(i == 0 ? r : -r,j == 0 ? t : -t,weight));
            }
        }
    }

    private Z24120Mtf() {}
}
