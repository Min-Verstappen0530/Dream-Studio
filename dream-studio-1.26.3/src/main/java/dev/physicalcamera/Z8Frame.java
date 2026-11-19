package dev.physicalcamera;

import java.util.stream.IntStream;

record Z8Frame(int width, int height, int[] pixels, double halfTan, Z8Depth depth, boolean nativePhoto,
               SceneRadiometry radiometry, float[] integrated) {
    private static final int[] CHANNEL_SHIFT = {16,8,0};
    private static final double[] LINEAR = IntStream.range(0,256).mapToDouble(code -> {
        double value = code/255.0;
        return value <= .04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4);
    }).toArray();

    static double linearRgb(int rgb, int band) { return LINEAR[(rgb>>CHANNEL_SHIFT[band])&255]; }

    static float[] integrateFrames(int width, int height, int[][] frames, double[] weights, SceneRadiometry[] scenes) {
        float[] result = new float[3*width*height];
        IntStream.range(0,height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {
                int index = y*width+x;
                double red = 0, green = 0, blue = 0;
                for (int i = 0; i < frames.length; i++) {
                    double gain = weights[i]*scenes[i].scale();
                    int rgb = frames[i][index];
                    red += gain*linearRgb(rgb,0);
                    green += gain*linearRgb(rgb,1);
                    blue += gain*linearRgb(rgb,2);
                }
                result[3*index] = (float)red;
                result[3*index+1] = (float)green;
                result[3*index+2] = (float)blue;
            }
        });
        return result;
    }
    static double[][] aperturePoints(int samples, int blades) {
        double[][] points = new double[2][samples];
        double blade = Math.TAU/blades;
        for (int i = 0; i < samples; i++) {
            double angle = i*Math.PI*(3-Math.sqrt(5));
            double edge = Math.cos(blade/2)/Math.cos(Math.IEEEremainder(angle,blade));
            double radius = Math.sqrt((i+.5)/samples)*(.65+.35*edge);
            points[0][i] = radius*Math.cos(angle);
            points[1][i] = radius*Math.sin(angle);
        }
        return points;
    }

    record Crop(double halfWidth, double halfHeight) {}

    @FunctionalInterface
    interface PhotonSampler { double sample(int x, int y, int band); }

    static Crop crop(double halfTan, double aspect, double focal, double photoAspect) {
        return crop(halfTan,aspect,focal,photoAspect,SensorFormat.Z8_FX);
    }

    static Crop crop(double halfTan, double aspect, double focal, double photoAspect, SensorFormat sensor) {
        double gate = sensor.gateWidth(photoAspect)/(4*focal*halfTan);
        double halfHeight = Math.min(gate/photoAspect,Math.min(.5,.5*aspect/photoAspect));
        return new Crop(halfHeight*photoAspect/aspect,halfHeight);
    }

    static Crop aspectCrop(double aspect, double photoAspect) {
        double halfHeight = .5*Math.min(1,aspect/photoAspect);
        return new Crop(halfHeight*photoAspect/aspect,halfHeight);
    }

    PhotonSampler openMountSampler(Z8PhotoSize size) {
        double[] radiance = OpenMount.average((u,v,band) -> channel(u,v,band));
        return (x,y,band) -> radiance[band]*RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX
                *OpenMount.throughput(size.sensor(),(x+.5)/size.width,(y+.5)/size.height);
    }

    PhotonSampler sampler(Z24120Optics.Exposure optics, Z8PhotoSize size, int samples) {
        return sampler(optics,size,size.sensor(),samples);
    }

    PhotonSampler sampler(Z24120Optics.Exposure optics, Z8PhotoSize size, SensorFormat sensor, int samples) {
        if (optics == null) {
            // Standalone RAW uses a neutral f/4 reference response and the frame's actual projection.
            double gain = RecordingMedia.DAYLIGHT_WHITE_FLUX*Z24120Optics.lightThroughput(4);
            if (nativePhoto && width == size.width && height == size.height) {
                return (x,y,band) -> gain*linearRgb(pixels[y*width+x],band)*radiometry.scale();
            }
            double aspect = width/(double)height;
            double halfHeight = .5*Math.min(1,aspect/size.aspect());
            double halfWidth = halfHeight*size.aspect()/aspect;
            return (x,y,band) -> gain*channel(.5+((x+.5)*2/size.width-1)*halfWidth,
                    .5+((y+.5)*2/size.height-1)*halfHeight,band);
        }
        double focal = optics.projectionFocal();
        double aspect = width/(double)height;
        Crop crop = nativePhoto ? new Crop(.5,.5) : crop(halfTan,aspect,focal,size.aspect(),sensor);
        double du = 2*crop.halfWidth/size.width;
        double dv = 2*crop.halfHeight/size.height;
        double pixelsPerMm = 1/(du*2*focal*halfTan*aspect);
        double sensorMmPerPixel = sensor.gateWidth(size.aspect())/size.width;
        double lensSetting = optics.lens == ZoomLens.XCD35_100 ? optics.indicatedFocal() : focal;
        var mtf = new Z24120Mtf.Profile(optics.lens,lensSetting,optics.fNumber(),optics.focusMeters());
        var field = optics.lens.field(lensSetting,optics.fNumber());
        double whiteFlux = optics.throughput()*RecordingMedia.Z8_DAYLIGHT_WHITE_FLUX;
        double focusInverse = 1/optics.focusMeters();
        double[][] aperture = aperturePoints(samples,optics.lens.apertureBlades());
        var edges = depth == null ? null : new Z8DepthEdge(depth);
        return (x,y,band) -> {
            double radialX = x+.5-size.width/2.0, radialY = y+.5-size.height/2.0;
            double u = radialX*du, v = radialY*dv;
            double fieldRadius = Z24120Field.radiusSquared(2*radialX/size.width,2*radialY/size.height,size.aspect());
            double mappedScale = field.sourceScale(fieldRadius);
            u *= mappedScale;
            v *= mappedScale;
            int depthIndex = depth == null ? -1 : depth.indexAt(.5+u,.5+v);
            double inverse = depth == null ? focusInverse : depth.inverseMetres()[depthIndex];
            double gain = whiteFlux*field.transmission(fieldRadius);
            double colourScale = optics.colourScale(band,fieldRadius);
            double sx = .5+u*colourScale;
            double sy = .5+v*colourScale;
            double distance = Math.hypot(radialX,radialY);
            var kernel = mtf.at(distance*sensorMmPerPixel);
            double ax = distance > 0 ? radialX/(distance*sensorMmPerPixel) : 1/sensorMmPerPixel;
            double ay = distance > 0 ? radialY/(distance*sensorMmPerPixel) : 0;
            var edge = edges == null ? null : edges.split(depthIndex,.5+u,.5+v,du*mappedScale,dv*mappedScale);
            double centre = integrate(sx,sy,du,dv,inverse,optics,band,pixelsPerMm,aperture,kernel,ax,ay,
                    fieldRadius,sensorMmPerPixel,edges,edge == null ? null : edge.centre());
            if (edge == null || edge.neighbour() == null) return gain*centre;
            var neighbour = edge.neighbour();
            double other = integrate(sx,sy,du,dv,neighbour.inverseDepth(),optics,band,pixelsPerMm,aperture,
                    kernel,ax,ay,fieldRadius,sensorMmPerPixel,edges,neighbour);
            return gain*Math.fma(other-centre,edge.neighbourCoverage(),centre);
        };
    }

    private double integrate(double u, double v, double du, double dv, double inverse,
                             Z24120Optics.Exposure optics, int band, double pixelsPerMm, double[][] aperture,
                             Z24120Mtf.Kernel kernel, double ax, double ay, double fieldRadius, double sensorMmPerPixel,
                             Z8DepthEdge edges, Z8DepthEdge.Layer layer) {
        double radius = optics.spreadAtInverseDistance(inverse,band)*pixelsPerMm;
        if (radius < .45) {
            double sum = 0;
            for (var tap : kernel.taps()) {
                double dx = ax*tap.radialMm()-ay*tap.tangentialMm();
                double dy = ay*tap.radialMm()+ax*tap.tangentialMm();
                double sx = u+dx*du, sy = v+dy*dv;
                double value = layer == null ? interpolate(sx,sy,band)
                        : interpolateOnSurface(width,height,pixels,integrated,depth,sx,sy,band,inverse,layer.u(),layer.v());
                sum += tap.weight()*value;
            }
            // The MTF stencil is small compared with a cell of the scene exposure map.
            return integrated == null ? sum*radiometry.scale() : sum;
        }
        double sum = 0;
        int count = 0;
        double threshold = inverse+Math.max(.008,inverse*.08);
        double pupilClip = optics.radiusAtInverseDistance(inverse,band)*pixelsPerMm >= .45
                ? optics.pupilClipping(fieldRadius) : 0;
        double rx = ax*sensorMmPerPixel, ry = ay*sensorMmPerPixel;
        for (int i = 0; i < aperture[0].length; i++) {
            var tap = kernel.forApertureSample(i);
            double px = aperture[0][i], py = aperture[1][i];
            double inward = pupilClip == 0 ? 0 : pupilOffset(px,py,rx,ry,pupilClip);
            double dx = (px+rx*inward)*radius+ax*tap.radialMm()-ay*tap.tangentialMm();
            double dy = (py+ry*inward)*radius+ay*tap.radialMm()+ax*tap.tangentialMm();
            double sx = u+dx*du, sy = v+dy*dv;
            if (sx < 0 || sx > 1 || sy < 0 || sy > 1) {
                continue;
            }
            double value;
            if (depth != null) {
                int sampleIndex = depth.indexAt(sx,sy);
                double sampleDepth = depth.inverseMetres()[sampleIndex];
                // A sharp foreground silhouette cannot bleed into a distant blurred plane.
                if (sampleDepth > threshold) {
                    double foregroundRadius = optics.radiusAtInverseDistance(sampleDepth,band)*pixelsPerMm;
                    double absX = Math.abs(dx), absY = Math.abs(dy);
                    if (foregroundRadius < Math.max(absX,absY)
                            || foregroundRadius < absX+absY && foregroundRadius < Math.hypot(dx,dy)) continue;
                }
                value = !edges.near(sampleIndex) ? channel(sx,sy,band)
                        : scaleRadiance(interpolateOnSurface(width,height,pixels,integrated,depth,
                                sx,sy,band,sampleDepth,sx,sy));
            } else {
                value = channel(sx,sy,band);
            }
            sum += value;
            count++;
        }
        if (count > 0) return sum/count;
        return layer == null ? channel(u,v,band)
                : scaleRadiance(interpolateOnSurface(width,height,pixels,integrated,depth,u,v,band,inverse,layer.u(),layer.v()));
    }

    static double pupilOffset(double x, double y, double radialX, double radialY, double clipping) {
        double tangential = radialX*y-radialY*x;
        return -(radialX*x+radialY*y)*clipping*(1+.32*Math.abs(tangential));
    }

    private double channel(double u, double v, int band) {
        return scaleRadiance(interpolate(u,v,band));
    }

    private double scaleRadiance(double colour) {
        return integrated != null ? colour : colour*radiometry.scale();
    }

    private double interpolate(double u, double v, int band) {
        return interpolate(width,height,pixels,integrated,u,v,band);
    }

    static double interpolateRgb(int width, int height, int[] pixels, double u, double v, int band) {
        return interpolate(width,height,pixels,null,u,v,band);
    }

    static double interpolateOnSurface(int width, int height, int[] pixels, float[] integrated, Z8Depth depth,
                                       double u, double v, int band, double inverse, double fallbackU, double fallbackV) {
        double x = Math.clamp(u*width-.5,0,width-1), y = Math.clamp(v*height-.5,0,height-1);
        int xi = (int)x, yi = (int)y;
        double fractionX = x-xi, fractionY = y-yi;
        double sum = 0, weight = 0;
        for (int row = yi; row <= Math.min(yi+1,height-1); row++) {
            double wy = row == yi ? 1-fractionY : fractionY;
            for (int col = xi; col <= Math.min(xi+1,width-1); col++) {
                if (!Z8DepthEdge.sameSurface(inverse,depth.at((col+.5)/width,(row+.5)/height))) continue;
                double tap = wy*(col == xi ? 1-fractionX : fractionX);
                sum += tap*linear(pixels,integrated,row*width+col,band);
                weight += tap;
            }
        }
        return weight >= .001 ? sum/weight : interpolate(width,height,pixels,integrated,fallbackU,fallbackV,band);
    }

    private static double interpolate(int width, int height, int[] pixels, float[] integrated, double u, double v, int band) {
        double x = Math.clamp(u*width-.5,0,width-1);
        double y = Math.clamp(v*height-.5,0,height-1);
        int xi = (int)x, yi = (int)y;
        int nextX = Math.min(xi+1,width-1), nextY = Math.min(yi+1,height-1);
        double a = linear(pixels,integrated,yi*width+xi,band);
        double b = linear(pixels,integrated,yi*width+nextX,band);
        double c = linear(pixels,integrated,nextY*width+xi,band);
        double d = linear(pixels,integrated,nextY*width+nextX,band);
        double top = Math.fma(b-a,x-xi,a), bottom = Math.fma(d-c,x-xi,c);
        return Math.fma(bottom-top,y-yi,top);
    }

    private static double linear(int[] pixels, float[] integrated, int index, int band) {
        return integrated == null ? linearRgb(pixels[index],band) : integrated[3*index+band];
    }
}
