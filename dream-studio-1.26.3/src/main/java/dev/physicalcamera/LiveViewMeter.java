package dev.physicalcamera;

import java.util.function.DoubleBinaryOperator;

/** Spatial metering of the linear light in the framed view, before camera exposure. */
final class LiveViewMeter {
    private static final int COLUMNS = 96, ROWS = 64;
    private static final int BINS = 256;
    private static final double INVERSE_LOG_2 = 1/Math.log(2);

    static double measure(Z8Settings.Meter mode, double focusU, double focusV, double aspect,
                          DoubleBinaryOperator luminance) {
        double[] weights = new double[BINS], totals = new double[BINS];
        double totalWeight = 0;
        for (int y = 0; y < ROWS; y++) {
            double v = (y+.5)/ROWS;
            for (int x = 0; x < COLUMNS; x++) {
                double u = (x+.5)/COLUMNS;
                double weight = weight(mode,u,v,focusU,focusV,aspect);
                if (weight == 0) continue;
                double value = Math.max(0,luminance.applyAsDouble(u,v));
                int bin = value > 0 ? Math.clamp(1+(int)Math.floor(8*(Math.log(value)*INVERSE_LOG_2+25)),1,BINS-1) : 0;
                weights[bin] += weight;
                totals[bin] += weight*value;
                totalWeight += weight;
            }
        }
        double low = percentile(weights,totals,totalWeight*.05);
        double high = percentile(weights,totals,totalWeight*.95);
        double sum = 0;
        for (int bin = 0; bin < BINS; bin++) {
            if (weights[bin] > 0) sum += weights[bin]*Math.clamp(totals[bin]/weights[bin],low,high);
        }
        return sum/totalWeight;
    }

    private static double weight(Z8Settings.Meter mode, double u, double v,
                                 double focusU, double focusV, double aspect) {
        double dx = (u-.5)*aspect, dy = v-.5;
        double fx = (u-focusU)*aspect, fy = v-focusV;
        double center = dx*dx+dy*dy, focus = fx*fx+fy*fy;
        return switch (mode) {
            case MATRIX -> 1+Math.exp(-center/.16)+Math.exp(-focus/.025);
            case CENTER -> .25+4*Math.exp(-center/.065);
            case SPOT -> focus <= .055*.055 ? 1 : 0;
        };
    }

    private static double percentile(double[] weights, double[] totals, double target) {
        for (int bin = 0; bin < weights.length; bin++) {
            if (weights[bin] == 0) continue;
            target -= weights[bin];
            if (target <= 0) return totals[bin]/weights[bin];
        }
        return 0;
    }

    private LiveViewMeter() {}
}
