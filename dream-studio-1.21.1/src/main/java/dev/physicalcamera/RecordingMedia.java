package dev.physicalcamera;

import java.awt.image.BufferedImage;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.Random;

final class RecordingMedia {
    private static final double[] QUANTUM_EFFICIENCY = {0.53, 0.61, 0.46};
    private static final double[] GRAIN_SENSITIVITY = {0.35, 1, 2.4};
    private static final double FULL_WELL_E = 20_000;
    private static final double VOLTS_PER_ELECTRON = 40e-6;
    private static final double INPUT_READ_NOISE_E = 2.4;
    private static final double ADC_READ_NOISE_VOLTS = 72e-6;
    private static final double ADC_RANGE_VOLTS = 0.8;
    static final int ADC_MAX = RawDng.WHITE;
    static final int BASE_ISO = 100;
    static final int MAX_ISO = 3200;
    private static final double Z8_ELECTRON_SCALE = 2;

    // The rendered scene's luminance calibration is lifted by two thirds of a stop.
    static final double SCENE_BRIGHTNESS = Math.pow(2,2.0/3);
    static final double Z8_SCENE_BRIGHTNESS = SCENE_BRIGHTNESS*Math.cbrt(2);
    static final double DAYLIGHT_WHITE_FLUX = SCENE_BRIGHTNESS*FULL_WELL_E*32768
            /(Z24120Optics.lightThroughput(1)*QUANTUM_EFFICIENCY[1]);
    static final double Z8_DAYLIGHT_WHITE_FLUX = DAYLIGHT_WHITE_FLUX*(Z8_SCENE_BRIGHTNESS/SCENE_BRIGHTNESS);

    static double meterLuma(double red, double green, double blue) {
        return (.2126*red*QUANTUM_EFFICIENCY[0]+.7152*green*QUANTUM_EFFICIENCY[1]
                +.0722*blue*QUANTUM_EFFICIENCY[2])/QUANTUM_EFFICIENCY[1];
    }

    /** Equivalent readout stages. Voltages and capacities are model parameters, not a circuit schematic. */
    enum ConversionStage {
        GENERIC(100,FULL_WELL_E,VOLTS_PER_ELECTRON,INPUT_READ_NOISE_E,ADC_READ_NOISE_VOLTS),
        Z8_LOW(64,62_500,12.8e-6,13.4,45e-6),
        Z8_HIGH(500,16_000,64e-6,3.0,45e-6);

        final int baseIso;
        final double capacity, voltsPerElectron, inputNoise, adcNoise;

        ConversionStage(int baseIso, double capacity, double voltsPerElectron, double inputNoise, double adcNoise) {
            this.baseIso = baseIso;
            this.capacity = capacity;
            this.voltsPerElectron = voltsPerElectron;
            this.inputNoise = inputNoise;
            this.adcNoise = adcNoise;
        }
    }

    record Readout(ConversionStage stage, double analogGain) {
        static Readout forIso(int iso, boolean z8) {
            var stage = !z8 ? ConversionStage.GENERIC
                    : iso >= ConversionStage.Z8_HIGH.baseIso ? ConversionStage.Z8_HIGH : ConversionStage.Z8_LOW;
            return new Readout(stage,VOLTS_PER_ELECTRON*iso/(BASE_ISO*stage.voltsPerElectron*electronScale(stage)));
        }

        private static double electronScale(ConversionStage stage) {
            return stage == ConversionStage.GENERIC ? 1 : Z8_ELECTRON_SCALE;
        }

        double electronScale() { return electronScale(stage); }

        double signalVolts(double electrons) { return electrons*stage.voltsPerElectron*analogGain; }

        double inputReferredNoise() { return Math.hypot(stage.inputNoise,stage.adcNoise/signalVolts(1)); }

        double saturationElectrons() { return Math.min(stage.capacity,ADC_RANGE_VOLTS/signalVolts(1)); }
    }

    enum FilmStock {
        ISO_100(100, 64),
        ISO_200(200, 48),
        ISO_400(400, 32),
        ISO_800(800, 22),
        ISO_1600(1600, 15),
        ISO_3200(3200, 10);

        final int iso;
        final int grainsPerGroup;

        FilmStock(int iso, int grainsPerGroup) {
            this.iso = iso;
            this.grainsPerGroup = grainsPerGroup;
        }

        static FilmStock byIso(int iso) {
            for (FilmStock stock : values()) {
                if (stock.iso == iso) {
                    return stock;
                }
            }
            throw new IllegalArgumentException("胶片 ISO 可选 100、200、400、800、1600、3200。");
        }
    }

    static final class FilmSampler {
        private static final int PROBABILITY_STEPS = 4096;
        private final FilmStock stock;
        private final double[][] cdf;
        private final byte[] tones;

        FilmSampler(FilmStock stock) {
            this.stock = stock;
            int trials = stock.grainsPerGroup;
            cdf = new double[PROBABILITY_STEPS+1][trials+1];
            for (int i = 0; i <= PROBABILITY_STEPS; i++) {
                double p = i/(double)PROBABILITY_STEPS;
                double[] row = cdf[i];
                if (i < PROBABILITY_STEPS) {
                    double mass = Math.pow(1-p,trials);
                    row[0] = mass;
                    for (int k = 1; k < trials; k++) {
                        mass *= (trials-k+1)*p/(k*(1-p));
                        row[k] = Math.min(1,row[k-1]+mass);
                    }
                }
                row[trials] = 1;
            }
            int grains = trials*GRAIN_SENSITIVITY.length;
            tones = new byte[grains+1];
            for (int n = 0; n <= grains; n++) {
                tones[n] = (byte)encodeSrgb(Math.pow(10,-.10-1.9*n/grains));
            }
        }

        byte tone(double absorbed, Random random) {
            double exposure = absorbed*stock.iso/BASE_ISO;
            int developed = 0;
            for (double sensitivity : GRAIN_SENSITIVITY) {
                double probability = developmentProbability(exposure*sensitivity);
                int index = (int)Math.round(PROBABILITY_STEPS*probability);
                int result = Arrays.binarySearch(cdf[index],random.nextDouble());
                developed += result >= 0 ? result : -result-1;
            }
            return tones[developed];
        }
    }

    static BufferedImage cmos(double[] photons, int width, int height, double seconds, int iso,
                              Random random) {
        return demosaic(cmosMosaic(photons,width,height,seconds,iso,random),width,height,false);
    }

    static short[] cmosMosaic(double[] photons, int width, int height, double seconds, int iso, Random random) {
        double analogGain = iso / (double) BASE_ISO;
        short[] mosaic = new short[width*height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y*width+x;
                int band = filter(x,y);
                mosaic[i] = (short)cmosPhotosite(photons[3*i+band],band,seconds,analogGain,random);
            }
        }
        return mosaic;
    }

    static BufferedImage demosaic(short[] mosaic, int width, int height, boolean parallel) {
        return demosaic(mosaic,width,height,parallel,ADC_MAX);
    }

    static BufferedImage demosaic(short[] mosaic, int width, int height, boolean parallel, int white) {
        var raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT,width,height,3,null);
        var color = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),false,false,
                Transparency.OPAQUE,DataBuffer.TYPE_USHORT);
        short[] pixels = ((java.awt.image.DataBufferUShort)raster.getDataBuffer()).getData();
        short[] srgb = new short[white+1];
        for (int code = 0; code <= white; code++) {
            srgb[code] = (short)Math.round(65535.0*encodeSrgb(code/(double)white,white)/white);
        }
        var rows = java.util.stream.IntStream.range(0,height);
        (parallel ? rows.parallel() : rows).forEach(y -> {
            for (int x = 0; x < width; x++) {
                for (int band = 0; band < 3; band++) {
                    int code = (int)Math.round(interpolate(mosaic,width,height,x,y,band));
                    pixels[(y*width+x)*3+band] = srgb[code];
                }
            }
        });
        return new BufferedImage(color,raster,false,null);
    }

    static int cmosCode(double meanElectrons, double analogGain, Random random) {
        double electrons = Math.min(photonCount(meanElectrons, random), FULL_WELL_E);
        double inputVolts = (electrons + INPUT_READ_NOISE_E * random.nextGaussian()) * VOLTS_PER_ELECTRON;
        double adcVolts = analogGain * inputVolts + ADC_READ_NOISE_VOLTS * random.nextGaussian();
        return (int) Math.round(ADC_MAX * Math.clamp(adcVolts / ADC_RANGE_VOLTS, 0, 1));
    }

    static int cmosPhotosite(double photons, int band, double seconds, double gain, Random random) {
        return cmosCode(photons*QUANTUM_EFFICIENCY[band]+4*seconds,gain,random);
    }

    /** Shot noise and charge capacity follow area; read noise combines across independent readouts. */
    record SensorResponse(double collectionArea, double readouts, int white,
                          double siteCalibration, double noiseScale) {
        private static final double REFERENCE_AREA = SensorFormat.Z8_FX.pixelArea();

        SensorResponse(double collectionArea, double readouts) { this(collectionArea,readouts,RawDng.BITS); }

        SensorResponse(double collectionArea, double readouts, int bits) {
            // Match ISO brightness across formats while retaining native shot and input read noise.
            this(collectionArea,readouts,(1<<bits)-1,REFERENCE_AREA*readouts/collectionArea,1/Math.sqrt(readouts));
        }

        double meanElectrons(double flux, int band, double seconds) {
            double referenceSites = collectionArea/REFERENCE_AREA;
            return (flux*QUANTUM_EFFICIENCY[band]+4)*seconds*referenceSites;
        }

        double expectedLinear(double flux, int band, double seconds, Readout readout) {
            double capacity = readout.stage.capacity*collectionArea/REFERENCE_AREA;
            double electrons = Math.min(meanElectrons(flux,band,seconds)*readout.electronScale(),capacity)/readouts;
            return quantize(readout.signalVolts(electrons*siteCalibration))/(double)white;
        }

        int code(double flux, int band, double seconds, Readout readout, Random random) {
            // Increase effective collected charge while retaining the voltage calibration and shadow read-noise floor.
            double mean = meanElectrons(flux,band,seconds)*readout.electronScale();
            double capacity = readout.stage.capacity*collectionArea/REFERENCE_AREA;
            double electrons = Math.min(photonCount(mean,random),capacity);
            double input = electrons/readouts + readout.stage.inputNoise*random.nextGaussian()*noiseScale;
            double voltage = readout.signalVolts(input*siteCalibration)
                    + readout.stage.adcNoise*random.nextGaussian()*noiseScale;
            return quantize(voltage);
        }

        private int quantize(double voltage) {
            return (int)Math.round(white*Math.clamp(voltage/ADC_RANGE_VOLTS,0,1));
        }
    }

    static double filmAbsorbed(double red, double green, double blue) {
        return (.32*red+.49*green+.42*blue)/2300;
    }

    static BufferedImage film(double[] photons, int width, int height, FilmStock stock, Random random) {
        int[] pixels = new int[width*height];
        for (int i = 0; i < pixels.length; i++) {
            double absorbed = filmAbsorbed(photons[3*i],photons[3*i+1],photons[3*i+2]);
            double density = developDensity(absorbed,stock,random);
            int tone = encodeSrgb(Math.pow(10,-density));
            pixels[i] = tone*0x010101;
        }
        return image(width,height,pixels);
    }

    private static BufferedImage image(int width, int height, int[] pixels) {
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,width,height,pixels,0,width);
        return image;
    }

    static double developDensity(double absorbed, FilmStock stock, Random random) {
        double grainExposure = absorbed * stock.iso / BASE_ISO;
        // Three sensitivity populations approximate the spread in silver-halide grain response.
        // A grain needs at least two effective events to form a developable latent image.
        double developed = 0;
        for (double sensitivity : GRAIN_SENSITIVITY) {
            double development = developmentProbability(grainExposure*sensitivity);
            for (int grain = 0; grain < stock.grainsPerGroup; grain++) {
                if (random.nextDouble() < development) {
                    developed++;
                }
            }
        }
        return 0.10 + 1.9 * developed / (stock.grainsPerGroup * GRAIN_SENSITIVITY.length);
    }

    private static double developmentProbability(double mean) {
        double latent = 1-Math.exp(-mean)*(1+mean);
        return latent*(1-Math.exp(-2.7))+(1-latent)*(1-Math.exp(-.016));
    }

    static double photonCount(double mean, Random random) {
        if (mean >= 32) {
            return Math.max(0, Math.rint(mean + Math.sqrt(mean) * random.nextGaussian()));
        }
        double threshold = Math.exp(-mean);
        double product = random.nextDouble();
        int count = 0;
        while (product > threshold) {
            product *= random.nextDouble();
            count++;
        }
        return count;
    }

    static int filter(int x, int y) {
        return (x & 1) + (y & 1); // RGGB
    }

    static double interpolate(short[] values, int width, int height, int x, int y, int band) {
        if (filter(x, y) == band) {
            return Short.toUnsignedInt(values[y*width+x]);
        }
        int sum = 0;
        int count = 0;
        for (int row = Math.max(0, y - 1); row <= Math.min(height - 1, y + 1); row++) {
            for (int col = Math.max(0, x - 1); col <= Math.min(width - 1, x + 1); col++) {
                if (filter(col, row) == band) {
                    sum += Short.toUnsignedInt(values[row*width+col]);
                    count++;
                }
            }
        }
        return sum/(double)count;
    }

    static int encodeSrgb(double linear) {
        return encodeSrgb(linear,255);
    }

    private static int encodeSrgb(double linear, int maximum) {
        double value = linear <= 0.0031308 ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int)Math.round(maximum*Math.clamp(value,0,1));
    }
}
