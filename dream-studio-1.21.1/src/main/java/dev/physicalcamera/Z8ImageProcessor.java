package dev.physicalcamera;

import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Integrates charge and stores the sensor mosaic before demosaicing. */
record Z8ImageProcessor(Z8PhotoSize size, short[] mosaic, int white) {
    static Z8ImageProcessor expose(Z8Frame frame, Z24120Optics.Exposure optics, Z8PhotoSize size,
                                   SensorFormat sensor, double seconds, int iso, long seed, AtomicInteger rowsDone,
                                   int samples, boolean openMount) {
        int width = size.width, height = size.height;
        short[] mosaic = new short[width*height];
        var opticsSampler = openMount ? frame.openMountSampler(size) : frame.sampler(optics,size,sensor,samples);
        var response = sensor.response(size);
        var readout = RecordingMedia.Readout.forIso(iso,optics != null || openMount);
        IntStream.range(0,height).parallel().forEach(y -> {
            var cmosNoise = new Random(seed+0x9e3779b97f4a7c15L*y);
            for (int x = 0; x < width; x++) {
                int index = y*width+x;
                int band = RecordingMedia.filter(x,y);
                mosaic[index] = (short)response.code(opticsSampler.sample(x,y,band),band,seconds,readout,cmosNoise);
            }
            rowsDone.incrementAndGet();
        });
        return new Z8ImageProcessor(size,mosaic,response.white());
    }

    BufferedImage cmos() {
        return RecordingMedia.demosaic(mosaic,size.width,size.height,true,white);
    }
}
