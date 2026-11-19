package dev.physicalcamera;

import java.nio.file.Path;
import java.util.Random;

/** Exercises the optics and media without launching Minecraft. */
public final class CameraCheck {
    private static final RecordingMedia.FilmStock SLOW_FILM = RecordingMedia.FilmStock.ISO_100;
    private static final RecordingMedia.FilmStock FAST_FILM = RecordingMedia.FilmStock.ISO_800;

    public static void main(String[] args) throws Exception {
        var lens = Optics.Lens.singlet(44, 0);
        var wideLens = Optics.Lens.singlet(28, 0);
        var camera = lens.focus(1200, 2.8);
        var narrow = lens.focus(1200, 8);
        var near = lens.focus(180, 2.8);
        var random = new Random(10);

        require(lens.focalLength() > 40 && lens.focalLength() < 46, "standard focal length");
        require(wideLens.focalLength() < lens.focalLength(), "wide lens focal length");
        require(near.backFocus() > camera.backFocus(), "near focus requires lens extension");
        require(Math.abs(project(camera, 0.1, 1200, 1, random)) < 1e-4, "paraxial focus");
        require(Math.abs(project(near, 0.1, 180, 1, random)) < 1e-4, "near paraxial focus");
        double openBlur = Math.abs(project(camera, camera.stopRadius() * 0.8, 180, 1, random));
        double stoppedBlur = Math.abs(project(narrow, narrow.stopRadius() * 0.8, 180, 1, random));
        require(stoppedBlur < openBlur, "stopping down reduces foreground defocus");
        double blue = project(camera, 3, 1200, 2, random);
        double red = project(camera, 3, 1200, 0, random);
        require(Math.abs(blue - red) > 0.3, "wavelength-dependent refraction");
        double tangentialFocus = fieldFocus(camera, 0.01, 0, random);
        double sagittalFocus = fieldFocus(camera, 0, 0.01, random);
        require(Math.abs(tangentialFocus - sagittalFocus) > 1, "off-axis astigmatism");
        require(camera.trace(0, 0, 30, 0, 1, random) == null, "aperture rejection");
        var rough = Optics.Lens.singlet(44, 0.0025).focus(1200, 2.8);
        require(Math.abs(project(rough, 3, 1200, 1, random)
                - project(camera, 3, 1200, 1, random)) > 0.01, "surface scattering");
        require(RecordingMedia.developDensity(15, SLOW_FILM, new Random(10))
                > RecordingMedia.developDensity(0, SLOW_FILM, new Random(10)) + 1, "negative density");
        checkCmos();
        checkIso();
        checkZ8Exposure();
        checkLiveViewMeter();
        checkOpenMount();
        checkZoomDepthOfField();
        checkLensPrescriptions();
        checkZoomMtf();
        checkApertureResolution();
        checkNoct();
        checkOpticalCharacter();
        checkZoomSteps();
        checkSuperteleConverter();
        checkSensorArea();
        checkDepthEdges();

        Path output = Path.of(args[0]);
        render(camera, output.resolve("far-focus"));
        render(near, output.resolve("near-focus"));
        renderIso(output.resolve("iso"));
        System.out.printf("Camera checks passed. Focal lengths %.2f / %.2f mm.%n",
                lens.focalLength(), wideLens.focalLength());
    }

    private static void checkDepthEdges() {
        var depth = new Z8Depth(4,2,new float[]{.5f,.5f,.025f,.025f, .5f,.5f,.025f,.025f});
        int[] pixels = {0xffdd0000,0xffdd0000,0xff0000dd,0xff0000dd,
                0xffdd0000,0xffdd0000,0xff0000dd,0xff0000dd};
        var edges = new Z8DepthEdge(depth);
        require(edges.split(depth.indexAt(.125,.25),.125,.25,.25,.5) == null,"no filtering within a continuous depth");
        var left = edges.split(depth.indexAt(.49,.25),.49,.25,.25,.5);
        var right = edges.split(depth.indexAt(.51,.25),.51,.25,.25,.5);
        require(left != null && right != null,"fractional coverage on both sides of depth edge");
        require(left.centre().inverseDepth() == .5 && right.neighbour().inverseDepth() == .5
                && left.neighbour().inverseDepth() < .03 && right.centre().inverseDepth() < .03,
                "foreground and background remain on separate focus planes");
        require(Math.abs(left.neighbourCoverage()-right.neighbourCoverage()) < .001
                && left.neighbourCoverage() > .4 && left.neighbourCoverage() < .5,"continuous silhouette coverage");
        require(Z8Frame.interpolateOnSurface(4,2,pixels,null,depth,.49,.25,2,.5,.375,.25) == 0
                && Z8Frame.interpolateOnSurface(4,2,pixels,null,depth,.51,.25,0,.025,.625,.25) == 0,
                "edge colour is filtered independently on each surface");
    }

    private static void checkSuperteleConverter() {
        var lens = ZoomLens.Z600_TC;
        var settings = new Z8Settings();
        settings.setLens(lens,600);
        var nativeLens = settings.lensExposure(settings.exposure(.18).aperture());
        require(lens.focal(.49) == 600 && lens.focal(.50) == 840, "TC slider has two endpoints");
        require(lens.stepFocal(600,1) == 840 && lens.stepFocal(840,-1) == 600, "TC responds to single clicks");
        settings.changeFocal(840);
        var tc = settings.lensExposure(settings.exposure(.18).aperture());
        require(Math.abs(tc.focalLength()-840) < 1e-8 && Math.abs(tc.fNumber()-5.6) < 1e-8, "engaging TC changes focal length and aperture");
        require(Math.abs(tc.entrancePupilDiameter()-nativeLens.entrancePupilDiameter()) < 1e-8, "same physical pupil with TC");
        require(Math.abs(tc.throughput()/nativeLens.throughput()-1/1.96) < 1e-8, "TC loses approximately one stop");
        require(Math.abs(tc.projectionFocal()/nativeLens.projectionFocal()-1.4) < 1e-8, "TC tightens framing by 1.4×");
        settings.aperture = settings.lastAperture();
        require(settings.exposure(.18).aperture() == 45, "f/45 with TC");
        settings.changeFocal(600);
        require(settings.exposure(.18).aperture() == 32, "f/32 without TC");
        require(settings.distance() == 4.3, "4.3 m minimum focus");
        settings.focusLimit = true;
        require(settings.autofocusNear() == 10, "AF limiter starts at ten metres");
        require(lens.field(600,4).cornerEv() > lens.field(600,5.6).cornerEv(), "vignetting fades with aperture");
        var far = new Z24120Mtf.Profile(lens,840,5.6,90).at(21.6);
        var close = new Z24120Mtf.Profile(lens,840,5.6,4.3).at(21.6);
        require(mtf(close,30,true) < mtf(far,30,true), "840 mm edges are softer near minimum focus");
    }

    private static void checkOpenMount() {
        double center = OpenMount.throughput(.5,.5);
        double corner = OpenMount.throughput(0,0);
        double analytic = Math.PI*27.5*27.5/(27.5*27.5+16*16);
        require(Math.abs(center-analytic) < .01, "bare mount integrates the projected solid angle");
        require(corner > 0 && corner < center && Math.abs(corner-OpenMount.throughput(1,1)) < 1e-8,
                "bare mount has symmetric edge falloff");
        var average = OpenMount.average((u,v,band) -> band == 1 ? u : v/2);
        require(Math.abs(average[1]-.5) < 1e-8 && Math.abs(average[0]-.25) < 1e-8,
                "bare mount mixes light across the scene");
    }

    private static void checkZ8Exposure() {
        var camera = new Z8Settings();
        var manual = camera.exposure(.09);
        require(camera.exposure(.18).seconds() == manual.seconds(), "manual shutter remains fixed");
        require(Math.abs(camera.exposure(.18).ev() - manual.ev() - 1) < 1e-8, "meter measures stops");
        camera.mode = Z8Settings.Mode.A;
        require(camera.exposure(.045).seconds() > camera.exposure(.18).seconds(), "aperture priority");
        double before = camera.exposure(.09).seconds();
        camera.compensationThirds = 3;
        require(camera.exposure(.09).seconds() > before, "exposure compensation in aperture priority");
        camera.mode = Z8Settings.Mode.S;
        camera.shutter = java.util.Arrays.binarySearch(Z8Settings.SHUTTERS,1.0/125);
        camera.compensationThirds = 0;
        require(camera.exposure(.22).aperture() > camera.exposure(.06).aperture(), "shutter priority");
        var response = SensorFormat.Z8_FX.response(Z8PhotoSize.FULL);
        double middleGrey = .18*RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX*Z24120Optics.lightThroughput(16);
        var iso100 = RecordingMedia.Readout.forIso(100,true);
        var iso200 = RecordingMedia.Readout.forIso(200,true);
        double normal = response.expectedLinear(middleGrey,1,1.0/128,iso100);
        require(Math.abs(normal-.36) < .001,"one-stop Z8 daylight calibration");
        require(Math.abs(RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX/RecordingMedia.DAYLIGHT_WHITE_FLUX-Math.cbrt(2)) < 1e-10,
                "Z8 is one third of a stop above the previous calibration");
        require(Math.abs(response.expectedLinear(middleGrey,1,1.0/256,iso200)-normal) < .001,
                "one stop ISO compensates one stop shutter before clipping");
        require(Math.abs(response.expectedLinear(middleGrey*4,1,1.0/512,iso100)-normal) < .001,
                "doubling aperture diameter compensates four times faster shutter");
        double moonGrey = middleGrey*16/262144;
        require(response.expectedLinear(moonGrey,1,1.0/60,iso100) < .001,"f/4 ISO 100 1/60 underexposes moonlight");
        var iso64 = RecordingMedia.Readout.forIso(64,true);
        var iso400 = RecordingMedia.Readout.forIso(400,true);
        var iso500 = RecordingMedia.Readout.forIso(500,true);
        require(iso64.stage().baseIso == 64 && iso400.stage() == iso64.stage() && iso500.stage().baseIso == 500,
                "Z8 changes conversion gain at precisely ISO 500");
        require(iso500.inputReferredNoise() < .3*iso400.inputReferredNoise()
                && iso500.saturationElectrons() < iso400.saturationElectrons(),"high conversion gain trades headroom for lower read noise");
        require(Math.abs(iso500.signalVolts(100)/iso400.signalVolts(100)-1.25) < 1e-10,
                "no extra signal gain at the conversion switch");
        camera.mode = Z8Settings.Mode.M;
        camera.autoIso = true;
        camera.shutter = java.util.Arrays.binarySearch(Z8Settings.SHUTTERS,1.0/60);
        double autoLight = .18*16*60*100/(500*32768*RecordingMedia.Z8_SCENE_BRIGHTNESS);
        var auto = camera.exposure(autoLight);
        require(auto.iso() == 500 && auto.seconds() == 1.0/60 && auto.aperture() == 4,"manual exposure with auto ISO");
        require(camera.exposure(0).iso() == 25600 && camera.exposure(1).iso() == 64,"auto ISO bounds");
        camera.shutter = java.util.Arrays.binarySearch(Z8Settings.SHUTTERS,1.0/125);
        require(Math.abs(camera.exposure(autoLight).ev()) < .01 && camera.exposure(autoLight).iso() == 1042,
                "auto ISO holds the target between standard ISO steps after a shutter change");
        camera.aperture = java.util.Arrays.binarySearch(Z8Settings.APERTURES,8);
        var meteredThroughLens = (java.util.function.DoubleUnaryOperator)(f -> autoLight*Z24120Field.at(24,f).transmission(.7));
        require(Math.abs(camera.exposure(meteredThroughLens,false).ev()) < .01,
                "auto ISO holds the target after stopping down with aperture-dependent falloff");
        camera.compensationThirds = -3;
        require(Math.abs(camera.exposure(meteredThroughLens,false).ev()+1) < .01,
                "manual compensation moves the automatic target by the requested stop");
        camera.compensationThirds = 0;
        camera.setIsoExtension(true);
        require(camera.exposure(0).iso() == 102400,"auto ISO reaches the extended ceiling");
        camera.autoIso = false;
        camera.sensitivity = camera.isoCount()-1;
        require(camera.exposure(1).iso() == 102400,"manual ISO reaches the extended ceiling");
        camera.setIsoExtension(false);
        require(camera.iso() == 25600 && camera.exposure(1).iso() == 25600,"turning off extension clamps the current ISO");
        camera.sensitivity = java.util.Arrays.binarySearch(Z8Settings.ISOS,100);
        camera.autoIso = true;
        camera.mode = Z8Settings.Mode.A;
        require(camera.exposure(0).seconds() == 30,"aperture priority can slow down after reaching ISO limit");
        camera.focus(.2);
        require(camera.confirmed() && camera.distance() == .35, "focus near limit");
        camera.focus(Double.POSITIVE_INFINITY);
        require(!camera.confirmed() && camera.distance() == Double.POSITIVE_INFINITY, "focus fallback");
        for (int focal : Z8Settings.FOCALS) {
            camera.focal = focal;
            require(Math.abs(camera.focalLength()-focal) < .08, "Z8 zoom marks");
        }
        var tele = ZoomLens.Z100_400;
        camera.aperture = 0;
        camera.setLens(tele,100);
        camera.mode = Z8Settings.Mode.M;
        require(camera.exposure(.18).aperture()==4.5,"100–400 wide aperture");
        camera.focal = 300;
        require(camera.exposure(.18).aperture()==5.3,"100–400 intermediate aperture");
        camera.focal = 400;
        camera.focus(.1);
        var optics = camera.lensExposure(camera.exposure(.18).aperture());
        require(Math.abs(optics.focalLength()-400)<1e-9 && Math.abs(optics.fNumber()-5.6)<1e-9 && optics.focusMeters()==.98,
                "400 mm focal length, aperture and minimum focus");
        require(optics.defocusRadius(.98,1)<1e-8 && optics.defocusRadius(20,1)>.1,
                "100–400 focal plane and out-of-focus blur");
        require(tele.field(400,5.6).transmission(1)<tele.field(400,8).transmission(1),
                "100–400 corner shading recedes when stopped down");
        camera.setLens(ZoomLens.Z24_120,24);
        require(camera.lensExposure(4).lens == ZoomLens.Z24_120 && camera.lastAperture()==Z8Settings.APERTURES.length-1,
                "switching back restores the 24–120 profile");
    }

    private static void checkLiveViewMeter() {
        var matrix = Z8Settings.Meter.MATRIX;
        double uniform = LiveViewMeter.measure(matrix,.5,.5,1.5,(x,y) -> .025);
        double glint = LiveViewMeter.measure(matrix,.5,.5,1.5,
                (x,y) -> Math.abs(x-.5)<.025 && Math.abs(y-.5)<.025 ? 1 : .025);
        require(Math.abs(glint-uniform) < 1e-9,"matrix live meter ignores a small glint");
        require(LiveViewMeter.measure(matrix,.5,.5,1.5,(x,y) -> y<.35 ? .8 : .025) > .18,
                "matrix live meter sees broad lighting changes");
        double dark = LiveViewMeter.measure(Z8Settings.Meter.SPOT,.2,.5,1.5,(x,y) -> x<.5 ? .02 : .2);
        double light = LiveViewMeter.measure(Z8Settings.Meter.SPOT,.8,.5,1.5,(x,y) -> x<.5 ? .02 : .2);
        require(Math.abs(light/dark-10) < 1e-9,"spot follows the viewfinder focus point");
        var wide = Z24120Field.at(24,4);
        var tele = Z24120Field.at(120,4);
        var stopped = Z24120Field.at(24,8);
        require(wide.transmission(0)==1 && Math.abs(wide.transmission(1)-Math.pow(2,-2.1))<1e-9,
                "wide lens reproduces the table's relative illumination");
        require(stopped.transmission(1)>wide.transmission(1) && tele.transmission(1)>wide.transmission(1),
                "vignetting responds to focal length and aperture");
        require(wide.sourceScale(0)<1 && wide.sourceScale(1)==1 && tele.sourceScale(1)<1,
                "corrected residual distortion samples only the available image");
    }

    private static void checkZoomMtf() {
        var wide = new Z24120Mtf.Profile(24);
        var tele = new Z24120Mtf.Profile(120);
        var middle = new Z24120Mtf.Profile(72);
        for (var profile : new Z24120Mtf.Profile[]{wide,middle,tele}) {
            for (double height = 0; height <= 21.6; height += .25) {
                var kernel = profile.at(height);
                require(Math.abs(mtf(kernel,0,true)-1) < 1e-9,"MTF filter conserves constant scene luminance");
                require(mtf(kernel,10,true) >= mtf(kernel,30,true),"radial contrast falls from 10 to 30 lp/mm");
                require(mtf(kernel,10,false) >= mtf(kernel,30,false),"sagittal contrast falls from 10 to 30 lp/mm");
            }
        }
        require(Math.abs(mtf(wide.at(0),30,true)-.962) < .01,"wide center 30 lp/mm");
        require(Math.abs(mtf(wide.at(20),30,false)-.664) < .015,"wide sagittal 30 lp/mm at 20 mm");
        require(Math.abs(mtf(tele.at(20),10,true)-.861) < .015,"tele meridional 10 lp/mm at 20 mm");
        require(Math.abs(mtf(tele.at(20),30,true)-.291) < .015,"tele meridional 30 lp/mm at 20 mm");
        require(Math.abs(mtf(tele.at(20),30,false)-.560) < .015,"tele sagittal 30 lp/mm at 20 mm");
        double average = (mtf(wide.at(20),30,true)+mtf(tele.at(20),30,true))/2;
        require(Math.abs(mtf(middle.at(20),30,true)-average)<.015,"continuous zoom MTF interpolation");
    }

    private static double mtf(Z24120Mtf.Kernel kernel, double lpPerMm, boolean radial) {
        double response = 0;
        for (var tap : kernel.taps()) {
            double offset = radial ? tap.radialMm() : tap.tangentialMm();
            response += tap.weight()*Math.cos(Math.TAU*lpPerMm*offset);
        }
        return Math.abs(response);
    }

    private static void checkApertureResolution() {
        for (var lens : ZoomLens.values()) {
            for (int focal : new int[]{lens.wide,lens.tele}) {
                double wide = lens.wideOpen(focal);
                var open = new Z24120Mtf.Profile(lens,focal,wide);
                var stopped = new Z24120Mtf.Profile(lens,focal,8);
                require(mtf(stopped.at(20),30,true) > mtf(open.at(20),30,true),"stop-down reduces edge aberrations");
                require(mtf(stopped.at(0),30,true) > mtf(open.at(0),30,true),"stop-down changes center MTF");
                var moderate = Z24120Optics.focus(lens,focal,3,8);
                double focused = 1.0/moderate.focusMeters();
                double stoppedDown = Math.min(22,lens.apertures()[lens.lastAperture(focal)]);
                var small = Z24120Optics.focus(lens,focal,3,stoppedDown);
                require(small.spreadAtInverseDistance(focused,1) > 1.9*moderate.spreadAtInverseDistance(focused,1),
                        "diffraction at small apertures softens an in-focus plane");
                require(moderate.spreadAtInverseDistance(0,1) > moderate.spreadAtInverseDistance(focused,1),
                        "distant geometry blurs outside the focus plane");
            }
        }
    }

    private static void checkZoomSteps() {
        var standard = ZoomLens.Z24_120;
        var tele = ZoomLens.Z100_400;
        require(standard.snapFocal(35.5) == 36 && standard.stepFocal(119,1) == 120
                        && standard.stepFocal(24,-1) == 24,"24–120 moves in integer millimetres");
        for (int[] pair : new int[][]{{199,200},{200,205},{295,300},{300,310},{390,400}}) {
            require(tele.stepFocal(pair[0],1) == pair[1] && tele.stepFocal(pair[1],-1) == pair[0],
                    "100–400 zoom steps are reversible across segment boundaries");
        }
        require(tele.snapFocal(204) == 205 && tele.snapFocal(304) == 300 && tele.snapFocal(306) == 310
                        && tele.stepFocal(400,1) == 400 && tele.stepFocal(100,-1) == 100,
                "zoom rail snaps to the nearest valid mark and respects the end stops");
        require(ZoomLens.Z58_NOCT.stepFocal(58,1) == 58,"Noct remains fixed at 58 mm");
    }

    private static void checkOpticalCharacter() {
        var standard = ZoomLens.Z24_120;
        var longZoom = ZoomLens.Z100_400;
        require(Math.abs(standard.projectionFocal(120,1.28)/120-.97) < 1e-9
                        && Math.abs(longZoom.projectionFocal(200,2.09)/200-1.002) < 1e-9,
                "zoom lenses match measured focus breathing at the reported distances");
        var noct = Z24120Optics.focus(ZoomLens.Z58_NOCT,58,2,.95);
        require(noct.pupilClipping(0) == 0
                        && noct.pupilClipping(1) > Z24120Optics.focus(ZoomLens.Z58_NOCT,58,2,2).pupilClipping(1),
                "edge blur clipping grows toward the corner and recedes as the aperture closes");
        require(Z8Frame.pupilOffset(.5,.2,1,0,.5) < 0
                        && Z8Frame.pupilOffset(-.5,-.2,1,0,.5) > 0,
                "pupil clipping is radially oriented and centred");
        var camera = new Z8Settings();
        camera.setLens(longZoom,400);
        camera.focusLimit = true;
        camera.focus(1);
        require(camera.distance() == 3,"100–400 focus limiter preserves the 3 m AF boundary");
        camera.manualFocus(1);
        require(camera.distance() == 1,"100–400 manual distance can stay below the AF boundary");
    }

    private static void checkNoct() {
        var camera = new Z8Settings();
        var lens = ZoomLens.Z58_NOCT;
        camera.setLens(lens,58);
        require(!camera.usesAutofocus() && !lens.zoomable() && lens.apertureBlades() == 11,
                "Noct is an MF-only 11-blade prime");
        require(camera.exposure(.18).aperture() == .95
                        && Z8Settings.apertureLabel(.95).equals("F0.95")
                        && lens.apertures()[camera.lastAperture()] == 16,
                "Noct aperture controls cover f/0.95 to f/16");
        camera.manualFocus(.1);
        camera.focus(20);
        camera.touchFocus(6);
        require(camera.distance() == .5,"Noct clamps manual focus and ignores automatic focus commands");
        camera.manualFocus(.79);
        require(Math.abs(camera.projectionFocal()/58-1.16) < 1e-9,"Noct close-focus breathing");
        camera.manualFocus(2);
        var open = camera.lensExposure(.95);
        var closed = camera.lensExposure(2);
        require(open.defocusRadius(2,1) < 1e-8 && open.defocusRadius(2,2) > .01,
                "Noct has a clear focal plane with residual axial colour wide open");
        require(open.defocusRadius(1.99,2) > open.defocusRadius(1.99,1)
                        && open.defocusRadius(2.04,2) < open.defocusRadius(2.04,1),
                "Noct fringing changes colour with the subject's side of focus");
        require(closed.defocusRadius(2,2) < open.defocusRadius(2,2)*.12,
                "Noct axial colour recedes when stopped down");
        require(Math.abs((open.colourScale(2,1)-open.colourScale(0,1))*21.6-.009) < 1e-8
                        && open.colourScale(2,0) == open.colourScale(0,0),
                "Noct lateral colour is concentrated at the edge");
        require(Math.abs(lens.field(58,.95).transmission(1)-.25) < 1e-8
                        && lens.field(58,2).transmission(1) > .7,
                "Noct exhibits measured wide-open corner shading");
        camera.setLens(ZoomLens.Z24_120,24);
        require(camera.usesAutofocus(),"Noct preserves the camera's AF preference for compatible lenses");
    }

    private static void checkZoomDepthOfField() {
        require(Z24120Optics.elements() == 16 && Z24120Optics.groups() == 13,"illustrated lens train");
        var wide = Z24120Optics.focus(24,2,4);
        var open = Z24120Optics.focus(120,2,4);
        var closed = Z24120Optics.focus(120,2,8);
        var far = Z24120Optics.focus(120,5,4);
        var infinity = Z24120Optics.focus(120,Double.POSITIVE_INFINITY,4);
        var intermediate = Z24120Optics.focus(72.35,2,4);
        require(Math.abs(intermediate.focalLength()-72.35) < 1e-9
                && intermediate.defocusRadius(2,1) < 1e-9,"continuous zoom focus");
        require(Math.abs(open.entrancePupilDiameter()-open.focalLength()/4) < 1e-9,"entrance pupil diameter");
        require(Math.abs(open.throughput()/closed.throughput()-4) < 1e-9,"two-stop exposure ratio");
        require(open.defocusRadius(2,1) < 1e-9 && far.defocusRadius(5,1) < 1e-9
                && infinity.defocusRadius(Double.POSITIVE_INFINITY,1) < 1e-9,"focus planes");
        require(open.defocusRadius(5,1) > wide.defocusRadius(5,1)*20,"longer focal length and scene defocus");
        require(Math.abs(open.defocusRadius(5,1)/closed.defocusRadius(5,1)-2) < 1e-8,"stopping down halves geometric blur");
        require(open.depthOfField(.03).nearMeters() > closed.depthOfField(.03).nearMeters()
                && open.depthOfField(.03).farMeters() < closed.depthOfField(.03).farMeters(),"stopping down widens depth of field");
        for (var optics : new Z24120Optics.Exposure[]{wide,open,closed}) {
            var limits = optics.depthOfField(.03);
            System.out.printf("%.2f mm at 2 m: near %.3f m, far %.3f m at CoC 0.03 mm%n",
                    optics.focalLength(),limits.nearMeters(),limits.farMeters());
        }
    }

    private static void checkLensPrescriptions() {
        int[][] counts = {{16,13},{25,20},{17,10},{19,16},{16,11},{16,13}};
        for (var lens : ZoomLens.values()) {
            int[] count = counts[lens.ordinal()];
            require(Z24120Optics.elements(lens,lens.wide) == count[0]
                            && Z24120Optics.groups(lens,lens.wide) == count[1],
                    lens+" uses all illustrated lens elements and groups");
            double[] marks = lens.hasBuiltInConverter() ? new double[]{600,840}
                    : new double[]{lens.wide,(lens.wide+lens.tele)/2.0,lens.tele};
            for (double mark : marks) {
                double near = lens.minFocus(mark);
                double aperture = lens.wideOpen(mark);
                for (double distance : new double[]{near,near*3,Double.POSITIVE_INFINITY}) {
                    var exposure = Z24120Optics.focus(lens,mark,distance,aperture);
                    require(Math.abs(exposure.focalLength()-lens.opticalFocal(mark)) < 1e-8
                                    && exposure.defocusRadius(distance,1) < 1e-8
                                    && exposure.defocusRadius(near/2,1) > 0,
                            lens+" retains its focal length, focus plane and geometric blur");
                    for (int band = 0; band < 3; band++) {
                        require(Double.isFinite(exposure.defocusRadius(near*10,band))
                                        && exposure.colourScale(band,0) == 1
                                        && Math.abs(exposure.colourScale(band,1)-1) < .001,
                                lens+" has finite chromatic offsets limited to the image field");
                    }
                }
            }
        }
        var teleconverter = ZoomLens.Z600_TC;
        require(Z24120Optics.elements(teleconverter,840) == 26
                        && Z24120Optics.groups(teleconverter,840) == 20,
                "the 840 mm mode traces the seven-element converter");
        for (int focal : Z24120Optics.ZOOM_MARKS) {
            var standard = Z24120Optics.focus(focal,2,4);
            for (int band = 0; band < 3; band++) {
                require(standard.defocusRadius(2,band) <= .0028
                                && Math.abs(standard.colourScale(band,1)-1) < .000063,
                        "Z 24–120 axial and lateral colour stay within the low-fringing fit");
            }
        }
    }

    private static void checkSensorArea() {
        var sensor = SensorFormat.Z8_FX;
        double area = sensor.pixelArea();
        require(sensor.columns() == 8256 && sensor.rows() == 5504
                && Math.abs(Math.sqrt(area)*1000-4.35) < .02,"FX sensor geometry");
        var nativeSite = new RecordingMedia.SensorResponse(area,1);
        var largeSite = new RecordingMedia.SensorResponse(area*4,1);
        var grouped = new RecordingMedia.SensorResponse(area*4,4);
        require(largeSite.meanElectrons(3e5,1,1.0/60) == 4*nativeSite.meanElectrons(3e5,1,1.0/60),
                "four times the site area collects four times the signal");
        var n1 = new Random(10);
        var n2 = new Random(10);
        var gain = RecordingMedia.Readout.forIso(100,false);
        var one = measure(() -> nativeSite.code(3e5,1,1.0/60,gain,n1));
        var four = measure(() -> grouped.code(3e5,1,1.0/60,gain,n2));
        require(Math.abs(one.mean-four.mean) < 1 && four.standardDeviation < one.standardDeviation*.65,
                "averaging four independent readouts lowers relative noise at equal mean");
        System.out.printf("Native / four-site pooled noise: %.2f / %.2f ADC codes%n",
                one.standardDeviation,four.standardDeviation);
        var generic = RecordingMedia.Readout.forIso(100,false);
        var z8 = RecordingMedia.Readout.forIso(100,true);
        var prior = new Random(2019);
        var current = new Random(2019);
        var brightPrior = measure(() -> nativeSite.code(6e5,1,1.0/60,generic,prior));
        var brightZ8 = measure(() -> nativeSite.code(6e5,1,1.0/60,z8,current));
        require(Math.abs(brightPrior.mean-brightZ8.mean) < 1 && brightZ8.standardDeviation < .8*brightPrior.standardDeviation,
                "Z8 reduces bright photon noise while retaining the exposure calibration");
        System.out.printf("Bright reference / Z8 noise: %.2f / %.2f ADC codes%n",
                brightPrior.standardDeviation,brightZ8.standardDeviation);
    }

    private static double project(Optics.Camera c, double pupil, double distance, int band, Random rng) {
        Optics.Ray ray = c.trace(0, 0, pupil, 0, band, rng).ray();
        double front = c.backFocus() + c.lens().surfaces().getLast().offset();
        return ray.at((front + distance - ray.origin().z()) / ray.direction().z()).x();
    }

    private static double fieldFocus(Optics.Camera camera, double px, double py, Random random) {
        Optics.Ray chief = camera.trace(12, 0, 0, 0, 1, random).ray();
        Optics.Ray neighbour = camera.trace(12, 0, px, py, 1, random).ray();
        // Intersect the rays projected onto the tangential or sagittal plane.
        double chiefSlope = (px != 0 ? chief.direction().x() : chief.direction().y())
                / chief.direction().z();
        double slope = (px != 0 ? neighbour.direction().x() : neighbour.direction().y())
                / neighbour.direction().z();
        double chiefIntercept = (px != 0 ? chief.origin().x() : chief.origin().y())
                - chiefSlope * chief.origin().z();
        double intercept = (px != 0 ? neighbour.origin().x() : neighbour.origin().y())
                - slope * neighbour.origin().z();
        return (chiefIntercept - intercept) / (slope - chiefSlope);
    }

    private static void checkCmos() {
        var sample = new double[4 * 4 * 3];
        var dark = RecordingMedia.cmos(sample, 4, 4, 0.02, 100, new Random(19));
        java.util.Arrays.fill(sample, 100_000);
        var saturated = RecordingMedia.cmos(sample, 4, 4, 0.02, 100, new Random(19));
        require((dark.getRGB(2, 2) & 255) < 12, "CMOS dark frame");
        require((saturated.getRGB(2, 2) & 0xffffff) == 0xffffff, "CMOS saturation");
    }

    private record Moments(double mean, double standardDeviation) {}

    private static Moments measure(java.util.function.DoubleSupplier read) {
        int count = 5000;
        double sum = 0;
        double squares = 0;
        for (int i = 0; i < count; i++) {
            double value = read.getAsDouble();
            sum += value;
            squares += value * value;
        }
        double mean = sum / count;
        return new Moments(mean, Math.sqrt(squares / count - mean * mean));
    }

    private static void checkIso() {
        var rng = new Random(985);
        var low = measure(() -> RecordingMedia.cmosCode(1600, 1, rng));
        var high = measure(() -> RecordingMedia.cmosCode(1600, 8, rng));
        var matched = measure(() -> RecordingMedia.cmosCode(200, 8, rng));
        require(Math.abs(high.mean / low.mean - 8) < 0.1, "CMOS analog gain");
        require(RecordingMedia.cmosCode(6000, 8, rng) == RecordingMedia.ADC_MAX
                && RecordingMedia.cmosCode(6000, 1, rng) < RecordingMedia.ADC_MAX, "high ISO highlight clipping");
        require(Math.abs(matched.mean / low.mean - 1) < 0.02
                && matched.standardDeviation > 2 * low.standardDeviation, "CMOS matched brightness noise");

        var slow = measure(() -> RecordingMedia.developDensity(1, SLOW_FILM, rng));
        var fast = measure(() -> RecordingMedia.developDensity(1, FAST_FILM, rng));
        var matchedDensity = measure(() -> RecordingMedia.developDensity(0.125, FAST_FILM, rng));
        require(fast.mean > slow.mean + 0.8, "fast film needs less exposure");
        require(Math.abs(matchedDensity.mean - slow.mean) < 0.03
                && matchedDensity.standardDeviation > 1.5 * slow.standardDeviation,
                "fast film coarser grain at matched density");
        System.out.printf("Matched brightness noise (ADC codes, ISO 100 / 800): %.2f / %.2f%n",
                low.standardDeviation, matched.standardDeviation);
        System.out.printf("Matched density grain (optical density, ISO 100 / 800): %.3f / %.3f%n",
                slow.standardDeviation, matchedDensity.standardDeviation);
    }

    private static void renderIso(Path path) throws Exception {
        java.nio.file.Files.createDirectories(path);
        int width = 384;
        int height = 144;
        var photons = new double[width * height * 3];
        for (int i = 0; i < width * height; i++) {
            int step = (i % width) / (width / 8);
            double value = 180 * Math.pow(2, step);
            java.util.Arrays.fill(photons, 3 * i, 3 * i + 3, value);
        }
        writeIsoPair(path, "iso100", photons, width, height, 0.08, SLOW_FILM);
        writeIsoPair(path, "iso800-same-exposure", photons, width, height, 0.08, FAST_FILM);
        for (int i = 0; i < photons.length; i++) {
            photons[i] /= 8;
        }
        writeIsoPair(path, "iso800-shorter-exposure", photons, width, height, 0.01, FAST_FILM);
    }

    private static void writeIsoPair(Path path, String name, double[] photons, int width, int height,
                                     double seconds, RecordingMedia.FilmStock stock) throws Exception {
        var cmos = RecordingMedia.cmos(photons, width, height, seconds, stock.iso, new Random(19));
        var film = RecordingMedia.film(photons, width, height, stock, new Random(27));
        javax.imageio.ImageIO.write(cmos, "png", path.resolve(name + "-cmos.png").toFile());
        javax.imageio.ImageIO.write(film, "png", path.resolve(name + "-film.png").toFile());
    }

    private static void render(Optics.Camera camera, Path path) throws Exception {
        var capture = new Capture(camera, CameraCheck::chart, 192, 128, 160, 0.075, 42);
        capture.advance(192 * 128);
        capture.writeCmos(path,100);
    }

    private static double chart(Optics.Ray ray, int band) {
        Optics.Vector near = ray.at((240 - ray.origin().z()) / ray.direction().z());
        if (near.x() > -84 && near.x() < -19 && Math.abs(near.y()) < 42) {
            boolean line = Math.floorMod((int) Math.floor(near.x()), 6) < 2
                    || Math.floorMod((int) Math.floor(near.y()), 6) < 2;
            return (line ? 5e7 : 1e8) * (band == 0 ? 1 : 0.44);
        }
        Optics.Vector far = ray.at((1250 - ray.origin().z()) / ray.direction().z());
        double dx = far.x() - 36 * Math.rint(far.x() / 36);
        double dy = far.y() - 36 * Math.rint(far.y() / 36);
        return dx * dx + dy * dy < 23 ? 2.1e8 : 1.3e7;
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
