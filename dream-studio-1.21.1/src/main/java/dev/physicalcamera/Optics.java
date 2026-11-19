package dev.physicalcamera;

import java.util.List;
import java.util.random.RandomGenerator;

/** Sensor at z = 0; scene at +z; lens dimensions in millimetres. */
final class Optics {
    static final double[] WAVELENGTH_NM = {610, 550, 460};
    static final double SENSOR_WIDTH = 36;
    static final double SENSOR_HEIGHT = 24;

    record Vector(double x, double y, double z) {
        Vector add(Vector v) { return new Vector(x + v.x, y + v.y, z + v.z); }
        Vector times(double s) { return new Vector(x * s, y * s, z * s); }
        double dot(Vector v) { return x * v.x + y * v.y + z * v.z; }
        Vector unit() { return times(1 / Math.sqrt(dot(this))); }
    }

    record Ray(Vector origin, Vector direction) {
        Vector at(double distance) { return origin.add(direction.times(distance)); }
    }

    // Cauchy's approximation, with wavelength in micrometres.
    record Medium(double a, double b, double absorptionPerMm) {
        double index(double nm) { return a + b / Math.pow(nm * 0.001, 2); }
    }

    // A zero radius is planar. Signed radius locates the centre relative to the vertex.
    record Surface(double offset, double radius, double clearRadius, Medium after,
                   double scatterSigmaRadians) {}

    record Lens(List<Surface> surfaces) {
        Lens {
            surfaces = List.copyOf(surfaces);
        }

        static Lens singlet(double radius, double scatter) {
            var glass = new Medium(1.5046, 0.00420, 0.001);
            return new Lens(List.of(new Surface(0, radius, 15, glass, scatter),
                    new Surface(6, -radius, 15, AIR, scatter)));
        }

        double focalLength() {
            Paraxial result = forwardParaxial(0);
            return -1 / result.slope;
        }

        Camera focus(double distanceMm, double fNumber) {
            Paraxial result = forwardParaxial(1 / distanceMm);
            double backFocus = -result.height / result.slope;
            if (!Double.isFinite(backFocus) || backFocus <= 1) {
                throw new IllegalArgumentException("Subject lies inside the focus range.");
            }
            // The effective f-number is approximate because the stop is behind the glass.
            return new Camera(this, backFocus, Math.min(15, focalLength() / (2 * fNumber)));
        }

        private Paraxial forwardParaxial(double slope) {
            double height = 1;
            double index = 1;
            double previous = surfaces.getLast().offset;
            for (int i = surfaces.size() - 1; i >= 0; i--) {
                Surface surface = surfaces.get(i);
                height += slope * (previous - surface.offset);
                Medium next = i > 0 ? surfaces.get(i - 1).after : AIR;
                double nextIndex = next.index(550);
                double power = surface.radius == 0 ? 0 : (nextIndex - index) / -surface.radius;
                slope = (index * slope - height * power) / nextIndex;
                index = nextIndex;
                previous = surface.offset;
            }
            return new Paraxial(height, slope);
        }
    }

    private record Paraxial(double height, double slope) {}
    private static final Medium AIR = new Medium(1, 0, 0);

    record Trace(Ray ray, double throughput) {}

    record Camera(Lens lens, double backFocus, double stopRadius) {
        Trace trace(double x, double y, double pupilX, double pupilY, int band,
                    RandomGenerator random) {
            if (Math.hypot(pupilX, pupilY) > stopRadius) {
                return null;
            }
            double stopZ = backFocus - 1;
            Vector film = new Vector(x, y, 0);
            Vector delta = new Vector(pupilX - x, pupilY - y, stopZ);
            Ray ray = new Ray(film, delta.unit());
            // Area-to-solid-angle Jacobian and projected area of the sensor.
            double throughput = Math.PI * stopRadius * stopRadius
                    * Math.pow(ray.direction.z, 4) / (stopZ * stopZ);
            double wavelength = WAVELENGTH_NM[band];
            Medium before = AIR;
            for (Surface surface : lens.surfaces) {
                double vertex = backFocus + surface.offset;
                double distance = intersection(ray, surface, vertex);
                if (!Double.isFinite(distance)) {
                    return null;
                }
                Vector point = ray.at(distance);
                Vector normal = surface.radius == 0 ? new Vector(0, 0, -1)
                        : point.add(new Vector(0, 0, -vertex - surface.radius)).unit();
                if (normal.dot(ray.direction) > 0) {
                    normal = normal.times(-1);
                }
                double n1 = before.index(wavelength);
                double n2 = surface.after.index(wavelength);
                double cosIncident = Math.clamp(-normal.dot(ray.direction), 0, 1);
                double ratio = n1 / n2;
                double cosSquared = 1 - ratio * ratio * (1 - cosIncident * cosIncident);
                if (cosSquared <= 0) {
                    return null;
                }
                double cosTransmitted = Math.sqrt(cosSquared);
                Vector transmitted = ray.direction.times(ratio)
                        .add(normal.times(ratio * cosIncident - cosTransmitted));
                throughput *= Math.exp(-before.absorptionPerMm * distance)
                        * (1 - fresnel(n1, n2, cosIncident, cosTransmitted));
                if (surface.scatterSigmaRadians > 0) {
                    transmitted = transmitted.add(new Vector(random.nextGaussian(),
                            random.nextGaussian(), random.nextGaussian())
                            .times(surface.scatterSigmaRadians)).unit();
                }
                if (transmitted.dot(normal) >= 0 || transmitted.z <= 0) {
                    return null;
                }
                ray = new Ray(point, transmitted);
                before = surface.after;
            }
            return new Trace(ray, throughput);
        }
    }

    private static double intersection(Ray ray, Surface surface, double z) {
        if (surface.radius == 0) {
            return validHit(ray, surface, (z - ray.origin.z) / ray.direction.z);
        }
        double centreZ = z + surface.radius;
        Vector relative = ray.origin.add(new Vector(0, 0, -centreZ));
        double b = relative.dot(ray.direction);
        double d = b * b - relative.dot(relative) + surface.radius * surface.radius;
        if (d < 0) {
            return Double.NaN;
        }
        double root = Math.sqrt(d);
        // The cap containing the vertex is the near root for a positive radius.
        return validHit(ray, surface, -b - Math.copySign(root, surface.radius));
    }

    private static double validHit(Ray ray, Surface surface, double distance) {
        if (distance <= 1e-7) {
            return Double.NaN;
        }
        Vector hit = ray.at(distance);
        return Math.hypot(hit.x, hit.y) <= surface.clearRadius ? distance : Double.NaN;
    }

    private static double fresnel(double n1, double n2, double ci, double ct) {
        double s = (n1 * ci - n2 * ct) / (n1 * ci + n2 * ct);
        double p = (n2 * ci - n1 * ct) / (n2 * ci + n1 * ct);
        return (s * s + p * p) * 0.5;
    }
}
