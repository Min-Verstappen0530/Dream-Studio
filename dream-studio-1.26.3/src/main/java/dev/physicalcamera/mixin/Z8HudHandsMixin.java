package dev.physicalcamera.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.physicalcamera.CameraClient;
import dev.physicalcamera.GroundEquipmentPass;
import dev.physicalcamera.IrisPhotoTarget;
import dev.physicalcamera.Z8PhotoSize;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class Z8HudHandsMixin {
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Shadow @Final private GameRenderState gameRenderState;
    @Unique private Runnable physicalCamera$deferredHud;
    @Unique private boolean physicalCamera$postHookAvailable;
    @Unique private boolean physicalCamera$capturingFrame;
    @Unique private float physicalCamera$farMetres;
    @Unique private RenderTarget physicalCamera$screenTarget;
    @Unique private RenderTarget physicalCamera$screenColorBackup;
    @Unique private RenderTarget physicalCamera$irisPhotoTarget;
    @Unique private boolean physicalCamera$photoAttachmentsActive;
    @Unique private Matrix4f physicalCamera$screenProjection;
    @Unique private double physicalCamera$photoHalfTan;
    @Unique private double physicalCamera$liveHalfTan;
    @Unique private int physicalCamera$screenWidth, physicalCamera$screenHeight;

    @WrapMethod(method = "render", require = 0)
    private void physicalCamera$finishRenderSafely(Operation<Void> render) {
        try (var drops = physicalCamera$postHookAvailable && gameRenderState.shouldRenderLevel
                && mainRenderTarget.getDepthTextureView() != null
                ? GroundEquipmentPass.begin() : null) {
            physicalCamera$renderWithCamera(render);
        }
    }

    @Unique
    private void physicalCamera$renderWithCamera(Operation<Void> render) {
        if (!physicalCamera$postHookAvailable
                || !(CameraClient.needsCleanWorldFrame() || CameraClient.deferIrisCameraHands())
                || !gameRenderState.shouldRenderLevel) {
            if (!CameraClient.reuseIrisPhotoTarget()) physicalCamera$releaseIrisPhotoTarget();
            render.call();
            return;
        }
        physicalCamera$capturingFrame = true;
        try {
            physicalCamera$beginFullPhoto();
            CameraClient.renderCleanWorldFrame(() -> render.call());
        } finally {
            physicalCamera$capturingFrame = false;
            if (physicalCamera$screenTarget != null) {
                physicalCamera$restoreScreen();
                CameraClient.fallbackToViewport(new IllegalStateException("Photo frame ended before world readback"));
            }
            physicalCamera$deferredHud = null;
            physicalCamera$liveHalfTan = 0;
            if (!CameraClient.reuseIrisPhotoTarget()) physicalCamera$releaseIrisPhotoTarget();
        }
    }

    @WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
    private void physicalCamera$rememberWorldProjection(LevelRenderer level, GraphicsResourceAllocator allocator,
            boolean outline, CameraRenderState camera, GpuBufferSlice fog, Vector4f fogColor, boolean sky,
            boolean effects, Operation<Void> draw) {
        GroundEquipmentPass.worldProjection(fog);
        draw.call(level,allocator,outline,camera,fog,fogColor,sky,effects);
    }

    @WrapMethod(method = "renderLevel", require = 0)
    private void physicalCamera$projectLiveView(Operation<Void> render) {
        if (!physicalCamera$capturingFrame || physicalCamera$screenTarget != null) {
            render.call();
            return;
        }
        double aspect = mainRenderTarget.width/(double)mainRenderTarget.height;
        double halfTan = CameraClient.liveProjectionHalfTan(aspect);
        if (halfTan <= 0) {
            render.call();
            return;
        }
        var projection = gameRenderState.levelRenderState.cameraRenderState.projectionMatrix;
        var saved = new Matrix4f(projection);
        try {
            physicalCamera$liveHalfTan = halfTan;
            projection.m00((float)(1/(aspect*halfTan))).m11((float)(1/halfTan));
            render.call();
        } finally {
            projection.set(saved);
        }
    }

    @Unique
    private void physicalCamera$beginFullPhoto() {
        double halfTan = CameraClient.pendingFullPhotoHalfTan();
        if (halfTan <= 0 || !gameRenderState.shouldRenderLevel) {
            return;
        }
        var size = CameraClient.pendingNativePhotoSize();
        var device = RenderSystem.getDevice().getDeviceInfo();
        var colorTexture = mainRenderTarget.getColorTexture();
        var depthTexture = mainRenderTarget.getDepthTexture();
        if (colorTexture == null || depthTexture == null) {
            CameraClient.fallbackToViewport(new IllegalStateException("The active renderer has no colour or depth attachment"));
            return;
        }
        var color = colorTexture.getFormat();
        var depth = depthTexture.getFormat();
        int limit = Math.min(device.limits().maxTextureSizeForFormat(color),device.limits().maxTextureSizeForFormat(depth));
        if (limit < Math.max(size.width,size.height)) {
            CameraClient.fallbackToViewport(new IllegalStateException("The GPU texture size limit is "+limit));
            return;
        }
        TextureTarget photo;
        boolean iris = CameraClient.pendingIrisPhoto();
        boolean reuse = iris && CameraClient.reuseIrisPhotoTarget() && IrisPhotoTarget.supported();
        // Keep the main target's identity so renderers can observe texture changes through resize().
        try {
            photo = iris ? null : new TextureTarget("Dream Studio photo",size.width,size.height,color,depth);
            if (reuse) {
                var cached = physicalCamera$irisPhotoTarget;
                if (cached == null || cached.width != size.width || cached.height != size.height
                        || cached.getColorTexture().getFormat() != color || cached.getDepthTexture().getFormat() != depth) {
                    physicalCamera$releaseIrisPhotoTarget();
                    physicalCamera$irisPhotoTarget = new TextureTarget("Dream Studio Iris photo",size.width,size.height,color,depth);
                    org.slf4j.LoggerFactory.getLogger("physical_camera").info("Iris reusable photo target: {}×{}",size.width,size.height);
                }
                IrisPhotoTarget.swap(mainRenderTarget,physicalCamera$irisPhotoTarget);
                physicalCamera$photoAttachmentsActive = true;
            } else if (iris) {
                var backup = new TextureTarget("Dream Studio viewport",mainRenderTarget.width,mainRenderTarget.height,color,null);
                try {
                    backup.copyColorFrom(mainRenderTarget);
                } catch (RuntimeException error) {
                    backup.destroyBuffers();
                    throw error;
                }
                physicalCamera$screenColorBackup = backup;
            }
        } catch (RuntimeException error) {
            CameraClient.fallbackToViewport(error);
            return;
        }
        var window = gameRenderState.windowRenderState;
        var camera = gameRenderState.levelRenderState.cameraRenderState;
        physicalCamera$screenTarget = mainRenderTarget;
        physicalCamera$screenWidth = window.width;
        physicalCamera$screenHeight = window.height;
        physicalCamera$screenProjection = new Matrix4f(camera.projectionMatrix);
        physicalCamera$photoHalfTan = halfTan;
        if (!reuse && iris) {
            try {
                mainRenderTarget.resize(size.width,size.height);
            } catch (RuntimeException error) {
                physicalCamera$restoreScreen();
                CameraClient.fallbackToViewport(error);
                return;
            }
        } else if (!reuse) {
            mainRenderTarget = photo;
        }
        camera.projectionMatrix.setPerspective((float)(2*Math.atan(physicalCamera$photoHalfTan)),
                size.width/(float)size.height,camera.depthFar,Camera.PROJECTION_Z_NEAR,device.isZZeroToOne());
        window.width = size.width;
        window.height = size.height;
    }

    @Unique
    private void physicalCamera$releaseIrisPhotoTarget() {
        var target = physicalCamera$irisPhotoTarget;
        if (target != null) {
            physicalCamera$irisPhotoTarget = null;
            target.destroyBuffers();
        }
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void physicalCamera$closePhotoTarget(CallbackInfo callback) {
        physicalCamera$releaseIrisPhotoTarget();
    }

    @ModifyExpressionValue(method = "renderItemInHand", require = 0, at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;isHudHidden:Z"))
    private boolean physicalCamera$showMonitorHands(boolean hudHidden) {
        return hudHidden && !CameraClient.showZ8Monitor();
    }

    @WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void physicalCamera$deferHands(GameRenderer renderer, CameraRenderState camera, PlayerRenderState player,
                                           OptionsRenderState options, boolean effects, Operation<Void> renderHud) {
        if (physicalCamera$capturingFrame) {
            physicalCamera$farMetres = camera.depthFar;
            boolean showEffects = effects && !CameraClient.needsCleanWorldFrame();
            physicalCamera$deferredHud = () -> renderHud.call(renderer,camera,player,options,showEffects);
        } else {
            renderHud.call(renderer,camera,player,options,effects);
        }
    }

    @Inject(method = "render", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void physicalCamera$copyWorldFrame(CallbackInfo callback) {
        physicalCamera$postHookAvailable = true;
        GroundEquipmentPass.draw((GameRenderer)(Object)this,mainRenderTarget);
        if (!physicalCamera$capturingFrame) return;
        try {
            boolean fullPhoto = physicalCamera$screenTarget != null;
            CameraClient.onRenderedFrame(mainRenderTarget,physicalCamera$farMetres,
                    fullPhoto ? physicalCamera$photoHalfTan : physicalCamera$liveHalfTan,fullPhoto);
        } finally {
            physicalCamera$restoreScreen();
        }
        if (physicalCamera$deferredHud != null) {
            Runnable hud = physicalCamera$deferredHud;
            physicalCamera$deferredHud = null;
            CameraClient.drawDeferredHands(hud);
        }
    }

    @Unique
    private void physicalCamera$restoreScreen() {
        if (physicalCamera$screenTarget == null) {
            return;
        }
        var photo = mainRenderTarget;
        mainRenderTarget = physicalCamera$screenTarget;
        gameRenderState.windowRenderState.width = physicalCamera$screenWidth;
        gameRenderState.windowRenderState.height = physicalCamera$screenHeight;
        gameRenderState.levelRenderState.cameraRenderState.projectionMatrix.set(physicalCamera$screenProjection);
        physicalCamera$screenTarget = null;
        physicalCamera$screenProjection = null;
        physicalCamera$photoHalfTan = 0;
        if (physicalCamera$photoAttachmentsActive) {
            IrisPhotoTarget.swap(mainRenderTarget,physicalCamera$irisPhotoTarget);
            physicalCamera$photoAttachmentsActive = false;
        } else if (physicalCamera$screenColorBackup != null) {
            var backup = physicalCamera$screenColorBackup;
            physicalCamera$screenColorBackup = null;
            try {
                mainRenderTarget.resize(backup.width,backup.height);
                mainRenderTarget.copyColorFrom(backup);
            } finally {
                backup.destroyBuffers();
            }
        } else {
            photo.destroyBuffers();
        }
    }
}
