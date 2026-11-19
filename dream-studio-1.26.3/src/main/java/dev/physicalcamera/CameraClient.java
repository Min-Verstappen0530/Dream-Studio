package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static dev.physicalcamera.CameraKeys.Action.*;

public final class CameraClient implements ClientModInitializer {
    private static CameraClient instance;
    private static final Logger LOGGER = LoggerFactory.getLogger("physical_camera");
    private static final DateTimeFormatter PHOTO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS");
    private static final long NO_FRAME_TIMEOUT = 5_000_000_000L;
    private static boolean drawingDeferredHands;
    private record Shot(Capture capture, ClientLevel level, int cmosIso, LocalDateTime time) {}
    private record RenderedShot(ClientLevel level, Z24120Optics.Exposure optics, SensorFormat sensor,
                                double nativeHalfTan, boolean irisPhoto, double seconds, int iso, Z8PhotoSize size,
                                int samples, int workers, boolean openMount, PhotoFormat format, LocalDateTime time) {
        boolean rawOnly() { return optics == null && !openMount; }
        RenderedShot usingViewport() {
            return new RenderedShot(level,optics,sensor,0,false,seconds,iso,size,samples,workers,openMount,format,time);
        }
    }
    private record ColourFrame(int width, int height, int[] pixels) {}
    private record TimedFrame(long time, CompletableFuture<ColourFrame> colour, SceneRadiometry scene) {}
    private static final class ExposureWindow {
        final long opened = System.nanoTime();
        final long duration;
        final List<TimedFrame> frames = new ArrayList<>();
        CompletableFuture<Z8Depth> depth;
        final IrisCaptureProfile profile;
        final IrisCaptureProfile.Selection historySelection;
        final int historyFrames;
        int historyRemaining;
        long lastRender = opened;
        double halfTan;
        int width, height;
        ExposureWindow(RenderedShot request, IrisCaptureProfile.Selection selection) {
            duration = Math.round(request.seconds()*1_000_000_000);
            profile = request.irisPhoto() ? IrisCompatibility.captureProfile() : IrisCaptureProfile.SINGLE;
            historySelection = selection;
            historyFrames = request.irisPhoto() ? selection.frames(profile) : 0;
            historyRemaining = historyFrames;
        }

        boolean readyToSample(long now, int w, int h) {
            if (frames.isEmpty()) return true;
            int limit = (int)Math.clamp(64_000_000L/((long)w*h),1,8);
            return w == width && h == height && now-opened < duration && frames.size() < limit
                    && now-frames.getLast().time() >= Math.max(33_333_333L,duration/limit);
        }

        double[] weights() {
            double[] weights = new double[frames.size()];
            long lower = 0;
            for (int i = 0; i < weights.length; i++) {
                long upper = i+1 == weights.length ? duration : Math.min(duration,
                        (frames.get(i).time()-opened+frames.get(i+1).time()-opened)/2);
                weights[i] = (upper-lower)/(double)duration;
                lower = upper;
            }
            return weights;
        }
    }
    private Shot shot;
    private RenderedShot waitingFrame;
    private ExposureWindow shutterWindow;
    private AtomicInteger renderedRows;
    private int photoRows;
    private boolean exporting;
    private final Z8Settings z8 = new Z8Settings();
    private final List<Path> photos = new ArrayList<>();
    private boolean pendingZ8;
    private boolean pendingSettings;
    private int lastHeld = -1;
    private CameraKeys keys;
    private CameraPreferences preferences;

    @Override
    public void onInitializeClient() {
        instance = this;
        AssemblyScreen.register();
        keys = new CameraKeys();
        preferences = new CameraPreferences(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("physical-camera.properties"));
        z8.setIsoExtension(preferences.extendedIso);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(literal("physicalcamera")
                        .then(literal("z8").executes(context -> {
                            int focal = heldCamera(context.getSource().getClient());
                            if (focal < 0) {
                                context.getSource().sendError(Component.translatable("screen.physical_camera.capture.hold"));
                                return 0;
                            }
                            pendingZ8 = true;
                            return 1;
                        }))
                        .then(literal("raw").executes(context -> queueRaw(context.getSource())))
                        .then(literal("settings").executes(context -> {
                            pendingSettings = true;
                            return 1;
                        }))
                        .then(literal("assembly").executes(context ->
                                openAssembly(context.getSource().getClient()) ? 1 : 0))
                        .then(preset("standard", Optics.Lens.singlet(44, 0)))
                        .then(preset("wide", Optics.Lens.singlet(28, 0)))
                        .then(preset("scatter", Optics.Lens.singlet(44, 0.0025)))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(level instanceof ClientLevel) || player != Minecraft.getInstance().player) {
                return InteractionResult.PASS;
            }
            return useZ8(player.getItemInHand(hand));
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(level instanceof ClientLevel) || player != Minecraft.getInstance().player) {
                return InteractionResult.PASS;
            }
            return useZ8(player.getItemInHand(hand));
        });
    }

    private InteractionResult useZ8(ItemStack item) {
        int focal = focalOf(item);
        if (focal < 0) {
            return InteractionResult.PASS;
        }
        pendingZ8 = true;
        return InteractionResult.FAIL;
    }

    public static int focalOf(ItemStack item) {
        if (CameraEquipment.isBody(item)) {
            var lens = CameraEquipment.lensType(CameraEquipment.fittedLens(item));
            return lens == null ? 0 : lens.wide;
        }
        return CameraEquipment.legacyFocal(item);
    }

    static int heldCamera(Minecraft client) {
        return focalOf(heldCameraStack(client));
    }

    private static ItemStack heldCameraStack(Minecraft client) {
        if (client.player == null) return ItemStack.EMPTY;
        var main = client.player.getMainHandItem();
        return focalOf(main) >= 0 ? main : client.player.getOffhandItem();
    }

    public static boolean showZ8Monitor() {
        return Minecraft.getInstance().gui.screen() instanceof Z8Screen view && view.monitor();
    }

    public static boolean hasZ8View() {
        return Minecraft.getInstance().gui.screen() instanceof Z8Screen;
    }

    public static boolean drawingDeferredHands() { return drawingDeferredHands; }

    public static boolean deferIrisCameraHands() {
        var client = Minecraft.getInstance();
        return client.player != null && client.options.getCameraType().isFirstPerson()
                && (isCameraEquipment(client.player.getMainHandItem()) || isCameraEquipment(client.player.getOffhandItem()))
                && IrisCompatibility.packState() == IrisCompatibility.PackState.ACTIVE;
    }

    public static boolean isCameraEquipment(ItemStack stack) {
        return !stack.isEmpty() && (focalOf(stack) >= 0
                || BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("physical_camera"));
    }

    public static void drawDeferredHands(Runnable draw) {
        drawingDeferredHands = true;
        try {
            draw.run();
        } finally {
            drawingDeferredHands = false;
        }
    }

    public static double pendingFullPhotoHalfTan() {
        return instance == null ? 0 : instance.fullPhotoHalfTan();
    }

    public static Z8PhotoSize pendingNativePhotoSize() {
        if (instance == null || instance.waitingFrame == null) return Z8PhotoSize.FULL;
        var shot = instance.waitingFrame;
        return shot.size().renderSize(shot.irisPhoto());
    }

    public static boolean pendingIrisPhoto() {
        return instance != null && instance.waitingFrame != null && instance.waitingFrame.irisPhoto();
    }

    public static boolean accumulatingIrisHistory() {
        return pendingIrisPhoto() && instance.shutterWindow.historyFrames > 0;
    }

    public static boolean reuseIrisPhotoTarget() {
        return pendingIrisPhoto() && instance.shutterWindow.profile.shader() != null;
    }

    public static double liveProjectionHalfTan(double aspect) {
        // The rear LCD reads the main scene at its original FOV. Alternating camera
        // projections in that renderer contaminates shader packs' temporal history.
        if (showZ8Monitor()) return 0;
        if (instance != null && instance.waitingFrame != null && !instance.waitingFrame.rawOnly()) {
            var shot = instance.waitingFrame;
            return shot.openMount() ? 0 : shot.size().viewportHalfTan(shot.optics().projectionFocal(),aspect);
        }
        if (instance != null && instance.busy()) return 0;
        return Minecraft.getInstance().gui.screen() instanceof Z8Screen view ? view.projectionHalfTan(aspect) : 0;
    }

    private static final ThreadLocal<Boolean> CLEAN_WORLD_PASS = new ThreadLocal<>();

    public static boolean needsCleanWorldFrame() { return hasZ8View() || instance != null && instance.waitingFrame != null; }

    public static boolean capturingWorldFrame() { return Boolean.TRUE.equals(CLEAN_WORLD_PASS.get()); }

    public static void renderCleanWorldFrame(Runnable render) {
        if (capturingWorldFrame()) {
            render.run();
            return;
        }
        CLEAN_WORLD_PASS.set(Boolean.TRUE);
        try {
            render.run();
        } finally {
            CLEAN_WORLD_PASS.remove();
        }
    }

    public static boolean ownsDepthReadback(GpuBuffer buffer) { return Z8Depth.ownsReadback(buffer); }

    public static void fallbackToViewport(Throwable error) {
        if (instance != null) {
            instance.useViewport(Minecraft.getInstance(),error);
        }
    }

    public static void onRenderedFrame(RenderTarget target, float farMetres, double worldHalfTan, boolean nativePhoto) {
        var client = Minecraft.getInstance();
        if (client.level == null || !client.gameRenderer.mainCamera().isInitialized()) return;
        float fov = client.gameRenderer.mainCamera().getFov();
        double halfTan = worldHalfTan > 0 ? worldHalfTan : Math.tan(Math.toRadians(Math.clamp(fov,10,170)/2));
        if (client.gui.screen() instanceof Z8Screen view) {
            view.worldRendered(target,halfTan,farMetres,nativePhoto);
        } else if (instance != null && instance.waitingFrame != null) {
            instance.recordFrame(client,target,halfTan,farMetres,nativePhoto);
        }
    }

    public static float z8Lift() {
        return Minecraft.getInstance().gui.screen() instanceof Z8Screen view ? view.lift() : 0;
    }

    public static double zoomFraction(int defaultFocal) {
        if (Minecraft.getInstance().gui.screen() instanceof Z8Screen view) {
            return view.zoomFraction();
        }
        if (instance != null && instance.lastHeld == defaultFocal) {
            return instance.z8.lens.fraction(instance.z8.focal);
        }
        return ZoomLens.fromMountedFocal(defaultFocal).fraction(defaultFocal);
    }

    List<Path> photos() { return photos; }
    boolean busy() { return shot != null || exporting || waitingFrame != null || renderedRows != null; }
    String progress() {
        if (waitingFrame != null) {
            if (accumulatingIrisHistory()) {
                int total = shutterWindow.historyFrames;
                return I18n.get("screen.physical_camera.capture.accumulating", total-shutterWindow.historyRemaining, total);
            }
            double remaining = Math.max(0,shutterWindow.duration-(System.nanoTime()-shutterWindow.opened))*1e-9;
            return I18n.get("screen.physical_camera.capture.remaining", String.format(java.util.Locale.ROOT,"%.2f",remaining));
        }
        if (renderedRows != null && renderedRows.get() < photoRows) {
            return I18n.get("screen.physical_camera.capture.rendering", (100*renderedRows.get()/photoRows)+"%");
        }
        return I18n.get("screen.physical_camera.capture.saving");
    }
    CameraKeys keys() { return keys; }
    CameraPreferences preferences() { return preferences; }
    void setIsoExtension(boolean enabled) throws IOException {
        preferences.extendedIso = enabled;
        z8.setIsoExtension(enabled);
        preferences.save();
    }

    static Z8Settings assemblySettings() { return instance.z8; }

    boolean openAssembly(Minecraft client) {
        if (client.player == null) return false;
        if (!CameraEquipment.canAssemble(client.player.getMainHandItem())
                && !CameraEquipment.canAssemble(client.player.getOffhandItem())) {
            client.player.sendSystemMessage(Component.translatable("screen.physical_camera.assembly.hold"));
            return false;
        }
        if (!ClientPlayNetworking.canSend(CameraEquipment.OpenAssembly.TYPE)) {
            client.player.sendSystemMessage(Component.translatable("screen.physical_camera.assembly.server"));
            return false;
        }
        ClientPlayNetworking.send(CameraEquipment.OpenAssembly.INSTANCE);
        return true;
    }

    boolean shootZ8(Minecraft client, Z8Settings settings, Z8Settings.Exposure exposure) {
        if (busy() || client.level == null || !client.gameRenderer.mainCamera().isInitialized()) {
            return false;
        }
        boolean openMount = heldCamera(client) == 0;
        var optics = openMount ? null : settings.lensExposure(exposure.aperture());
        var mode = openMount ? CameraPreferences.RenderMode.VIEWPORT
                : captureMode(client,exposure.seconds(),settings.photoSize);
        double nativeHalfTan = mode == CameraPreferences.RenderMode.VIEWPORT ? 0
                : settings.photoSize.halfTan(optics.projectionFocal());
        boolean irisPhoto = mode == CameraPreferences.RenderMode.IRIS;
        if (mode == CameraPreferences.RenderMode.NATIVE && settings.photoSize == Z8PhotoSize.MF_FULL) {
            message(client,Component.translatable("screen.physical_camera.capture.render_size", Z8PhotoSize.MF_UHD.label, settings.photoSize.label));
        }
        waitingFrame = new RenderedShot(client.level,optics,settings.photoSize.sensor(),nativeHalfTan,irisPhoto,exposure.seconds(),exposure.iso(),
                settings.photoSize,preferences.samples(),preferences.workers(),openMount,settings.photoFormat,LocalDateTime.now());
        shutterWindow = new ExposureWindow(waitingFrame,preferences.irisHistory);
        return true;
    }

    private CameraPreferences.RenderMode captureMode(Minecraft client, double seconds, Z8PhotoSize size) {
        var mode = preferences.renderMode;
        if (mode == CameraPreferences.RenderMode.VIEWPORT) return mode;
        if (seconds > 1.0/30) {
            if (mode == CameraPreferences.RenderMode.IRIS) message(client,Component.translatable("screen.physical_camera.capture.long_exposure"));
            return CameraPreferences.RenderMode.VIEWPORT;
        }
        var iris = IrisCompatibility.packState();
        if (iris == IrisCompatibility.PackState.INACTIVE) return CameraPreferences.RenderMode.NATIVE;
        if (mode != CameraPreferences.RenderMode.IRIS) return CameraPreferences.RenderMode.VIEWPORT;
        if (iris == IrisCompatibility.PackState.UNKNOWN) {
            message(client,Component.translatable("screen.physical_camera.capture.iris_fallback"));
            return CameraPreferences.RenderMode.VIEWPORT;
        }
        if (size != size.renderSize(true)) {
            message(client,Component.translatable("screen.physical_camera.capture.render_size", size.renderSize(true).label, size.label));
        }
        return mode;
    }

    void cancelPendingFrame() {
        if (waitingFrame != null && !waitingFrame.rawOnly()) {
            clearShutter();
        }
    }

    private void clearShutter() {
        waitingFrame = null;
        shutterWindow = null;
    }

    private int queueRaw(FabricClientCommandSource source) {
        var client = source.getClient();
        var view = client.gameRenderer.mainCamera();
        if (busy() || client.level == null || !view.isInitialized()) {
            source.sendError(Component.translatable("screen.physical_camera.capture.raw_not_ready"));
            return 0;
        }
        var size = Z8PhotoSize.FULL;
        var target = client.gameRenderer.mainRenderTarget();
        double aspect = target.width/(double)target.height;
        double halfTan = Math.tan(Math.toRadians(Math.clamp(view.getFov(),10,170)/2))
                * Math.min(1,aspect/size.aspect());
        var mode = captureMode(client,1.0/60,size);
        waitingFrame = new RenderedShot(client.level,null,SensorFormat.FULL_FRAME_45MP,
                mode == CameraPreferences.RenderMode.VIEWPORT ? 0 : halfTan,
                mode == CameraPreferences.RenderMode.IRIS,1.0/60,
                RecordingMedia.BASE_ISO,size,preferences.samples(),preferences.workers(),false,PhotoFormat.RAW,LocalDateTime.now());
        shutterWindow = new ExposureWindow(waitingFrame,preferences.irisHistory);
        source.sendFeedback(Component.translatable("screen.physical_camera.capture.raw_start"));
        return 1;
    }

    private double fullPhotoHalfTan() {
        return waitingFrame != null && waitingFrame.level() == Minecraft.getInstance().level
                ? waitingFrame.nativeHalfTan() : 0;
    }

    private void useViewport(Minecraft client, Throwable error) {
        if (waitingFrame == null || waitingFrame.nativeHalfTan() <= 0) return;
        waitingFrame = waitingFrame.usingViewport();
        shutterWindow = new ExposureWindow(waitingFrame,preferences.irisHistory);
        LOGGER.warn("Native photo target unavailable. Using the viewport for this exposure.",error);
        message(client,Component.translatable("screen.physical_camera.capture.buffer_fallback"));
    }

    void recordFrame(Minecraft client, RenderTarget target, double halfTan, float farMetres, boolean nativePhoto) {
        RenderedShot request = waitingFrame;
        ExposureWindow window = shutterWindow;
        if (request == null || request.level() != client.level || nativePhoto != (request.nativeHalfTan() > 0)) {
            return;
        }
        long now = System.nanoTime();
        window.lastRender = now;
        if (nativePhoto && window.historyRemaining > 0) {
            window.historyRemaining--;
            return;
        }
        try {
            if (window.readyToSample(now,target.width,target.height)) {
                if (window.frames.isEmpty()) {
                    window.width = target.width;
                    window.height = target.height;
                    window.halfTan = halfTan;
                    window.depth = request.rawOnly() || request.openMount() ? CompletableFuture.completedFuture(null)
                            : Z8Depth.capture(target,farMetres).exceptionally(error -> {
                        LOGGER.warn("Could not read the game depth buffer. Using the focus plane for this exposure.",error);
                        return null;
                    });
                }
                var colour = new CompletableFuture<ColourFrame>();
                var scene = SceneRadiometry.measure(client.level,client.gameRenderer.mainCamera(),halfTan,
                        target.width/(double)target.height);
                window.frames.add(new TimedFrame(now,colour,scene));
                Screenshot.takeScreenshot(target,image -> {
                    try (image) {
                        colour.complete(new ColourFrame(image.getWidth(),image.getHeight(),image.getPixels()));
                    } catch (RuntimeException error) {
                        colour.completeExceptionally(error);
                    }
                });
            }
            if (nativePhoto || window.duration <= 33_333_333L || now-window.opened >= window.duration) {
                sealExposure(client,request,window);
            }
        } catch (RuntimeException error) {
            clearShutter();
            finishRendered(client,null,error);
        }
    }

    private void sealExposure(Minecraft client, RenderedShot request, ExposureWindow window) {
        clearShutter();
        var rows = new AtomicInteger();
        photoRows = request.size().height;
        renderedRows = rows;
        LOGGER.info("Camera shutter: {} s, {} frame(s), last sample at {} ms",request.seconds(),window.frames.size(),
                (window.frames.getLast().time()-window.opened)/1_000_000);
        LOGGER.info("Camera render mode: {}",request.irisPhoto() ? "Iris" : request.nativeHalfTan()>0 ? "native" : "viewport");
        if (request.irisPhoto()) {
            LOGGER.info("Iris capture history: {} frames (profile {}, setting {})",
                    window.historyFrames,window.profile.name(),window.historySelection);
        }
        var pending = window.frames.stream().map(TimedFrame::colour).toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(pending).thenCombine(window.depth,(ignored,z) -> z)
                .whenComplete((z,error) -> {
                    if (error == null) {
                        writeRendered(client,request,() -> exposedFrame(window,z,request.nativeHalfTan()>0),rows);
                    } else {
                        client.execute(() -> finishRendered(client,null,error));
                    }
                });
    }

    private static Z8Frame exposedFrame(ExposureWindow window, Z8Depth depth, boolean nativePhoto) {
        int[][] frames = new int[window.frames.size()][];
        for (int i = 0; i < frames.length; i++) {
            var colour = window.frames.get(i).colour().join();
            if (colour.width() != window.width || colour.height() != window.height) {
                throw new IllegalStateException("Unexpected photo source size");
            }
            frames[i] = colour.pixels();
        }
        SceneRadiometry[] scenes = window.frames.stream().map(TimedFrame::scene).toArray(SceneRadiometry[]::new);
        float[] integrated = frames.length == 1 ? null
                : Z8Frame.integrateFrames(window.width,window.height,frames,window.weights(),scenes);
        return new Z8Frame(window.width,window.height,frames[0],window.halfTan,depth,nativePhoto,scenes[0],integrated);
    }

    private void writeRendered(Minecraft client, RenderedShot request, Supplier<Z8Frame> snapshot, AtomicInteger rows) {
        var workers = new ForkJoinPool(request.workers());
        long seed = ThreadLocalRandom.current().nextLong();
        long started = System.nanoTime();
        var render = CompletableFuture.supplyAsync(() -> {
            var frame = snapshot.get();
            LOGGER.info("Camera source {}×{}, photo {}×{}{}",frame.width(),frame.height(),
                    request.size().width,request.size().height,
                    request.rawOnly() ? " RAW" : request.openMount() ? " (open mount)" : " at "+request.optics().focalLength()+" mm");
            return Z8ImageProcessor.expose(frame,request.optics(),request.size(),request.sensor(),
                    request.seconds(),request.iso(),seed,rows,request.samples(),request.openMount());
        },workers);
        render.thenCompose(image -> {
            if (!request.rawOnly() && !request.openMount()) {
                LOGGER.info("Camera CPU render: {} samples, {} workers, {} ms",request.samples(),
                        request.workers(),(System.nanoTime()-started)/1_000_000);
            }
            Path stem;
            try {
                stem = photoStem(client,request.time());
            } catch (IOException error) {
                return CompletableFuture.<Path>failedFuture(error);
            }
            var raw = request.format().raw ? writeAsync(workers,() -> RawDng.write(photoFile(stem,"dng"),
                    request.size().width,request.size().height,image.mosaic(),request.sensor())) : CompletableFuture.completedFuture(null);
            var png = request.format().png ? writeAsync(workers,() -> Capture.writeImage(image.cmos(),photoFile(stem,"png")))
                    : CompletableFuture.completedFuture(null);
            return CompletableFuture.allOf(raw,png).thenApply(ignored -> stem);
        }).whenComplete((directory,error) -> {
            workers.shutdown();
            if (error == null && !request.rawOnly() && !request.openMount()) {
                LOGGER.info("Camera CPU: {} samples, {} workers, {} ms including file export",
                        request.samples(),request.workers(),(System.nanoTime()-started)/1_000_000);
            }
            client.execute(() -> {
                if (request.rawOnly() && error == null) {
                    renderedRows = null;
                    message(client,Component.translatable("screen.physical_camera.capture.raw_saved", photoFile(directory,"dng").toAbsolutePath()));
                } else {
                    finishRendered(client,directory,error);
                }
            });
        });
    }


    @FunctionalInterface
    private interface ImageWriter { void write() throws IOException; }

    private static CompletableFuture<Void> writeAsync(ForkJoinPool pool, ImageWriter writer) {
        return CompletableFuture.runAsync(() -> {
            try {
                writer.write();
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        },pool);
    }

    private void finishRendered(Minecraft client, Path directory, Throwable error) {
        renderedRows = null;
        finishWriting(client,directory,error);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> preset(String name, Optics.Lens lens) {
        var shutter = argument("shutterSeconds",DoubleArgumentType.doubleArg(0.0001,2))
                .executes(context -> start(context,lens,RecordingMedia.BASE_ISO));
        shutter.then(argument("iso",IntegerArgumentType.integer(RecordingMedia.BASE_ISO,RecordingMedia.MAX_ISO))
                .executes(context -> start(context,lens,IntegerArgumentType.getInteger(context,"iso"))));
        return literal(name)
                .executes(context -> start(context.getSource(),lens,2,2.8,1.0/60,RecordingMedia.BASE_ISO))
                .then(argument("focusMeters",DoubleArgumentType.doubleArg(0.12,128))
                        .then(argument("fNumber",DoubleArgumentType.doubleArg(1.8,22)).then(shutter)));
    }

    private int start(CommandContext<FabricClientCommandSource> context, Optics.Lens lens, int cmosIso) {
        return start(context.getSource(),lens,DoubleArgumentType.getDouble(context,"focusMeters"),
                DoubleArgumentType.getDouble(context,"fNumber"),
                DoubleArgumentType.getDouble(context,"shutterSeconds"),cmosIso);
    }

    private int start(FabricClientCommandSource source, Optics.Lens lens, double focusMeters,
                      double fNumber, double seconds, int cmosIso) {
        String error = begin(source.getClient(),lens,focusMeters,fNumber,seconds,cmosIso);
        if (error != null) {
            source.sendError(Component.translatable(error));
            return 0;
        }
        source.sendFeedback(Component.translatable("screen.physical_camera.capture.start", cmosIso));
        return 1;
    }

    private String begin(Minecraft client, Optics.Lens lens, double focusMeters,
                         double fNumber, double seconds, int cmosIso) {
        if (busy()) {
            return "screen.physical_camera.capture.busy";
        }
        var view = client.gameRenderer.mainCamera();
        if (client.level == null || !view.isInitialized()) {
            return "screen.physical_camera.capture.no_world";
        }
        Optics.Camera optics = lens.focus(focusMeters * 1000, fNumber);
        var scene = new WorldScene(client.level, view);
        long seed = ThreadLocalRandom.current().nextLong();
        var capture = new Capture(optics,scene,96,64,12,seconds,seed);
        shot = new Shot(capture,client.level,cmosIso,LocalDateTime.now());
        return null;
    }

    private void tick(Minecraft client) {
        if (waitingFrame != null && waitingFrame.level() != client.level) {
            clearShutter();
        } else if (waitingFrame != null && shutterWindow.frames.isEmpty()
                && System.nanoTime()-shutterWindow.lastRender >= NO_FRAME_TIMEOUT) {
            clearShutter();
            message(client,Component.translatable("screen.physical_camera.capture.no_frame"));
        } else if (waitingFrame != null && !shutterWindow.frames.isEmpty()
                && System.nanoTime()-shutterWindow.opened >= shutterWindow.duration) {
            sealExposure(client,waitingFrame,shutterWindow);
        }
        var stack = heldCameraStack(client);
        int held = focalOf(stack);
        if (held >= 0) z8.setSensor(CameraEquipment.sensor(stack));
        if (held > 0 && held != lastHeld) {
            z8.setLens(ZoomLens.fromMountedFocal(held),held);
        }
        lastHeld = held;
        if (pendingZ8) {
            pendingZ8 = false;
            if (held >= 0 && client.level != null && client.gui.screen() == null) {
                client.gui.setScreen(new Z8Screen(this,z8,false));
            }
        }
        boolean canOpen = held >= 0 && client.level != null && client.gui.screen() == null;
        boolean raise = keys.get(RAISE).consumeClick();
        boolean view = keys.get(VIEW).consumeClick();
        boolean openSettings = keys.get(SETTINGS).consumeClick();
        boolean assembly = keys.get(ASSEMBLY).consumeClick();
        for (var action : CameraKeys.Action.values()) {
            while (keys.get(action).consumeClick()) {}
        }
        if ((pendingSettings && client.gui.screen() == null) || (canOpen && openSettings)) {
            pendingSettings = false;
            client.gui.setScreen(new CameraSettingsScreen(this,null));
        } else if (canOpen && assembly) {
            openAssembly(client);
        } else if (canOpen && (raise || view)) {
            client.gui.setScreen(new Z8Screen(this,z8,view));
        }
        Shot current = shot;
        if (current == null) {
            return;
        }
        if (client.level != current.level()) {
            shot = null;
            message(client, Component.translatable("screen.physical_camera.capture.world_changed"));
            return;
        }
        if (!current.capture().advance(48)) {
            return;
        }

        shot = null;
        exporting = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                Path destination = photoStem(client,current.time());
                current.capture().writeCmosFiles(photoFile(destination,"png"),photoFile(destination,"dng"),current.cmosIso());
                return destination;
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }).whenComplete((destination, error) -> client.execute(() -> {
            exporting = false;
            finishWriting(client,destination,error);
        }));
    }

    private static Path photoStem(Minecraft client, LocalDateTime time) throws IOException {
        Path root = client.gameDirectory.toPath().resolve("screenshots");
        Files.createDirectories(root);
        Path stem = root.resolve(PHOTO_TIME.format(time));
        for (int offset = 1; Files.exists(photoFile(stem,"png")) || Files.exists(photoFile(stem,"dng")); offset++) {
            stem = root.resolve(PHOTO_TIME.format(time.plusNanos(offset*1_000_000L)));
        }
        return stem;
    }

    static Path photoFile(Path stem, String extension) { return stem.resolveSibling(stem.getFileName()+"."+extension); }

    private void finishWriting(Minecraft client, Path destination, Throwable error) {
        if (error == null) {
            photos.add(destination);
            message(client,Component.translatable("screen.physical_camera.capture.saved", destination.toAbsolutePath()));
        } else {
            LOGGER.error("Could not save camera images",error);
            message(client,Component.translatable("screen.physical_camera.capture.save_failed"));
        }
    }

    private static void message(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.sendSystemMessage(text);
        }
    }
}
