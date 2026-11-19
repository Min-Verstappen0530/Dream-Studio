package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.physicalcamera.mixin.PhotoTargetAttachments;
import java.lang.reflect.Field;

/** Reuse the photograph's textures without replacing the main target that Iris observes. */
public final class IrisPhotoTarget {
    private record Versions(Field color, Field depth) {}
    private static final Versions VERSIONS = findVersions();

    private IrisPhotoTarget() {}

    private static Versions findVersions() {
        try {
            var color = RenderTarget.class.getDeclaredField("iris$colorBufferVersion");
            var depth = RenderTarget.class.getDeclaredField("iris$depthBufferVersion");
            return color.trySetAccessible() && depth.trySetAccessible() ? new Versions(color,depth) : null;
        } catch (ReflectiveOperationException | SecurityException error) {
            return null;
        }
    }

    public static boolean supported() { return VERSIONS != null; }

    public static void swap(RenderTarget main, RenderTarget photo) {
        // Iris caches framebuffer attachments by these versions and clears history by dimensions.
        try {
            VERSIONS.color.setInt(main,VERSIONS.color.getInt(main)+1);
            VERSIONS.depth.setInt(main,VERSIONS.depth.getInt(main)+1);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Could not update Iris framebuffer bindings",error);
        }
        var mainAttachments = (PhotoTargetAttachments)main;
        var photoAttachments = (PhotoTargetAttachments)photo;
        var color = main.getColorTexture();
        var colorView = main.getColorTextureView();
        var depth = main.getDepthTexture();
        var depthView = main.getDepthTextureView();
        int width = main.width, height = main.height;

        mainAttachments.physicalCamera$setColor(photo.getColorTexture());
        mainAttachments.physicalCamera$setColorView(photo.getColorTextureView());
        mainAttachments.physicalCamera$setDepth(photo.getDepthTexture());
        mainAttachments.physicalCamera$setDepthView(photo.getDepthTextureView());
        main.width = photo.width;
        main.height = photo.height;
        photoAttachments.physicalCamera$setColor(color);
        photoAttachments.physicalCamera$setColorView(colorView);
        photoAttachments.physicalCamera$setDepth(depth);
        photoAttachments.physicalCamera$setDepthView(depthView);
        photo.width = width;
        photo.height = height;
    }
}
