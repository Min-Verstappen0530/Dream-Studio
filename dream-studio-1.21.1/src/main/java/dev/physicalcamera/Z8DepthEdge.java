package dev.physicalcamera;

import java.util.stream.IntStream;

/** Estimate pixel coverage at silhouettes without interpolating foreground and background depths. */
final class Z8DepthEdge {
    private static final double FIT_RADIUS = 2.5;
    private final Z8Depth depth;
    private final boolean[] boundary;

    Z8DepthEdge(Z8Depth depth) {
        this.depth = depth;
        int width = depth.width(), height = depth.height();
        var values = depth.inverseMetres();
        boundary = new boolean[values.length];
        IntStream.range(0,height).parallel().forEach(y -> {
            int row = y*width, above = Math.max(0,y-1)*width, below = Math.min(height-1,y+1)*width;
            for (int x = 0; x < width; x++) {
                double centre = values[row+x];
                int left = Math.max(0,x-1), right = Math.min(width-1,x+1);
                boundary[row+x] = !sameSurface(centre,values[row+left]) || !sameSurface(centre,values[row+right])
                        || !sameSurface(centre,values[above+x]) || !sameSurface(centre,values[below+x])
                        || !sameSurface(centre,values[above+left]) || !sameSurface(centre,values[above+right])
                        || !sameSurface(centre,values[below+left]) || !sameSurface(centre,values[below+right]);
            }
        });
    }

    record Layer(double inverseDepth, double u, double v) {}
    record Split(Layer centre, Layer neighbour, double neighbourCoverage) {}

    static boolean sameSurface(double a, double b) {
        return Math.abs(a-b) <= Math.max(.0003,.045*Math.max(a,b));
    }

    boolean near(int depthIndex) { return boundary[depthIndex]; }

    Split split(int centre, double u, double v, double stepU, double stepV) {
        if (!near(centre)) return null;
        int width = depth.width(), height = depth.height();
        double px = u*width+depth.offsetX(), py = v*height+depth.offsetY();
        var values = depth.inverseMetres();
        double inverse = values[centre];

        double pixelRadiusX = Math.clamp(.5*stepU*width,.18,1);
        double pixelRadiusY = Math.clamp(.5*stepV*height,.18,1);
        // A conservative footprint also supplies a fallback for corners and thin silhouettes.
        double rx = Math.max(pixelRadiusX,.54);
        double ry = Math.max(pixelRadiusY,.54);
        double left = Math.clamp(px-rx,0,width), right = Math.clamp(px+rx,0,width);
        double top = Math.clamp(py-ry,0,height), bottom = Math.clamp(py+ry,0,height);
        int firstX = (int)left, lastX = Math.min(width-1,(int)right);
        int firstY = (int)top, lastY = Math.min(height-1,(int)bottom);

        double coverage = 0, strongest = 0, neighbourDepth = 0;
        int neighbourX = 0, neighbourY = 0;
        for (int y = firstY; y <= lastY; y++) {
            double row = -1;
            for (int x = firstX; x <= lastX; x++) {
                float sample = values[y*width+x];
                if (sameSurface(inverse,sample)) continue;
                if (row < 0) row = Math.min(bottom,y+1)-Math.max(top,y);
                double area = row*(Math.min(right,x+1)-Math.max(left,x));
                coverage += area;
                if (area > strongest) {
                    strongest = area;
                    neighbourDepth = sample;
                    neighbourX = x;
                    neighbourY = y;
                }
            }
        }
        var centreLayer = layer(depth,inverse,centre%width,centre/width);
        if (coverage <= 0) return new Split(centreLayer,null,0);
        coverage /= (right-left)*(bottom-top);
        double fitted = fittedCoverage(px,py,centre%width,centre/width,inverse,pixelRadiusX,pixelRadiusY);
        if (!Double.isNaN(fitted)) coverage = fitted;
        if (coverage < .005) return new Split(centreLayer,null,0);
        return new Split(centreLayer,
                layer(depth,neighbourDepth,neighbourX,neighbourY),Math.min(1,coverage));
    }

    /** Fit the tangent through neighbouring depth crossings, with weights that taper smoothly. */
    private double fittedCoverage(double px, double py, int cx, int cy, double inverse, double rx, double ry) {
        int width = depth.width(), height = depth.height();
        var values = depth.inverseMetres();
        var fit = new Contour();
        for (int y = Math.max(0,cy-3); y <= Math.min(height-1,cy+3); y++) {
            int row = y*width;
            double dy = y+.5-py;
            for (int x = Math.max(0,cx-3); x <= Math.min(width-1,cx+3); x++) {
                double dx = x+.5-px;
                if (Math.abs(dx) >= FIT_RADIUS+.5 || Math.abs(dy) >= FIT_RADIUS+.5) continue;
                boolean centrePlane = sameSurface(inverse,values[row+x]);
                int direction = centrePlane ? 1 : -1;
                if (x+1 < width && centrePlane != sameSurface(inverse,values[row+x+1])) {
                    fit.add(dx+.5,dy,direction,0);
                }
                if (y+1 < height && centrePlane != sameSurface(inverse,values[row+width+x])) {
                    fit.add(dx,dy+.5,0,direction);
                }
            }
        }
        return fit.coverage(rx,ry);
    }

    private static final class Contour {
        private double weight, x, y, xx, xy, yy, directionX, directionY;

        void add(double dx, double dy, int across, int down) {
            double taper = 1-(dx*dx+dy*dy)/(FIT_RADIUS*FIT_RADIUS);
            if (taper <= 0) return;
            double w = taper*taper;
            weight += w;
            x += w*dx;
            y += w*dy;
            xx += w*dx*dx;
            xy += w*dx*dy;
            yy += w*dy*dy;
            directionX += w*across;
            directionY += w*down;
        }

        double coverage(double rx, double ry) {
            // Opposing crossings are typical of a wire, a leaf tip or a narrow gap.
            if (weight < .55 || Math.abs(directionX)+Math.abs(directionY) < .85*weight) return Double.NaN;
            double meanX = x/weight, meanY = y/weight;
            double varX = xx/weight-meanX*meanX, varY = yy/weight-meanY*meanY;
            double covariance = xy/weight-meanX*meanY;
            double anisotropy = Math.hypot(varX-varY,2*covariance);
            double major = .5*(varX+varY+anisotropy), minor = .5*(varX+varY-anisotropy);
            if (major < .26 || minor > .20*major) return Double.NaN;
            double angle = .5*Math.atan2(2*covariance,varX-varY);
            double nx = -Math.sin(angle), ny = Math.cos(angle);
            if (nx*directionX+ny*directionY < 0) {
                nx = -nx;
                ny = -ny;
            }
            return halfPlaneCoverage(-nx*meanX-ny*meanY,Math.abs(nx)*rx,Math.abs(ny)*ry);
        }
    }

    // Exact area of the sensor pixel on one side of a line in depth-texel coordinates.
    private static double halfPlaneCoverage(double distance, double x, double y) {
        double a = Math.max(x,y), b = Math.min(x,y);
        if (b < 1e-6) return Math.clamp(.5+distance/(2*a),0,1);
        double d = Math.abs(distance);
        double tail = Math.max(0,a+b-d);
        double upper = d <= a-b ? .5+d/(2*a) : 1-tail*tail/(8*a*b);
        return distance >= 0 ? upper : 1-upper;
    }

    private static Layer layer(Z8Depth depth, double inverse, int x, int y) {
        return new Layer(inverse,(x+.5-depth.offsetX())/depth.width(),(y+.5-depth.offsetY())/depth.height());
    }
}
