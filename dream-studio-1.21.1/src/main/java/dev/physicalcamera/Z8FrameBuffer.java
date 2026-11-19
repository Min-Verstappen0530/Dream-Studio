package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class Z8FrameBuffer implements AutoCloseable {
    private static final long PREVIEW_INTERVAL = 16_000_000;
    private static final long LIGHT_INTERVAL = 120_000_000;
    private static final int BLUR_TAPS = 8;
    private static final int RESOLUTION_STEP = 96;
    private static final double[][] APERTURE = Z8Frame.aperturePoints(BLUR_TAPS,9);
    private static final double[][] NOCT_APERTURE = Z8Frame.aperturePoints(BLUR_TAPS,11);
    private static final int[] TONES = java.util.stream.IntStream.rangeClosed(0,RecordingMedia.ADC_MAX)
            .map(i -> RecordingMedia.encodeSrgb(i/(double)RecordingMedia.ADC_MAX)).toArray();

    private record Sample(int width, int height, double aspect, double halfTan, int[] pixels, SceneRadiometry radiometry,
                          Z8Depth depth, long capturedAt) {}
    private record Transfer(double shutter, double aperture, int iso, Z8PhotoSize size, boolean openMount,
                            ZoomLens lens, double focal, double distance, double displayGain) {}
    private record MeterView(double aspect, SensorFormat sensor, Z8Settings.Meter mode, double focusU, double focusV,
                             boolean openMount, ZoomLens lens, double focal) {}
    private record Preview(Sample sample, Transfer transfer, int width, int height, int[] rgba,
                           ToneHistogram.Curve histogram, long processingNanos) {}
    private static final net.minecraft.resources.ResourceLocation PREVIEW = CameraEquipment.id("viewfinder_preview");
    private DynamicTexture texture;
    private Sample sample, displayed;
    private ToneHistogram.Curve histogram;
    private Sample metered;
    private MeterView meterView;
    private final Map<Double,Double> readings = new HashMap<>();
    private Transfer lastTransfer;
    private CompletableFuture<Sample> reading;
    private CompletableFuture<int[]> readingColour;
    private CompletableFuture<Z8Depth> readingDepth;
    private CompletableFuture<Preview> developing;
    private ForkJoinPool workers;
    private final ViewfinderTarget cropTarget = new ViewfinderTarget();
    private long lastCapture, lastLightReading;
    private SceneRadiometry light;
    private int previewWidth, previewLimit, slowFrames, fastFrames;
    private volatile long generation;

    private boolean needsSample(long now) {
        pollRead();
        return reading == null && now-lastCapture >= PREVIEW_INTERVAL;
    }

    boolean readyForFrame() { return needsSample(System.nanoTime()); }

    void copy(RenderTarget target, double halfTan, float farMetres, Minecraft client, double focal, Z8PhotoSize size, int maxWidth) {
        long time = System.nanoTime();
        if (!needsSample(time) || client.level == null) return;
        double aspect = target.width/(double)target.height;
        var crop = focal > 0 ? Z8Frame.crop(halfTan,aspect,focal,size.aspect(),size.sensor())
                : Z8Frame.aspectCrop(aspect,size.aspect());
        var region = Z8ReadRegion.centered(target.width,target.height,crop,size);
        double croppedAspect = region.width()/(double)region.height();
        double croppedHalfTan = halfTan*region.height()/target.height;
        if (previewLimit != maxWidth) {
            previewLimit = previewWidth = maxWidth;
            slowFrames = fastFrames = 0;
        }
        if (light == null || time-lastLightReading >= LIGHT_INTERVAL) {
            light = SceneRadiometry.measurePreview(client.level,client.gameRenderer.getMainCamera(),croppedHalfTan,croppedAspect);
            lastLightReading = time;
        }
        var radiometry = light;
        lastCapture = time;
        try {
            int width = Math.min(region.width(),previewWidth);
            int height = Math.max(1,(int)Math.round(width/croppedAspect));
            var depthView = focal > 0 ? IrisCompatibility.depthViewport() : Z8Depth.Viewport.FULL;
            var resized = cropTarget.copy(target,region,width,height,focal > 0,depthView);
            var source = resized == null ? target : resized;
            var area = resized == null ? region : new Z8ReadRegion(0,0,width,height);
            readingColour = FrameReadback.colour(source,area);
            readingDepth = focal > 0 ? Z8Depth.capture(source,farMetres,area,
                    resized == null ? depthView : Z8Depth.Viewport.FULL) : CompletableFuture.completedFuture(null);
            var depth = readingDepth.exceptionally(error -> {
                if (!(error instanceof CancellationException)) log(error);
                return null;
            });
            reading = readingColour.thenCombine(depth,(pixels,map) -> new Sample(
                    area.width(),area.height(),croppedAspect,croppedHalfTan,pixels,radiometry,map,time));
        } catch (RuntimeException error) {
            if (readingColour != null) readingColour.cancel(false);
            readingColour = null;
            log(error);
        }
    }

    private void pollRead() {
        if (reading == null || !reading.isDone()) return;
        try { sample = reading.join(); }
        catch (CompletionException error) { log(error); }
        finally { reading = null; readingColour = null; readingDepth = null; }
    }

    void prepareMeter() { pollRead(); }

    double meter(Z8PhotoSize size, Z8Settings.Meter mode, double focusU, double focusV,
                 boolean openMount, ZoomLens lens, double focal, double aperture) {
        if (sample == null) return .18;
        var view = new MeterView(size.aspect(),size.sensor(),mode,focusU,focusV,openMount,lens,focal);
        if (metered != sample || !view.equals(meterView)) {
            metered = sample;
            meterView = view;
            readings.clear();
        }
        return readings.computeIfAbsent(aperture,stop -> readMeter(metered,view,stop));
    }

    private static double readMeter(Sample source, MeterView view, double aperture) {
        var crop = Z8Frame.aspectCrop(source.aspect(),view.aspect());
        if (view.openMount()) {
            var diffuse = openRadiance(source);
            double light = RecordingMedia.meterLuma(diffuse[0],diffuse[1],diffuse[2]);
            double center = Z24120Optics.lightThroughput(OpenMount.equivalentAperture(view.sensor()));
            return LiveViewMeter.measure(view.mode(),view.focusU(),view.focusV(),view.aspect(),
                    (u,v) -> light*OpenMount.throughput(view.sensor(),u,v)/center);
        }
        var lens = view.lens().field(view.focal(),aperture);
        return LiveViewMeter.measure(view.mode(),view.focusU(),view.focusV(),view.aspect(),(u,v) -> {
            double x = 2*u-1, y = 2*v-1;
            double radius = Z24120Field.radiusSquared(x,y,view.aspect());
            double scale = lens.sourceScale(radius);
            double sx = .5+x*crop.halfWidth()*scale, sy = .5+y*crop.halfHeight()*scale;
            return lens.transmission(radius)*source.radiometry().scale()
                    *view.lens().transmission()/ZoomLens.Z24_120.transmission()
                    *RecordingMedia.meterLuma(linear(source,sx,sy,0),linear(source,sx,sy,1),linear(source,sx,sy,2));
        });
    }

    boolean hasMeterSample() { return sample != null; }

    private static double linear(Sample source, double u, double v, int band) {
        return Z8Frame.interpolateRgb(source.width(),source.height(),source.pixels(),u,v,band);
    }

    private static double[] openRadiance(Sample source) {
        return OpenMount.average((u,v,band) -> source.radiometry().scale()
                *Z8Frame.linearRgb(source.pixels()[(int)(v*source.height())*source.width()+(int)(u*source.width())],band));
    }

    void draw(GuiGraphics g, int x, int y, int w, int h, Z8PhotoSize size,
              Z8Settings.Exposure exposure, boolean openMount, ZoomLens lens, double focal, double distance,
              int workerLimit, boolean starlightView, boolean showHistogram) {
        // Starlight changes only the live display gain, independently of capture exposure.
        double displayGain = starlightView ? Math.pow(2,Math.clamp(-exposure.ev()-1,0,12)) : 1;
        var transfer = new Transfer(exposure.seconds(),exposure.aperture(),exposure.iso(),size,openMount,lens,focal,distance,displayGain);
        if (developing != null && developing.isDone()) {
            try { upload(developing.join()); }
            catch (CompletionException error) { log(error); }
            finally { developing = null; }
        }
        if (!showHistogram) histogram = null;
        if (sample != null && developing == null
                && (displayed != sample || !transfer.equals(lastTransfer) || showHistogram && histogram == null)) {
            var current = sample;
            long activeGeneration = generation;
            int widthLimit = previewWidth;
            if (workers == null || workers.getParallelism() != workerLimit) {
                if (workers != null) workers.shutdown();
                workers = new ForkJoinPool(workerLimit,pool -> {
                    var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    worker.setPriority(Thread.NORM_PRIORITY-1);
                    return worker;
                },null,false);
            }
            developing = CompletableFuture.supplyAsync(() -> develop(current,transfer,widthLimit,showHistogram,activeGeneration),workers);
        }
        if (texture == null) return;
        var crop = Z8Frame.aspectCrop(displayed.aspect(),size.aspect());
        float u = (float)crop.halfWidth(), v = (float)crop.halfHeight();
        int texWidth = texture.getPixels().getWidth(), texHeight = texture.getPixels().getHeight();
        g.blit(PREVIEW,x,y,w,h,(.5f-u)*texWidth,(.5f-v)*texHeight,
                Math.round(2*u*texWidth),Math.round(2*v*texHeight),texWidth,texHeight);
    }

    ToneHistogram.Curve histogram() { return histogram; }

    private void upload(Preview preview) {
        // Native pixels belong to the render thread. Cancelled workers can only
        // keep touching their Java array, never a texture that has been closed.
        if (texture == null || texture.getPixels().getWidth() != preview.width()
                || texture.getPixels().getHeight() != preview.height()) {
            var replacement = new DynamicTexture(preview.width(),preview.height(),false);
            try {
                uploadPixels(replacement,preview.rgba());
            } catch (RuntimeException | Error error) {
                replacement.close();
                throw error;
            }
            Minecraft.getInstance().getTextureManager().register(PREVIEW,replacement);
            replacement.setFilter(true,false);
            texture = replacement;
        } else {
            uploadPixels(texture,preview.rgba());
        }
        lastTransfer = preview.transfer();
        displayed = preview.sample();
        histogram = preview.histogram();
        tuneResolution(preview);
    }

    private void tuneResolution(Preview preview) {
        // Hysteresis avoids repeated texture reallocations when frame times fluctuate.
        if (preview.processingNanos() > 18_000_000 && preview.width() >= previewWidth) {
            fastFrames = 0;
            if (++slowFrames >= 4) {
                previewWidth = Math.max(previewLimit/2,previewWidth-RESOLUTION_STEP);
                slowFrames = 0;
            }
        } else if (preview.processingNanos() < 9_000_000 && previewWidth < previewLimit) {
            slowFrames = 0;
            if (++fastFrames >= 45) {
                previewWidth = Math.min(previewLimit,previewWidth+RESOLUTION_STEP);
                fastFrames = 0;
            }
        } else {
            slowFrames = fastFrames = 0;
        }
    }

    private static void uploadPixels(DynamicTexture texture, int[] rgba) {
        // Each int stores R, G, B, A from most to least significant byte.
        var pixels = texture.getPixels();
        int width = pixels.getWidth();
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < width; x++) {
                int pixel = rgba[y*width+x];
                pixels.setPixelRGBA(x,y,Integer.reverseBytes(pixel));
            }
        }
        texture.upload();
    }

    private Preview develop(Sample sample, Transfer transfer, int maxWidth, boolean showHistogram, long activeGeneration) {
        if (activeGeneration != generation) throw new CancellationException();
        int width = Math.min(sample.width(),maxWidth);
        int height = Math.max(1,(int)Math.round(width/sample.aspect()));
        int[] rgba = new int[width*height];
        long started = System.nanoTime();
        developPixels(sample,transfer,width,height,rgba,activeGeneration);
        if (activeGeneration != generation) throw new CancellationException();
        var tones = showHistogram ? ToneHistogram.live(width,height,rgba) : null;
        return new Preview(sample,transfer,width,height,rgba,tones,System.nanoTime()-started);
    }

    private void developPixels(Sample sample, Transfer transfer, int width, int height, int[] rgba,
                               long activeGeneration) {
        var format = transfer.size().sensor();
        var sensor = format.response(transfer.size());
        double base = RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX*Z24120Optics.lightThroughput(transfer.lens(),transfer.aperture());
        var readout = RecordingMedia.Readout.forIso(transfer.iso(),true);
        var diffuse = transfer.openMount() ? openRadiance(sample) : null;
        var crop = Z8Frame.aspectCrop(sample.aspect(),transfer.size().aspect());
        var lens = transfer.lens().field(transfer.focal(),transfer.aperture());
        var optics = diffuse == null ? Z24120Optics.focus(
                transfer.lens(),transfer.focal(),transfer.distance(),transfer.aperture()) : null;
        double pixelsPerMm = sample.width()*2*crop.halfWidth()/format.gateWidth(transfer.size().aspect());
        double footprint = Math.max(1,sample.width()/(double)width);
        double[] transmission = IntStream.rangeClosed(0,512).mapToDouble(i -> lens.transmission(i/512.0)).toArray();
        double sceneFlux = base*sample.radiometry().scale();
        double seconds = transfer.shutter()*transfer.displayGain();
        boolean noct = optics != null && transfer.lens() == ZoomLens.Z58_NOCT;
        double[][] pupil = noct ? NOCT_APERTURE : APERTURE;
        var depth = optics == null ? null : sample.depth();
        var edges = depth == null ? null : new Z8DepthEdge(depth);
        IntStream.range(0,height).parallel().forEach(y -> {
            if (activeGeneration != generation) return;
            double v = (y+.5)/height;
            double fy = (v-.5)/crop.halfHeight();
            for (int x = 0; x < width; x++) {
                double u = (x+.5)/width;
                double fx = (u-.5)/crop.halfWidth();
                double radius = Z24120Field.radiusSquared(fx,fy,transfer.size().aspect());
                double scale = lens.sourceScale(radius);
                double sx = .5+(u-.5)*scale, sy = .5+(v-.5)*scale;
                int index = depth == null ? -1 : depth.indexAt(sx,sy);
                double inverse = optics == null ? 0 : depth == null ? 1/transfer.distance() : depth.inverseMetres()[index];
                var edge = edges == null ? null : edges.split(index,sx,sy,scale/width,scale/height);
                double blur = optics == null ? 0 : optics.spreadAtInverseDistance(inverse,1)*pixelsPerMm;
                double flux = diffuse == null ? sceneFlux*transmission[(int)Math.round(radius*512)]
                        : RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX*OpenMount.throughput(format,.5+fx/2,.5+fy/2);
                double radialX = 1, radialY = 0, pupilClip = 0;
                if (edge != null || blur >= .65*footprint && optics.radiusAtInverseDistance(inverse,1)*pixelsPerMm > .65*footprint) {
                    double across = u-.5, down = (v-.5)/sample.aspect();
                    double fieldDistance = Math.sqrt(across*across+down*down);
                    if (fieldDistance > 0) {
                        radialX = across/fieldDistance;
                        radialY = down/fieldDistance;
                        pupilClip = optics.pupilClipping(radius);
                    }
                }
                int rgb = 0;
                for (int band = 0; band < 3; band++) {
                    double colourScale = noct ? optics.colourScale(band,radius) : 1;
                    double bandBlur = noct && band != 1 ? optics.spreadAtInverseDistance(inverse,band)*pixelsPerMm : blur;
                    double uBand = .5+(sx-.5)*colourScale, vBand = .5+(sy-.5)*colourScale;
                    double radiance = diffuse == null
                            ? filtered(sample,uBand,vBand,band,inverse,bandBlur,footprint,pupil,
                                    radialX,radialY,pupilClip,edges,edge == null ? null : edge.centre()) : diffuse[band];
                    if (edge != null && edge.neighbour() != null) {
                        var layer = edge.neighbour();
                        double otherDepth = layer.inverseDepth();
                        double otherRadius = optics.spreadAtInverseDistance(otherDepth,noct ? band : 1)*pixelsPerMm;
                        double otherClip = optics.radiusAtInverseDistance(otherDepth,1)*pixelsPerMm > .65*footprint ? pupilClip : 0;
                        double other = filtered(sample,uBand,vBand,band,otherDepth,otherRadius,footprint,
                                pupil,radialX,radialY,otherClip,edges,layer);
                        radiance = Math.fma(other-radiance,edge.neighbourCoverage(),radiance);
                    }
                    double linear = sensor.expectedLinear(flux*radiance,band,seconds,readout);
                    rgb = (rgb<<8)|TONES[(int)Math.round(linear*RecordingMedia.ADC_MAX)];
                }
                rgba[y*width+x] = rgb<<8 | 0xff;
            }
        });
    }

    private static double filtered(Sample sample, double x, double y, int band,
                                   double inverse, double radius, double footprint, double[][] pupil,
                                   double radialX, double radialY, double pupilClip, Z8DepthEdge edges,
                                   Z8DepthEdge.Layer layer) {
        double sum = layer == null ? resample(sample,x,y,band,footprint)
                : onSurface(sample,x,y,band,inverse,layer.u(),layer.v());
        if (radius < .65*footprint) return sum;
        radius = Math.min(12*footprint,radius);
        int count = 1;
        double threshold = Math.max(.0015,inverse*.18);
        for (int i = 0; i < BLUR_TAPS; i++) {
            double px = pupil[0][i], py = pupil[1][i];
            double inward = Z8Frame.pupilOffset(px,py,radialX,radialY,pupilClip);
            double dx = (px+radialX*inward)*radius, dy = (py+radialY*inward)*radius;
            double u = x+dx/sample.width(), v = y+dy/sample.height();
            if (u < 0 || u > 1 || v < 0 || v > 1) continue;
            // Keep the small live filter on the same surface, especially around leaf edges.
            int index = edges == null ? -1 : sample.depth().indexAt(u,v);
            double sampleDepth = edges == null ? inverse : sample.depth().inverseMetres()[index];
            if (Math.abs(sampleDepth-inverse) > threshold) continue;
            sum += edges == null || !edges.near(index) ? linear(sample,u,v,band)
                    : onSurface(sample,u,v,band,sampleDepth,u,v);
            count++;
        }
        return sum/count;
    }

    private static double onSurface(Sample sample, double u, double v, int band,
                                    double inverse, double fallbackU, double fallbackV) {
        return Z8Frame.interpolateOnSurface(sample.width(),sample.height(),sample.pixels(),null,sample.depth(),
                u,v,band,inverse,fallbackU,fallbackV);
    }

    private static double resample(Sample sample, double u, double v, int band, double footprint) {
        if (footprint < 1.25) return linear(sample,u,v,band);
        double dx = footprint/(4*sample.width()), dy = footprint/(4*sample.height());
        return .25*(linear(sample,u-dx,v-dy,band)+linear(sample,u+dx,v-dy,band)
                +linear(sample,u-dx,v+dy,band)+linear(sample,u+dx,v+dy,band));
    }

    net.minecraft.world.phys.Vec3 focusRay(net.minecraft.client.Camera camera, double u, double v,
                                         Z8PhotoSize size, double fallbackAspect) {
        double aspect = displayed == null ? fallbackAspect : displayed.aspect();
        double tangent = displayed == null ? Math.tan(Math.toRadians(CameraRender1211.worldFov()/2)) : displayed.halfTan();
        var crop = Z8Frame.aspectCrop(aspect,size.aspect());
        double scale = lastTransfer == null ? 1 : lastTransfer.lens().field(lastTransfer.focal(),lastTransfer.aperture())
                .sourceScale(Z24120Field.radiusSquared(2*u-1,2*v-1,size.aspect()));
        double right = (u-.5)*4*crop.halfWidth()*tangent*aspect*scale;
        double up = (.5-v)*4*crop.halfHeight()*tangent*scale;
        var front = camera.getLookVector();
        var left = camera.getLeftVector();
        var top = camera.getUpVector();
        return new net.minecraft.world.phys.Vec3(front.x()-left.x()*right+top.x()*up,
                front.y()-left.y()*right+top.y()*up,front.z()-left.z()*right+top.z()*up).normalize();
    }

    double displayedFocusDistance(double u, double v, Z8PhotoSize size, ZoomLens lens, double focal) {
        if (displayed == null || displayed.depth() == null || lastTransfer.lens() != lens || lastTransfer.focal() != focal
                || System.nanoTime()-displayed.capturedAt() > 250_000_000) return Double.POSITIVE_INFINITY;
        double scale = lens.field(focal,lastTransfer.aperture())
                .sourceScale(Z24120Field.radiusSquared(2*u-1,2*v-1,size.aspect()));
        var crop = Z8Frame.aspectCrop(displayed.aspect(),size.aspect());
        return 1/displayed.depth().at(.5+(2*u-1)*crop.halfWidth()*scale,.5+(2*v-1)*crop.halfHeight()*scale);
    }

    @Override public void close() {
        generation++;
        reading = null;
        if (readingColour != null) readingColour.cancel(false);
        readingColour = null;
        if (readingDepth != null) readingDepth.cancel(false);
        readingDepth = null;
        if (developing != null) developing.cancel(false);
        developing = null;
        if (workers != null) workers.shutdown();
        workers = null;
        lastCapture = lastLightReading = 0;
        light = null;
        previewLimit = previewWidth = slowFrames = fastFrames = 0;
        cropTarget.close();
        sample = displayed = null;
        histogram = null;
        metered = null;
        meterView = null;
        readings.clear();
        lastTransfer = null;
        if (texture != null) Minecraft.getInstance().getTextureManager().release(PREVIEW);
        texture = null;
    }

    private static void log(Throwable error) {
        org.slf4j.LoggerFactory.getLogger("physical_camera").debug("Live view processing failed",error);
    }
}
