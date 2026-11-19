package dev.physicalcamera;

import java.util.function.IntBinaryOperator;

/** Tone counts sampled from sRGB image pixels, before any UI is drawn. */
record ToneHistogram(Curve luminance, Curve red, Curve green, Curve blue) {
    static final int BINS = 64;

    record Curve(int[] counts, int peak, boolean blackClipped, boolean whiteClipped) {
        private static Curve of(int[] tones) {
            int[] counts = new int[BINS];
            int peak = 0;
            for (int i = 0; i < counts.length; i++) {
                int code = i*4;
                counts[i] = tones[code]+tones[code+1]+tones[code+2]+tones[code+3];
                peak = Math.max(peak,counts[i]);
            }
            return new Curve(counts,peak,tones[0] > 0,tones[255] > 0);
        }
    }

    static Curve live(int width, int height, int[] rgba) {
        return sample(width,height,96,false,(x,y) -> rgba[y*width+x]>>>8).luminance;
    }

    static ToneHistogram playback(int width, int height, IntBinaryOperator pixel) {
        return sample(width,height,384,true,pixel);
    }

    private static ToneHistogram sample(int width, int height, int columns, boolean rgb, IntBinaryOperator pixel) {
        int nx = Math.min(width,columns);
        int ny = Math.min(height,Math.max(1,(int)Math.round(height*nx/(double)width)));
        int[] luminance = new int[256];
        int[][] colour = rgb ? new int[3][256] : null;
        for (int y = 0; y < ny; y++) {
            int row = (int)((y+.5)*height/ny);
            for (int x = 0; x < nx; x++) {
                int argb = pixel.applyAsInt((int)((x+.5)*width/nx),row);
                int r = (argb>>>16)&255, g = (argb>>>8)&255, b = argb&255;
                // Rec. 709 luma weights applied to the encoded display values.
                luminance[(54*r+183*g+19*b+128)>>>8]++;
                if (colour != null) {
                    colour[0][r]++;
                    colour[1][g]++;
                    colour[2][b]++;
                }
            }
        }
        return new ToneHistogram(Curve.of(luminance),rgb ? Curve.of(colour[0]) : null,
                rgb ? Curve.of(colour[1]) : null,rgb ? Curve.of(colour[2]) : null);
    }
}
