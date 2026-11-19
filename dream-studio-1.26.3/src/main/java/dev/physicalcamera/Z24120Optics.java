package dev.physicalcamera;

/** First-order spectral ray tracing through the fitted prescriptions of the mounted lenses. */
final class Z24120Optics {
    static final int[] ZOOM_MARKS = {24,28,35,50,70,85,120};
    static double lightThroughput(double fNumber) {
        return lightThroughput(ZoomLens.Z24_120,fNumber);
    }
    static double lightThroughput(ZoomLens lens, double fNumber) {
        return lens.transmission()*Math.PI/(4*fNumber*fNumber);
    }
    private static final double DIFFRACTION_AIRY = 1.22;
    private static final double[][] TRAVEL_MM = {
            {31.4037832,27.1047504,.8843852,1.0000000},
            {32.7951337,22.7108171,1.3813956,1.8484330},
            {34.4208666,17.0312177,2.1210900,3.4329487},
            {37.3868593,9.9013536,3.8279149,6.8064434},
            {42.7668756,5.3186700,8.5478949,10.4887258},
            {50.9494536,4.4900305,16.2466381,10.8867589},
            {63.3171050,3.6847690,28.7712614,10.3076246}
    };

    enum Glass {
        AIR(1,0), CROWN(1.5168,64.2), FLINT(1.620,36.4), ASPHERICAL(1.590,58),
        ED(1.497,81.5), ASPHERICAL_ED(1.497,81.5),
        SUPER_ED(1.438,94.9), FLUORITE(1.4338,95.2), SR(1.635,40);

        final double nd, cauchy;
        Glass(double nd, double abbe) {
            this.nd = nd;
            cauchy = abbe == 0 ? 0 : (nd-1)/abbe/(1/(.48613*.48613)-1/(.65627*.65627));
        }
        double index(int band) {
            double wavelength = Optics.WAVELENGTH_NM[band]/1000;
            return nd+cauchy*(1/(wavelength*wavelength)-1/(.58756*.58756));
        }
    }

    record Element(Glass glass, double thickness, double entryCurvature, double exitCurvature,
                   double airGap, int movingGap) {
        Element(Glass glass, double thickness, double entryCurvature, double exitCurvature, double airGap) {
            this(glass,thickness,entryCurvature,exitCurvature,airGap,-1);
        }
    }

    // Object to sensor. Cemented pairs share one refracting surface. Curvatures are in mm^-1.
    private static final Element[] ELEMENTS = {
            new Element(Glass.CROWN,1.4,.043246729,-.000723287,0),
            new Element(Glass.FLINT,9,-.000723287,.007610934,1.6),
            new Element(Glass.ASPHERICAL,1.2,.028564302,.055985156,7.6),
            new Element(Glass.CROWN,.8,-.038597711,.027865591,1),
            new Element(Glass.CROWN,4.2,.044309092,-.025318870,2.2),
            new Element(Glass.ED,.8,-.031272391,-.028604944,0,0),
            new Element(Glass.ASPHERICAL,3.4,-.003472418,.047775018,.4),
            new Element(Glass.CROWN,1.6,-.000858107,.093760325,0,1),
            new Element(Glass.ED,4.8,.042508919,-.033746347,1.8),
            new Element(Glass.FLINT,1,.017818145,.100187761,0),
            new Element(Glass.ED,6.2,.100187761,-.023861293,0,2),
            new Element(Glass.FLINT,.8,-.035535267,-.072526782,.6),
            new Element(Glass.CROWN,3.6,-.013061018,-.021356400,1.6),
            new Element(Glass.ASPHERICAL_ED,3.8,-.004283743,-.030795777,0,3),
            new Element(Glass.ASPHERICAL,1.2,-.063383009,.035397441,0),
            new Element(Glass.CROWN,3.4,.035397441,.032696477,0)
    };

    // Ray coordinates (height, n * slope). Higher-order wavefront terms are outside this model.
    private record Matrix(double a, double b, double c, double d) {
        Matrix refract(double power) { return new Matrix(a,b,c-power*a,d-power*b); }
        Matrix travel(double reducedDistance) {
            return new Matrix(a+reducedDistance*c,b+reducedDistance*d,c,d);
        }
        Matrix rebaseInput(double offset) { return new Matrix(a,b-a*offset,c,d-c*offset); }
        double focalLength() { return -1/c; }
    }

    record Channel(double a, double b, double lateralShift, double airyRadius) {}

    static final class Prescription {
        private final double[] marks;
        private final Matrix[][] matrices;
        private final int elements, groups;

        Prescription(double[] marks, Element[] glass, double[][] gaps) {
            this.marks = marks;
            elements = glass.length;
            int groupCount = 1;
            for (int i = 0; i+1 < glass.length; i++) {
                if (glass[i].airGap > 0 || glass[i].movingGap >= 0) groupCount++;
            }
            groups = groupCount;
            matrices = new Matrix[marks.length][Optics.WAVELENGTH_NM.length];
            for (int i = 0; i < marks.length; i++) {
                for (int band = 0; band < matrices[i].length; band++) {
                    matrices[i][band] = trace(glass,gaps[i],band);
                }
            }
        }

        private Matrix[] at(double mark, double effectiveFocal) {
            int high = 0;
            while (high+1 < marks.length && mark > marks[high]) high++;
            int low = Math.max(0,high-1);
            double blend = high == low ? 0 : (mark-marks[low])/(marks[high]-marks[low]);
            Matrix[] left = matrices[low], right = matrices[high];
            Matrix[] result = new Matrix[left.length];
            double greenLeft = left[1].focalLength(), greenRight = right[1].focalLength();
            for (int band = 0; band < result.length; band++) {
                Matrix l = left[band], r = right[band];
                double fl = l.focalLength(), fr = r.focalLength();
                // Fit the green EFL to the calibrated barrel scale, then keep
                // the traced chromatic offsets and interpolate both principal planes.
                double f = effectiveFocal+lerp(fl-greenLeft,fr-greenRight,blend);
                double a = lerp(l.a*fl,r.a*fr,blend)/f;
                double d = 1-lerp((1-l.d)*fl,(1-r.d)*fr,blend)/f;
                result[band] = new Matrix(a,(1-a*d)*f,-1/f,d);
            }
            return result;
        }
    }

    static final class Exposure {
        final ZoomLens lens;
        private final double focal, indicatedFocal, pupilRadius, throughput, focusMeters;
        private final double diffractionFraction, lateralColor, pupilClip;
        private final Channel[] channels;

        private Exposure(ZoomLens lens, Matrix[] train, double indicatedFocal, double focusMeters, double fNumber) {
            this.lens = lens;
            this.focusMeters = focusMeters;
            Matrix green = train[1];
            focal = green.focalLength();
            this.indicatedFocal = indicatedFocal;
            pupilRadius = focal/(2*fNumber);
            double opening = lens.wideOpen(indicatedFocal)/fNumber;
            diffractionFraction = lens == ZoomLens.Z600_TC ? .72 : .72*Math.sqrt(Math.max(0,1-opening*opening));
            throughput = lightThroughput(lens,fNumber);
            pupilClip = lens.pupilClipping(indicatedFocal,fNumber);
            lateralColor = lens.lateralColor(indicatedFocal,opening);
            double principalPlane = focal*(1-green.d);
            double inverseFocus = 1/(focusMeters*1000);
            Matrix front = green.rebaseInput(principalPlane);
            double sensor = -(front.a+front.b*inverseFocus)/(front.c+front.d*inverseFocus);
            Matrix reference = front.travel(sensor);
            double chromaticWeight = switch (lens) {
                case Z24_120 -> .28;
                case Z14_24, Z100_400 -> .24;
                case Z58_NOCT -> .45*Math.pow(opening,2.5);
                case Z600_TC -> .15;
                case XCD35_100 -> .23;
            };
            double axialLimit = switch (lens) {
                case Z24_120 -> .00018;
                case Z14_24 -> .00036;
                case Z100_400 -> .00016;
                case Z58_NOCT -> .00048;
                case Z600_TC -> .000059;
                case XCD35_100 -> .00023;
            };
            channels = new Channel[train.length];
            for (int band = 0; band < channels.length; band++) {
                Matrix focused = train[band].rebaseInput(principalPlane).travel(sensor);
                double airy = DIFFRACTION_AIRY*Optics.WAVELENGTH_NM[band]*1e-6*fNumber;
                double slope = Math.clamp((focused.b-reference.b)/reference.b,-.002,.002)*chromaticWeight;
                double focalError = (focused.a+focused.b*inverseFocus)*chromaticWeight;
                double axial = Math.clamp(focalError,-axialLimit,axialLimit);
                if (lens == ZoomLens.Z58_NOCT && band != 1) {
                    // The Noct's near/far test crops establish the sign and the
                    // strong stop-down trend. Retain a small traced variation.
                    double measured = band == 0 ? .00033 : .00044;
                    double calibrated = Math.clamp(focalError,-measured*.08,measured*.08);
                    axial = (measured+calibrated)*Math.pow(opening,2.5);
                }
                double b = reference.b*(1+slope);
                double a = axial-b*inverseFocus;
                double lateralLimit = lens == ZoomLens.Z24_120 ? .000062 : .00012;
                double lateral = Math.clamp(-slope,-lateralLimit,lateralLimit);
                channels[band] = new Channel(a,b,lateral,airy);
            }
        }

        double focalLength() { return focal; }
        double indicatedFocal() { return indicatedFocal; }
        double projectionFocal() { return lens.projectionFocal(indicatedFocal,focusMeters); }
        double focusMeters() { return focusMeters; }
        double pupilClipping(double fieldRadiusSquared) { return pupilClip*fieldRadiusSquared; }
        double fNumber() { return focal/(2*pupilRadius); }
        double entrancePupilDiameter() { return 2*pupilRadius; }
        double throughput() { return throughput; }
        Channel channel(int band) { return channels[band]; }
        double colourScale(int band, double fieldRadiusSquared) {
            double shift = lateralColor != 0 ? (band-1)*.5*lateralColor : channels[band].lateralShift();
            return 1+shift*fieldRadiusSquared*fieldRadiusSquared;
        }

        double defocusRadius(double distanceMeters, int band) {
            return radiusAtInverseDistance(1/distanceMeters,band);
        }

        double radiusAtInverseDistance(double inverseMetres, int band) {
            Channel channel = channels[band];
            return pupilRadius*Math.abs(channel.a+channel.b*inverseMetres*.001);
        }

        double spreadAtInverseDistance(double inverseMetres, int band) {
            // The Z 600 design chart is geometric. Other MTF profiles already include their baseline diffraction.
            double diffraction = channels[band].airyRadius()*diffractionFraction;
            return Math.hypot(radiusAtInverseDistance(inverseMetres,band),diffraction);
        }

        record DepthOfField(double nearMeters, double farMeters) {}

        DepthOfField depthOfField(double circleDiameterMm) {
            double allowed = circleDiameterMm/(pupilRadius*2*channels[1].b);
            double inverseFocus = 1/(focusMeters*1000);
            return new DepthOfField(.001/(inverseFocus+allowed),
                    inverseFocus > allowed ? .001/(inverseFocus-allowed) : Double.POSITIVE_INFINITY);
        }
    }

    private static final Prescription STANDARD = new Prescription(
            new double[]{24,28,35,50,70,85,120},ELEMENTS,TRAVEL_MM);

    private Z24120Optics() {}

    static int elements() { return STANDARD.elements; }
    static int groups() { return STANDARD.groups; }
    static int elements(ZoomLens lens, double focal) { return prescription(lens,focal).elements; }
    static int groups(ZoomLens lens, double focal) { return prescription(lens,focal).groups; }

    static Exposure focus(double focal, double metres, double fNumber) {
        return focus(ZoomLens.Z24_120,focal,metres,fNumber);
    }

    static Exposure focus(ZoomLens lens, double focal, double metres, double fNumber) {
        double mark = lens.hasBuiltInConverter() ? lens.snapFocal(focal) : Math.clamp(focal,lens.wide,lens.tele);
        Matrix[] train = prescription(lens,mark).at(mark,lens.opticalFocal(mark));
        return new Exposure(lens,train,mark,Math.max(metres,lens.minFocus(mark)),fNumber);
    }

    private static Prescription prescription(ZoomLens lens, double mark) {
        return switch (lens) {
            case Z24_120 -> STANDARD;
            case Z14_24 -> LensPrescriptions.ULTRAWIDE;
            case Z58_NOCT -> LensPrescriptions.NOCT;
            case Z100_400 -> LensPrescriptions.LONG_ZOOM;
            case Z600_TC -> lens.converterEngaged(mark) ? LensPrescriptions.SUPERTELE_TC : LensPrescriptions.SUPERTELE;
            case XCD35_100 -> LensPrescriptions.XCD;
        };
    }

    private static double lerp(double a, double b, double t) { return a+(b-a)*t; }

    private static Matrix trace(Element[] elements, double[] airTravel, int band) {
        Matrix ray = new Matrix(1,0,0,1);
        double before = 1;
        for (int i = 0; i < elements.length; i++) {
            Element element = elements[i];
            double glass = element.glass.index(band);
            ray = ray.refract((glass-before)*element.entryCurvature).travel(element.thickness/glass);
            double gap = element.movingGap < 0 ? element.airGap : airTravel[element.movingGap];
            boolean exitsToAir = gap > 0 || i == elements.length-1;
            if (exitsToAir) {
                ray = ray.refract((1-glass)*element.exitCurvature).travel(gap);
            }
            before = exitsToAir ? 1 : glass;
        }
        return ray;
    }
}
