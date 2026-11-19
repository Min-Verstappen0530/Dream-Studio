package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL30;

/** Copy the viewfinder crop at its processing resolution before reading it back. */
final class ViewfinderTarget implements AutoCloseable {
    private RenderTarget target;

    RenderTarget copy(RenderTarget source, Z8ReadRegion region, int width, int height, boolean depth,
                      Z8Depth.Viewport depthView) {
        boolean hasDepth = depth && source.useDepth;
        if (region.width() == width && region.height() == height && !depthView.remapped()) return null;
        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL30.glIsEnabled(GL30.GL_SCISSOR_TEST);
        try {
            if (target == null || target.useDepth != hasDepth) {
                close();
                target = new TextureTarget(width,height,hasDepth,Minecraft.ON_OSX);
            } else if (target.width != width || target.height != height) {
                target.resize(width,height,Minecraft.ON_OSX);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,source.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,target.frameBufferId);
            if (scissor) GL30.glDisable(GL30.GL_SCISSOR_TEST);
            blit(source,region,width,height,GL30.GL_COLOR_BUFFER_BIT,GL30.GL_LINEAR);
            if (hasDepth) {
                var depthRegion = depthView.depthRegion(source.width,source.height,region);
                blit(source,depthRegion,width,height,GL30.GL_DEPTH_BUFFER_BIT,GL30.GL_NEAREST);
            }
            return target;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,previousDraw);
            if (scissor) GL30.glEnable(GL30.GL_SCISSOR_TEST);
        }
    }

    private static void blit(RenderTarget source, Z8ReadRegion region, int width, int height, int mask, int filter) {
        int bottom = region.bottom(source.height);
        GL30.glBlitFramebuffer(region.x(),bottom,region.x()+region.width(),bottom+region.height(),
                0,0,width,height,mask,filter);
    }

    @Override public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
