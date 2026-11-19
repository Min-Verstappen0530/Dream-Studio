package dev.physicalcamera;

/** FX relative illumination and residual distortion for the corrected 24–120 image. */
record Z24120Field(double cornerEv, double residual) {
    private static final double[] FOCALS = {24,35,70,120};
    private static final double[] APERTURES = {4,5.6,8,11,16,22};
    // Relative illumination measured on a Z 7 II. Rows follow aperture, columns focal length.
    private static final double[][] CORNER_EV = {
            {2.1,1,1.4,1.8}, {1.7,.7,.8,1.6}, {1.6,.7,.6,1.5},
            {1.6,.7,.6,1.3}, {1.6,.7,.6,1.3}, {1.6,.7,.6,1.2}
    };
    // Small image-space residuals; the strong uncorrected barrel distortion is not added.
    private static final double[] RESIDUAL = {-.0026,.0021,.0004,.0003};

    static Z24120Field at(double focal, double aperture) {
        int z = interval(FOCALS,focal), a = interval(APERTURES,aperture);
        double zoom = fraction(FOCALS,z,focal), stop = fraction(APERTURES,a,aperture);
        double open = mix(CORNER_EV[a][z],CORNER_EV[a][z+1],zoom);
        double closed = mix(CORNER_EV[a+1][z],CORNER_EV[a+1][z+1],zoom);
        return new Z24120Field(mix(open,closed,stop),mix(RESIDUAL[z],RESIDUAL[z+1],zoom));
    }

    static double radiusSquared(double x, double y, double aspect) {
        // x and y are normalized to the half-width and half-height of the output image.
        double aspectSquared = aspect*aspect;
        return Math.min(1,(x*x*aspectSquared+y*y)/(aspectSquared+1));
    }

    double transmission(double radiusSquared) {
        return Math.exp(-Math.log(2)*cornerEv*radiusSquared*(.45+.55*radiusSquared));
    }

    double sourceScale(double radiusSquared) {
        // Normalize the inverse map's largest scale to 1 to keep pixels inside the available frame.
        return (1-residual*radiusSquared)/Math.max(1,1-residual);
    }

    private static int interval(double[] marks, double value) {
        int index = 0;
        while (index+2 < marks.length && value > marks[index+1]) index++;
        return index;
    }

    private static double fraction(double[] marks, int index, double value) {
        return Math.clamp((value-marks[index])/(marks[index+1]-marks[index]),0,1);
    }

    private static double mix(double a, double b, double t) { return Math.fma(b-a,t,a); }
}
