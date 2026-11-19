package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.lwjgl.opengl.GL32;

final class FrameReadback {
    private static final ArrayDeque<Transfer<?>> PENDING = new ArrayDeque<>();

    private FrameReadback() {}

    static CompletableFuture<int[]> colour(RenderTarget source, Z8ReadRegion region) {
        return read(source,region,GL32.GL_BGRA,GL32.GL_UNSIGNED_INT_8_8_8_8_REV, data -> {
            int width = region.width();
            int height = region.height();
            int[] pixels = new int[width*height];
            var packed = data.asIntBuffer();
            for (int y = 0; y < height; y++) packed.get((height-1-y)*width,pixels,y*width,width);
            return pixels;
        });
    }

    static <T> CompletableFuture<T> read(RenderTarget source, Z8ReadRegion region, int format, int type,
                                         Function<ByteBuffer,T> decode) {
        int packBuffer = GL32.glGetInteger(GL32.GL_PIXEL_PACK_BUFFER_BINDING);
        int readFramebuffer = GL32.glGetInteger(GL32.GL_READ_FRAMEBUFFER_BINDING);
        int buffer = GL32.glGenBuffers();
        var future = new CompletableFuture<T>();
        try {
            GL32.glBindFramebuffer(GL32.GL_READ_FRAMEBUFFER,source.frameBufferId);
            GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER,buffer);
            GL32.glBufferData(GL32.GL_PIXEL_PACK_BUFFER,(long)region.width()*region.height()*4,GL32.GL_STREAM_READ);
            GL32.glReadPixels(region.x(),region.bottom(source.height),region.width(),region.height(),format,type,0L);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if (fence == 0) throw new IllegalStateException("Could not fence scene readback");
            PENDING.addLast(new Transfer<>(buffer,fence,decode,future));
        } catch (RuntimeException error) {
            GL32.glDeleteBuffers(buffer);
            future.completeExceptionally(error);
        } finally {
            GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER,packBuffer);
            GL32.glBindFramebuffer(GL32.GL_READ_FRAMEBUFFER,readFramebuffer);
        }
        return future;
    }

    static void poll() {
        while (!PENDING.isEmpty()) {
            var next = PENDING.getFirst();
            int status = GL32.glClientWaitSync(next.fence(),GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) return;
            PENDING.removeFirst();
            next.finish(status != GL32.GL_WAIT_FAILED);
        }
    }

    private record Transfer<T>(int buffer, long fence, Function<ByteBuffer,T> decode, CompletableFuture<T> future) {
        void finish(boolean ready) {
            int packBuffer = GL32.glGetInteger(GL32.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                if (future.isCancelled()) return;
                if (!ready) throw new IllegalStateException("Scene readback fence failed");
                GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER,buffer);
                var mapped = GL32.glMapBuffer(GL32.GL_PIXEL_PACK_BUFFER,GL32.GL_READ_ONLY);
                if (mapped == null) throw new IllegalStateException("Could not map scene readback");
                T decoded;
                try {
                    decoded = decode.apply(mapped.order(ByteOrder.nativeOrder()));
                } finally {
                    GL32.glUnmapBuffer(GL32.GL_PIXEL_PACK_BUFFER);
                }
                future.complete(decoded);
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            } finally {
                GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER,packBuffer);
                GL32.glDeleteSync(fence);
                GL32.glDeleteBuffers(buffer);
            }
        }
    }
}
