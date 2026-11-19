package dev.physicalcamera;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;

final class Capture {
    @FunctionalInterface
    interface Scene {
        // Photon radiance integrated over a band, in photons / (s mm² sr).
        double radiance(Optics.Ray ray, int band);
    }

    @FunctionalInterface
    interface Sampler {
        void sample(int x, int y, Random random, double[] exposure, int offset);
    }

    private final Sampler sampler;
    private final int width, height;
    private final double seconds, sampleArea;
    private final double[] exposure;
    private final Random random;
    private final long seed;
    private int pixel;

    Capture(Optics.Camera camera, Scene scene, int width, int height, int samples,
            double seconds, long seed) {
        this(worldSampler(camera,scene,width,height,samples),width,height,seconds,seed,
                Optics.SENSOR_WIDTH*Optics.SENSOR_HEIGHT/(width*height));
    }

    private Capture(Sampler sampler, int width, int height, double seconds, long seed, double sampleArea) {
        this.sampler = sampler;
        this.width = width;
        this.height = height;
        this.seconds = seconds;
        this.seed = seed;
        this.sampleArea = sampleArea;
        exposure = new double[width*height*3];
        random = new Random(seed);
    }

    boolean advance(int pixelBudget) {
        int end = Math.min(width*height,pixel+pixelBudget);
        for (; pixel < end; pixel++) {
            expose(pixel,random);
        }
        return pixel == width*height;
    }

    private void expose(int index, Random rng) {
        sampler.sample(index%width,index/width,rng,exposure,3*index);
        for (int band = 0; band < 3; band++) {
            exposure[3*index+band] *= seconds*sampleArea;
        }
    }

    private static Sampler worldSampler(Optics.Camera camera, Scene scene, int width, int height, int samples) {
        return (col,row,rng,photons,offset) -> {
            for (int sample = 0; sample < samples; sample++) {
                // The lens inverts the sensor image. Orient coordinates for the PNG.
                double x = (.5-(col+rng.nextDouble())/width)*Optics.SENSOR_WIDTH;
                double y = ((row+rng.nextDouble())/height-.5)*Optics.SENSOR_HEIGHT;
                double radius = camera.stopRadius()*Math.sqrt(rng.nextDouble());
                double theta = rng.nextDouble()*Math.TAU;
                double px = radius*Math.cos(theta), py = radius*Math.sin(theta);
                for (int band = 0; band < 3; band++) {
                    Optics.Trace trace = camera.trace(x,y,px,py,band,rng);
                    if (trace != null) {
                        photons[offset+band] += trace.throughput()*scene.radiance(trace.ray(),band);
                    }
                }
            }
            for (int band = 0; band < 3; band++) {
                photons[offset+band] /= samples;
            }
        };
    }

    void writeCmos(Path directory, int cmosIso) throws IOException {
        Files.createDirectories(directory);
        writeCmosFiles(directory.resolve("cmos.png"),directory.resolve("capture.dng"),cmosIso);
    }

    void writeCmosFiles(Path png, Path dng, int cmosIso) throws IOException {
        if (pixel != width*height) {
            throw new IllegalStateException("Exposure is still in progress.");
        }
        var mosaic = RecordingMedia.cmosMosaic(exposure,width,height,seconds,cmosIso,new Random(seed ^ 19));
        writeImage(RecordingMedia.demosaic(mosaic,width,height,false),png);
        RawDng.write(dng,width,height,mosaic);
    }

    static void writeImage(BufferedImage image, Path path) throws IOException {
        if (!ImageIO.write(image,"png",path.toFile())) {
            throw new IOException("No PNG writer available.");
        }
    }
}
