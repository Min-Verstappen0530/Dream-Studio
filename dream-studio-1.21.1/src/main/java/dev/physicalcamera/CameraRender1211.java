package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.physicalcamera.mixin.MinecraftTargetAccess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

public final class CameraRender1211 {
    static final float NEAR = .05f;
    private static final float REMIX_FAR = 1000f;
    private static final RemixDepthPass REMIX_FRAME = new RemixDepthPass();
    private static RenderTarget nativeTarget;
    private static double nativeHalfTan;
    private static double worldFov = 70;
    private static float handFov = 70;
    private static boolean remixColourReady;
    private static boolean remixCaptureFailed;

    private CameraRender1211() {}

    static double worldFov() { return worldFov; }
    public static float handFov() { return handFov; }

    public static void beginFrame() {
        FrameReadback.poll();
        remixColourReady = false;
        if (!CameraClient.hasZ8View() && !CameraClient.needsCleanWorldFrame()) REMIX_FRAME.close();
    }

    public static void remixColourReady() { remixColourReady = true; }

    public static void afterRemixWorld() {
        if (!remixColourReady || !CameraClient.needsCleanWorldFrame() || !RemixCompatibility.active()) return;
        remixColourReady = false;
        var client = Minecraft.getInstance();
        try {
            var source = REMIX_FRAME.copy(client.getMainRenderTarget(),RemixCompatibility.depthTexture());
            CameraClient.onRenderedFrame(source,REMIX_FAR,Math.tan(Math.toRadians(worldFov/2)),false);
            remixCaptureFailed = false;
        } catch (RuntimeException failure) {
            if (!remixCaptureFailed) {
                org.slf4j.LoggerFactory.getLogger("physical_camera").warn("Could not read the RTX Remix frame",failure);
                remixCaptureFailed = true;
            }
        }
    }

    public static boolean deferMonitorHands() {
        return CameraClient.showZ8Monitor() && (RemixCompatibility.active()
                || IrisCompatibility.packState() == IrisCompatibility.PackState.ACTIVE);
    }

    public static void drawMonitorHands(DeltaTracker delta) {
        if (!deferMonitorHands() || CameraClient.capturingWorldFrame()) return;
        var client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || client.options.hideGui || player.isSpectator()) return;
        var previousProjection = RenderSystem.getProjectionMatrix();
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        try {
            client.getMainRenderTarget().bindWrite(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(client.gameRenderer.getProjectionMatrix(handFov),VertexSorting.DISTANCE_TO_ORIGIN);
            client.gameRenderer.lightTexture().turnOnLightLayer();
            float time = delta.getGameTimeDeltaPartialTick(true);
            var buffers = client.renderBuffers().bufferSource();
            int light = client.getEntityRenderDispatcher().getPackedLightCoords(player,time);
            CameraClient.drawDeferredHands(() -> client.gameRenderer.itemInHandRenderer
                    .renderHandsWithItems(time,new PoseStack(),buffers,player,light));
        } finally {
            client.gameRenderer.lightTexture().turnOffLightLayer();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection,sorting);
        }
    }

    public static double fov(double original, boolean world) {
        if (!world) {
            handFov = (float)original;
            return original;
        }
        var window = Minecraft.getInstance().getWindow();
        double aspect = window.getWidth()/(double)Math.max(1,window.getHeight());
        double tangent = nativeHalfTan > 0 ? nativeHalfTan : CameraClient.liveProjectionHalfTan(aspect);
        worldFov = tangent > 0 ? Math.toDegrees(2*Math.atan(tangent)) : original;
        return worldFov;
    }

    public static void photoAspect(Matrix4f projection) {
        if (nativeHalfTan != 0) {
            double aspect = nativeTarget.width/(double)nativeTarget.height;
            projection.m00((float)(projection.m11()/aspect));
        }
    }

    public static void afterVanillaWorld() {
        if (CameraClient.needsCleanWorldFrame() && !RemixCompatibility.active()) {
            var client = Minecraft.getInstance();
            CameraClient.onRenderedFrame(client.getMainRenderTarget(),client.gameRenderer.getDepthFar(),
                    Math.tan(Math.toRadians(worldFov/2)),nativeHalfTan > 0);
        }
    }

    public static void nativePhoto(Runnable render) {
        double tangent = CameraClient.pendingFullPhotoHalfTan();
        if (tangent <= 0) {
            if (nativeTarget != null) {
                nativeTarget.destroyBuffers();
                nativeTarget = null;
            }
            return;
        }
        var size = CameraClient.pendingNativePhotoSize();
        var client = Minecraft.getInstance();
        var accessor = (MinecraftTargetAccess)client;
        var previous = client.getMainRenderTarget();
        double originalFov = worldFov;
        try {
            int limit = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (size.width > limit || size.height > limit) {
                throw new IllegalStateException("Photo dimensions exceed GL_MAX_TEXTURE_SIZE");
            }
            if (nativeTarget == null) {
                nativeTarget = new TextureTarget(size.width,size.height,true,Minecraft.ON_OSX);
            } else if (nativeTarget.width != size.width || nativeTarget.height != size.height) {
                nativeTarget.resize(size.width,size.height,Minecraft.ON_OSX);
            }
            nativeHalfTan = tangent;
            accessor.physicalCamera$setMainTarget(nativeTarget);
            nativeTarget.clear(Minecraft.ON_OSX);
            nativeTarget.bindWrite(true);
            CameraClient.renderCleanWorldFrame(render);
        } catch (RuntimeException | OutOfMemoryError failure) {
            CameraClient.fallbackToViewport(failure);
        } finally {
            nativeHalfTan = 0;
            worldFov = originalFov;
            accessor.physicalCamera$setMainTarget(previous);
            previous.bindWrite(true);
        }
    }
}
