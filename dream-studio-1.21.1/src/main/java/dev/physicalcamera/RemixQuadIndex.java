package dev.physicalcamera;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Spatially bounds the face tests used to build the Remix material shells. */
public final class RemixQuadIndex {
    private static final float PLANE_EPSILON = 1e-5f;
    private static final float EXTENT_EPSILON = 1e-4f;
    private static final int GRID = 24;

    private RemixQuadIndex() {}

    @FunctionalInterface
    public interface FaceTest {
        boolean contains(float[] px,float[] py,float[] pz,int face,float x,float y,float z,
                         float nx,float ny,float nz);
    }

    public static boolean offsets(float[] px, float[] py, float[] pz, float[] nx, float[] ny, float[] nz,
                                  int start, int count, float depth, int cardFace,
                                  float[] ox, float[] oy, float[] oz, FaceTest test) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = start; i < start+count; i++) {
            minX = Math.min(minX,px[i]); maxX = Math.max(maxX,px[i]);
            minY = Math.min(minY,py[i]); maxY = Math.max(maxY,py[i]);
            minZ = Math.min(minZ,pz[i]); maxZ = Math.max(maxZ,pz[i]);
        }
        double span = Math.max(Math.max(maxX-(double)minX,maxY-(double)minY),maxZ-(double)minZ);
        if (!Double.isFinite(span) || span < PLANE_EPSILON) return false;
        double scale = GRID/span;
        var cells = new IntArrayList[GRID*GRID*GRID];
        for (int a = start; a+3 < start+count; a += 4) {
            // Bound both the corner tolerance and the parallelepiped of accepted s/t and plane coordinates.
            double x = px[a], y = py[a], z = pz[a];
            double ux = px[a+1]-x, uy = py[a+1]-y, uz = pz[a+1]-z;
            double vx = px[a+3]-x, vy = py[a+3]-y, vz = pz[a+3]-z;
            double uu = ux*ux+uy*uy+uz*uz, vv = vx*vx+vy*vy+vz*vz;
            double bx = vy*nz[a]-vz*ny[a], by = vz*nx[a]-vx*nz[a], bz = vx*ny[a]-vy*nx[a];
            double cx = ny[a]*uz-nz[a]*uy, cy = nz[a]*ux-nx[a]*uz, cz = nx[a]*uy-ny[a]*ux;
            double dx = uy*vz-uz*vy, dy = uz*vx-ux*vz, dz = ux*vy-uy*vx;
            double determinant = ux*bx+uy*by+uz*bz;
            double lowX = x, highX = x, lowY = y, highY = y, lowZ = z, highZ = z;
            for (int q = a+1; q < a+4; q++) {
                lowX = Math.min(lowX,px[q]); highX = Math.max(highX,px[q]);
                lowY = Math.min(lowY,py[q]); highY = Math.max(highY,py[q]);
                lowZ = Math.min(lowZ,pz[q]); highZ = Math.max(highZ,pz[q]);
            }
            if (uu >= 1e-12 && vv >= 1e-12) {
                if (!(Math.abs(determinant) >= .001*Math.sqrt(uu*vv))) return false;
                double du = uu/determinant, dv = vv/determinant, dw = 1/determinant;
                bx *= du; by *= du; bz *= du;
                cx *= dv; cy *= dv; cz *= dv;
                dx *= dw; dy *= dw; dz *= dw;
                double radiusX = (.5+EXTENT_EPSILON)*(Math.abs(bx)+Math.abs(cx))+PLANE_EPSILON*Math.abs(dx);
                double radiusY = (.5+EXTENT_EPSILON)*(Math.abs(by)+Math.abs(cy))+PLANE_EPSILON*Math.abs(dy);
                double radiusZ = (.5+EXTENT_EPSILON)*(Math.abs(bz)+Math.abs(cz))+PLANE_EPSILON*Math.abs(dz);
                double midX = x+.5*(bx+cx), midY = y+.5*(by+cy), midZ = z+.5*(bz+cz);
                lowX = Math.min(lowX,midX-radiusX); highX = Math.max(highX,midX+radiusX);
                lowY = Math.min(lowY,midY-radiusY); highY = Math.max(highY,midY+radiusY);
                lowZ = Math.min(lowZ,midZ-radiusZ); highZ = Math.max(highZ,midZ+radiusZ);
            }
            // Include rounding in the original single-precision face predicate.
            double padding = PLANE_EPSILON+span*2e-6;
            for (int gz=cell(lowZ-padding,minZ,scale), endZ=cell(highZ+padding,minZ,scale); gz<=endZ; gz++) {
                for (int gy=cell(lowY-padding,minY,scale), endY=cell(highY+padding,minY,scale); gy<=endY; gy++) {
                    for (int gx=cell(lowX-padding,minX,scale), endX=cell(highX+padding,minX,scale); gx<=endX; gx++) {
                        int index = (gz*GRID+gy)*GRID+gx;
                        if (cells[index] == null) cells[index] = new IntArrayList();
                        cells[index].add(a);
                    }
                }
            }
        }

        var normals = new IntArrayList();
        for (int v = start; v < start+count; v++) {
            int key = (cell(pz[v],minZ,scale)*GRID+cell(py[v],minY,scale))*GRID+cell(px[v],minX,scale);
            var faces = cells[key];
            normals.clear();
            normals.add(v);
            float sx = nx[v], sy = ny[v], sz = nz[v];
            if (faces != null) {
                // Cells hold faces in source order. The normal selection and summation order match Remix.
                faces: for (int f = 0; f < faces.size(); f++) {
                    int a = faces.getInt(f);
                    float qx = nx[a], qy = ny[a], qz = nz[a];
                    if (sxFiniteDot(nx[v],ny[v],nz[v],qx,qy,qz) < -.999f) continue;
                    for (int k = 0; k < normals.size(); k++) {
                        int n = normals.getInt(k);
                        if (sxFiniteDot(nx[n],ny[n],nz[n],qx,qy,qz) > .999f) continue faces;
                    }
                    if (!test.contains(px,py,pz,a,px[v],py[v],pz[v],qx,qy,qz)) continue;
                    normals.add(a);
                    sx += qx; sy += qy; sz += qz;
                }
            }
            if (cardFace >= 0) {
                float along = sxFiniteDot(sx,sy,sz,nx[cardFace],ny[cardFace],nz[cardFace]);
                sx = along*nx[cardFace]; sy = along*ny[cardFace]; sz = along*nz[cardFace];
            }
            ox[v-start] = sx*depth; oy[v-start] = sy*depth; oz[v-start] = sz*depth;
        }
        return true;
    }

    private static float sxFiniteDot(float x,float y,float z,float a,float b,float c) { return x*a+y*b+z*c; }
    private static int cell(double position,double minimum,double scale) {
        return Math.clamp((int)((position-minimum)*scale),0,GRID-1);
    }
}
