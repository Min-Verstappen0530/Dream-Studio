package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.opengl.GL30;

record Z8Depth(int width, int height, float[] inverseMetres, float offsetX, float offsetY) {

    record Viewport(float scaleX, float scaleY, float offsetU, float offsetV) {
        static final Viewport FULL = new Viewport(1,1,0,0);
        boolean remapped() { return scaleX != 1 || scaleY != 1 || offsetU != 0 || offsetV != 0; }

        Z8ReadRegion depthRegion(int width, int height, Z8ReadRegion colour) {
            float xOffset = offsetU*width, yOffset = offsetV*height;
            int left = Math.clamp(Math.round(colour.x()*scaleX+xOffset),0,width-1);
            int bottom = Math.clamp(Math.round(colour.bottom(height)*scaleY+yOffset),0,height-1);
            int right = Math.clamp(Math.round((colour.x()+colour.width())*scaleX+xOffset),left+1,width);
            int top = Math.clamp(Math.round((height-colour.y())*scaleY+yOffset),bottom+1,height);
            return new Z8ReadRegion(left,height-top,right-left,top-bottom);
        }
    }

    Z8Depth(int width, int height, float[] inverseMetres) { this(width,height,inverseMetres,0,0); }

    static CompletableFuture<Z8Depth> capture(RenderTarget target, float farMetres) {
        return capture(target,farMetres,new Z8ReadRegion(0,0,target.width,target.height),IrisCompatibility.depthViewport());
    }

    static CompletableFuture<Z8Depth> capture(RenderTarget target, float farMetres, Z8ReadRegion colour, Viewport view) {
        var region = view.depthRegion(target.width,target.height,colour);
        if (!target.useDepth || farMetres <= CameraRender1211.NEAR) {
            return CompletableFuture.completedFuture(null);
        }
        int width = region.width(), height = region.height();
        float offsetX = colour.x()*view.scaleX()-region.x()+view.offsetU()*target.width;
        float offsetY = region.bottom(target.height)+height-(target.height-colour.y())*view.scaleY()
                -view.offsetV()*target.height;
        return FrameReadback.read(target,region,GL30.GL_DEPTH_COMPONENT,GL30.GL_FLOAT,buffer -> {
            var raw = buffer.asFloatBuffer();
            float[] values = new float[width*height];
            float nearInverse = 1/CameraRender1211.NEAR;
            float farInverse = 1/farMetres;
            boolean hasGeometry = false;
            for (int y = 0; y < height; y++) {
                int source = (height-1-y)*width;
                for (int x = 0; x < width; x++) {
                    float depth = raw.get(source+x);
                    if (depth >= 0 && depth < 1) {
                        // 1.21.1 stores conventional OpenGL depth, with 1 at the far plane.
                        values[y*width+x] = Math.fma(1-depth,nearInverse-farInverse,farInverse);
                        hasGeometry = true;
                    }
                }
            }
            return hasGeometry ? new Z8Depth(width,height,values,offsetX,offsetY) : null;
        });
    }

    double at(double u, double v) {
        return inverseMetres[indexAt(u,v)];
    }

    int indexAt(double u, double v) {
        int x = Math.clamp((int)(u*width+offsetX),0,width-1);
        int y = Math.clamp((int)(v*height+offsetY),0,height-1);
        return y*width+x;
    }
}
