package dev.physicalcamera;

/** Cosine-weighted irradiance through an open mount with no imaging lens. */
final class OpenMount {
    private static final int SAMPLES = 64;
    private static final Profile Z = new Profile(SensorFormat.Z8_FX,27.5,16);
    private static final Profile X = new Profile(SensorFormat.X2D_II,30.5,18.14);
    static final double EQUIVALENT_F_NUMBER = Z.aperture;

    private static final class Profile {
        final SensorFormat sensor;
        final double edge, aperture;
        final double[] throughput = new double[SAMPLES+1];

        Profile(SensorFormat sensor, double mountRadius, double distance) {
            this.sensor = sensor;
            edge = Math.hypot(sensor.widthMm()/2,sensor.heightMm()/2);
            aperture = Math.sqrt(Z24120Optics.lightThroughput(1)
                    /(Math.PI*mountRadius*mountRadius/(mountRadius*mountRadius+distance*distance)));
            for (int i = 0; i <= SAMPLES; i++) {
                double offset = edge*i/SAMPLES;
                double irradiance = 0;
                for (int r = 0; r < 16; r++) {
                    double radius = mountRadius*Math.sqrt((r+.5)/16);
                    for (int angle = 0; angle < 48; angle++) {
                        double d2 = distance*distance+radius*radius+offset*offset
                                -2*radius*offset*Math.cos(Math.TAU*angle/48);
                        irradiance += distance*distance/(d2*d2);
                    }
                }
                throughput[i] = irradiance*Math.PI*mountRadius*mountRadius/(16*48);
            }
        }

        double at(double u, double v) {
            double radius = Math.hypot((u-.5)*sensor.widthMm(),(v-.5)*sensor.heightMm());
            double index = Math.clamp(radius/edge*SAMPLES,0,SAMPLES);
            int low = Math.min((int)index,SAMPLES-1);
            return Math.fma(index-low,throughput[low+1]-throughput[low],throughput[low]);
        }
    }

    private static Profile profile(SensorFormat sensor) { return sensor == SensorFormat.X2D_II ? X : Z; }
    static double equivalentAperture(SensorFormat sensor) { return profile(sensor).aperture; }
    static double throughput(double u, double v) { return Z.at(u,v); }
    static double throughput(SensorFormat sensor, double u, double v) { return profile(sensor).at(u,v); }

    interface AngularRadiance { double at(double u, double v, int band); }

    static double[] average(AngularRadiance scene) {
        double[] bands = new double[3];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 24; x++) {
                for (int b = 0; b < bands.length; b++) bands[b] += scene.at((x+.5)/24,(y+.5)/16,b)/(24*16);
            }
        }
        return bands;
    }

    private OpenMount() {}
}
