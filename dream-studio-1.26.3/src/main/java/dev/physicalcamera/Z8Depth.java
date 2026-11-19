package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;

record Z8Depth(int width, int height, float[] inverseMetres, float offsetX, float offsetY) {
    private static final ThreadLocal<GpuBuffer> CAMERA_READBACK = new ThreadLocal<>();

    record Viewport(float scaleX, float scaleY, float offsetU, float offsetV) {
        static final Viewport FULL = new Viewport(1,1,0,0);
        boolean remapped() { return scaleX != 1 || scaleY != 1 || offsetU != 0 || offsetV != 0; }

        Z8ReadRegion depthRegion(int width, int height, Z8ReadRegion colour) {
            int left = Math.round(colour.x()*scaleX);
            int bottom = Math.round(colour.bottom(height)*scaleY);
            int right = Math.max(left+1,Math.round((colour.x()+colour.width())*scaleX));
            int top = Math.max(bottom+1,Math.round((height-colour.y())*scaleY));
            return new Z8ReadRegion(left,height-top,Math.min(width,right)-left,Math.min(height,top)-bottom);
        }
    }

    Z8Depth(int width, int height, float[] inverseMetres) { this(width,height,inverseMetres,0,0); }

    static boolean ownsReadback(GpuBuffer buffer) { return CAMERA_READBACK.get() == buffer; }

    static CompletableFuture<Z8Depth> capture(RenderTarget target, float farMetres) {
        var viewport = IrisCompatibility.depthViewport(target.width,target.height);
        return capture(target,farMetres,new Z8ReadRegion(0,0,target.width,target.height),viewport);
    }

    static CompletableFuture<Z8Depth> capture(RenderTarget target, float farMetres, Z8ReadRegion colour, Viewport view) {
        var region = view.depthRegion(target.width,target.height,colour);
        var texture = target.getDepthTexture();
        if (texture == null || texture.getFormat() != GpuFormat.D32_FLOAT
                || farMetres <= Camera.PROJECTION_Z_NEAR) {
            return CompletableFuture.completedFuture(null);
        }
        int width = region.width(), height = region.height();
        float offsetX = colour.x()*view.scaleX()-region.x()+view.offsetU()*target.width;
        float offsetY = region.bottom(target.height)+height-(target.height-colour.y())*view.scaleY()
                -view.offsetV()*target.height;
        var result = new CompletableFuture<Z8Depth>();
        var device = RenderSystem.getDevice();
        var buffer = device.createBuffer(() -> "Z 8 scene depth",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,(long)width*height*Float.BYTES);
        CAMERA_READBACK.set(buffer);
        try {
            device.createCommandEncoder().copyTextureToBuffer(texture,buffer,0,() -> {
                try (buffer; var mapped = result.isCancelled() ? null : buffer.map(true,false)) {
                    if (mapped == null) return;
                    var raw = mapped.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
                    float[] values = new float[width*height];
                    float nearInverse = 1/Camera.PROJECTION_Z_NEAR;
                    float farInverse = 1/farMetres;
                    boolean hasGeometry = false;
                    for (int y = 0; y < height; y++) {
                        int source = (height-1-y)*width;
                        for (int x = 0; x < width; x++) {
                            float depth = raw.get(source+x);
                            if (depth > 0 && depth <= 1) {
                                values[y*width+x] = Math.fma(depth,nearInverse-farInverse,farInverse);
                                hasGeometry = true;
                            }
                        }
                    }
                    result.complete(hasGeometry ? new Z8Depth(width,height,values,offsetX,offsetY) : null);
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            },0,region.x(),region.bottom(target.height),width,height);
        } catch (RuntimeException error) {
            buffer.close();
            result.completeExceptionally(error);
        } finally {
            CAMERA_READBACK.remove();
        }
        return result;
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
