package dev.physicalcamera;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.physicalcamera.mixin.Z8CameraFov;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

final class Z8Screen extends Screen {
    private static final int WHITE = 0xfff3f2ed;
    private static final int PLACEHOLDER = 0xff898c86;
    private static final int RED = 0xffef6655;
    private static final int GREEN = 0xff92db95;
    private static final int BLACK = 0xff101215;
    private static final int BAR = 0xe0101215;
    private static final int UI_HEIGHT = 204;
    private static final int MAX_FOCUS_RANGE = 512;
    private static final double FINITE_FOCUS_RAIL = .985;
    private static final long LIFT_NANOS = 240_000_000L;
    private static final Identifier PLAYBACK = Identifier.fromNamespaceAndPath("physical_camera", "z8_playback");
    private static final List<String> COMPENSATIONS = labels(19,i -> evLabel((i-9)/3.0));
    private static final List<String> FORMATS = labels(PhotoFormat.values().length,i -> PhotoFormat.values()[i].label);

    enum Parameter {
        MODE, SHUTTER, APERTURE, ISO, FOCUS, DISTANCE, METER, FOCAL, COMPENSATION, GRID, LEVEL, SIZE, FORMAT;

        String label() {
            return I18n.get("screen.physical_camera.viewfinder."+name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record Area(double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return px >= x && px < x+width && py >= y && py < y+height;
        }
    }

    private record View(Area area, int width) {
        double scale() { return area.height / UI_HEIGHT; }
        double x(double mouseX) { return (mouseX-area.x)/scale(); }
        double y(double mouseY) { return (mouseY-area.y)/scale(); }
    }

    private record Readout(Parameter parameter, String label, Area area, boolean enabled) {}
    private record Options(Readout readout, List<String> items, int current) {}
    private record Popup(Options options, int scroll, int x, int y, int width, int rows, Component pinnedLabel) {
        private static final int HEADER = 16;
        private static final int ROW = 13;
        private static final int VISIBLE = 5;

        Area item(int row) {
            return new Area(x+3,itemsY()+row*ROW,width-10,ROW);
        }
        int itemsY() { return y+HEADER+(pinnedLabel == null ? 0 : ROW); }
        Area pinnedItem() { return new Area(x+3,y+HEADER,width-6,ROW); }
        Area bounds() { return new Area(x,y,width,itemsY()-y+rows*ROW+3); }
    }

    private final CameraClient controller;
    private final Z8Settings settings;
    private final Z8FrameBuffer frame = new Z8FrameBuffer();
    private boolean evf;
    private long liftStart = System.nanoTime();
    private Parameter openList;
    private boolean draggingView, draggingZoom, draggingFocus, movedView;
    private double pressX, pressY, focusU = .5, focusV = .5;
    private int scroll, statusTicks;
    private boolean awaitingLight;
    private String status = "";
    private boolean restoreHud;
    private CameraType oldCameraType;
    private boolean playback;
    private DynamicTexture playbackTexture;
    private ToneHistogram photoHistogram;
    private int photoIndex, playbackWidth, playbackHeight, lastCount;
    private boolean rawPlayback;

    Z8Screen(CameraClient controller, Z8Settings settings, boolean evf) {
        super(Component.translatable(settings.mediumFormat() ? "item.physical_camera.hasselblad_x2d_ii" : "item.physical_camera.nikon_z8"));
        this.controller = controller;
        this.settings = settings;
        this.evf = evf;
        lastCount = controller.photos().size();
    }

    boolean monitor() { return !evf; }


    private boolean hasLens() { return CameraClient.heldCamera(minecraft) > 0; }
    double projectionHalfTan(double aspect) { return hasLens() ? settings.photoSize.viewportHalfTan(settings.projectionFocal(),aspect) : 0; }
    private Z8Settings.Exposure exposure() {
        frame.prepareMeter();
        boolean attached = hasLens();
        var result = settings.exposure(stop -> frame.meter(settings.photoSize,settings.meter,focusU,focusV,
                !attached,settings.lens,settings.focal,stop),!attached);
        if (frame.hasMeterSample()) settings.rememberExposure(attached,result);
        return result;
    }

    float lift() {
        float t = Math.clamp((System.nanoTime()-liftStart)/(float)LIFT_NANOS,0,1);
        return t*t*(3-2*t);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {}

    @Override public void added() {
        super.added();
        oldCameraType = minecraft.options.getCameraType();
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        restoreHud = !minecraft.gui.hud.isHidden();
        if (restoreHud) {
            minecraft.gui.hud.toggle();
        }
    }

    @Override public void removed() {
        clearPhoto();
        awaitingLight = false;
        controller.cancelPendingFrame();
        frame.close();
        if (restoreHud && minecraft.gui.hud.isHidden()) {
            minecraft.gui.hud.toggle();
        }
        minecraft.options.setCameraType(oldCameraType);
        super.removed();
    }

    void worldRendered(RenderTarget target, double halfTan, float farMetres, boolean nativePhoto) {
        if (!nativePhoto && !playback && !controller.busy()) {
            frame.copy(target,halfTan,farMetres,minecraft,hasLens() ? settings.projectionFocal() : 0,settings.photoSize,monitor() ? 768 : 1152);
        }
        controller.recordFrame(minecraft,target,halfTan,farMetres,nativePhoto);
    }

    double zoomFraction() { return settings.lens.fraction(settings.focal); }


    @Override public void tick() {
        if (minecraft.level == null || CameraClient.heldCamera(minecraft) < 0) {
            onClose();
            return;
        }
        if (awaitingLight && !playback) {
            frame.prepareMeter();
            if (frame.hasMeterSample() || statusTicks == 0) {
                awaitingLight = false;
                if (frame.hasMeterSample()) shoot();
            }
        }
        if (statusTicks > 0) {
            statusTicks--;
        }
        int count = controller.photos().size();
        if (count > lastCount) {
            feedback(I18n.get("screen.physical_camera.viewfinder.saved"));
            if (playback) {
                photoIndex = count-1;
                loadPhoto();
            }
        }
        lastCount = count;
    }

    private View view() {
        if (evf) {
            double scale = Math.min(height/(double)UI_HEIGHT,width/272.0);
            double viewHeight = scale*UI_HEIGHT;
            return new View(new Area(0,(height-viewHeight)/2,width,viewHeight),(int)Math.round(width/scale));
        }
        var camera = minecraft.gameRenderer.mainCamera();
        float fov = ((Z8CameraFov)camera).physicalCamera$hudFov();
        var glass = Z8Presentation.project(width,height,fov > 1 ? fov : 70,settings.photoSize.sensor());
        var inset = new Area(glass.x()+1,glass.y()+1,glass.width()-2,glass.height()-2);
        return new View(inset,(int)Math.round(inset.width/(inset.height/UI_HEIGHT)));
    }

    private static Readout at(Parameter parameter, String label, double fraction, int y, int cellWidth,
                              int width, boolean enabled) {
        return new Readout(parameter,label,readoutArea(width,fraction,y,cellWidth),enabled);
    }

    private static Area readoutArea(int width, double fraction, int y, int cellWidth) {
        return new Area((int)Math.round(width*fraction)-cellWidth/2,y,cellWidth,14);
    }

    private Area focalArea(int width) {
        return readoutArea(width,.88,settings.mediumFormat() ? 19 : 34,width/24*5);
    }

    private List<Readout> readouts(int width, Z8Settings.Exposure exposure) {
        int unit = width/24;
        boolean lens = hasLens();
        boolean medium = settings.mediumFormat();
        return List.of(
                at(Parameter.MODE,settings.mode.toString(),.06,1,unit*2,width,true),
                at(Parameter.FOCUS,lens ? settings.usesAutofocus() ? "AF-S" : "MF" : I18n.get("screen.physical_camera.viewfinder.body"),
                        medium ? .20 : .29,1,unit*3,width,lens && settings.lens.supportsAutofocus()),
                at(Parameter.GRID,settings.grid ? "▦" : "⊞",.08,19,unit*3,width,true),
                at(Parameter.SIZE,settings.photoSize.label,medium ? .52 : .83,medium ? 1 : 19,unit*8,width,true),
                at(Parameter.LEVEL,settings.horizon ? "⊕" : "⊖",.08,34,unit*3,width,true),
                new Readout(Parameter.FOCAL,lens ? zoomLabel() : "-- mm",focalArea(width),lens && settings.lens.zoomable()),
                at(Parameter.FORMAT,settings.photoFormat.label,.88,medium ? 1 : 49,unit*5,width,true),
                at(Parameter.DISTANCE,lens ? distanceLabel(settings.distance()) : "--",.50,UI_HEIGHT-38,unit*5,width,lens),
                at(Parameter.METER,meterSymbol(),.066,UI_HEIGHT-15,unit*3,width,true),
                at(Parameter.SHUTTER,Z8Settings.shutterLabel(exposure.seconds()),.223,UI_HEIGHT-15,unit*4,width,
                        settings.mode == Z8Settings.Mode.M || settings.mode == Z8Settings.Mode.S),
                at(Parameter.APERTURE,lens ? Z8Settings.apertureLabel(exposure.aperture()) : "F--",.394,UI_HEIGHT-15,unit*4,width,
                        lens && (settings.mode == Z8Settings.Mode.M || settings.mode == Z8Settings.Mode.A)),
                at(Parameter.COMPENSATION,evLabel(settings.compensationThirds/3.0),.56,UI_HEIGHT-15,unit*4,width,
                        settings.mode != Z8Settings.Mode.M || settings.autoIso),
                at(Parameter.ISO,(settings.autoIso ? "A " : "ISO ")+exposure.iso(),.75,UI_HEIGHT-15,unit*5,width,true));
    }

    private Options options(Readout readout) {
        return switch (readout.parameter) {
            case MODE -> new Options(readout,List.of(I18n.get("screen.physical_camera.viewfinder.program"),I18n.get("screen.physical_camera.viewfinder.shutter_priority"),I18n.get("screen.physical_camera.viewfinder.aperture_priority"),I18n.get("screen.physical_camera.viewfinder.manual")),settings.mode.ordinal());
            case SHUTTER -> {
                var steps = settings.shutterSteps();
                yield new Options(readout,labels(steps.length,i -> Z8Settings.shutterLabel(steps[i])),settings.shutter);
            }
            case APERTURE -> {
                int first = settings.firstAperture();
                var apertures = labels(settings.lastAperture()-first+1,
                        i -> Z8Settings.apertureLabel(settings.lens.apertures()[first+i]));
                yield new Options(readout,apertures,settings.apertureIndex()-first);
            }
            case ISO -> new Options(readout,labels(settings.isoCount()+1,
                    i -> i == 0 ? I18n.get("screen.physical_camera.viewfinder.auto_iso") : "ISO "+settings.isoSteps()[i-1]),settings.autoIso ? 0 : settings.sensitivity+1);
            case FOCUS -> settings.lens.focusLimitMeters() > 0
                    ? new Options(readout,List.of(I18n.get("screen.physical_camera.viewfinder.af_full"),"AF-S  ∞–"+settings.lens.focusLimitMeters()+" m",I18n.get("screen.physical_camera.viewfinder.mf")),
                            settings.usesAutofocus() ? settings.usesFocusLimit() ? 1 : 0 : 2)
                    : new Options(readout,List.of(I18n.get("screen.physical_camera.viewfinder.af_single"),I18n.get("screen.physical_camera.viewfinder.mf")),
                            settings.autofocus ? 0 : 1);
            case DISTANCE -> {
                int first = settings.firstDistance();
                var distances = labels(Z8Settings.DISTANCES.length-first,
                        i -> distanceLabel(Math.max(settings.lens.minFocus(settings.focal),Z8Settings.DISTANCES[first+i])));
                int nearest = first;
                double distance = settings.distance();
                if (Double.isInfinite(distance)) nearest = Z8Settings.DISTANCES.length-1;
                else for (int i = first+1; i < Z8Settings.DISTANCES.length-1; i++) {
                    if (distance >= Math.sqrt(Z8Settings.DISTANCES[i-1]*Z8Settings.DISTANCES[i])) nearest = i;
                }
                yield new Options(readout,distances,nearest-first);
            }
            case METER -> new Options(readout,List.of(I18n.get("screen.physical_camera.viewfinder.matrix"),I18n.get("screen.physical_camera.viewfinder.center"),I18n.get("screen.physical_camera.viewfinder.spot")),settings.meter.ordinal());
            case FOCAL -> throw new IllegalStateException("Zoom uses a continuous control");
            case COMPENSATION -> new Options(readout,COMPENSATIONS,settings.compensationThirds+9);
            case GRID -> new Options(readout,List.of(I18n.get("options.off"),I18n.get("options.on")),settings.grid ? 1 : 0);
            case LEVEL -> new Options(readout,List.of(I18n.get("options.off"),I18n.get("options.on")),settings.horizon ? 1 : 0);
            case SIZE -> {
                var sizes = Z8PhotoSize.forSensor(settings.photoSize.sensor());
                yield new Options(readout,labels(sizes.length,i -> sizes[i].label),settings.photoSize.sizeIndex());
            }
            case FORMAT -> new Options(readout,FORMATS,settings.photoFormat.ordinal());
        };
    }

    private Popup popup(List<Readout> readouts, int width) {
        for (Readout readout : readouts) {
            if (readout.parameter != openList || !readout.enabled) {
                continue;
            }
            Options choices = options(readout);
            Component pinned = readout.parameter == Parameter.ISO && !settings.mediumFormat()
                    ? Component.translatable(controller.preferences().extendedIso
                    ? "screen.physical_camera.iso_extension.on" : "screen.physical_camera.iso_extension.off") : null;
            int rows = Math.min(Popup.VISIBLE,choices.items.size());
            int labelWidth = choices.items.stream().mapToInt(font::width).max().orElse(0);
            labelWidth = Math.max(labelWidth,font.width(readout.parameter.label()));
            int w = Math.max(81,Math.max(labelWidth,pinned == null ? 0 : font.width(pinned))+22);
            int h = Popup.HEADER+(rows+(pinned == null ? 0 : 1))*Popup.ROW+3;
            int x = Math.clamp((int)(readout.area.x+readout.area.width/2)-w/2,2,width-w-2);
            int y = Math.clamp((int)(readout.area.y+readout.area.height+2),2,UI_HEIGHT-h-2);
            if (readout.area.y > UI_HEIGHT/2) {
                y = Math.max(2,(int)readout.area.y-h-2);
            }
            return new Popup(choices,scroll,x,y,w,rows,pinned);
        }
        return null;
    }

    private void select(Parameter parameter, int index) {
        switch (parameter) {
            case MODE -> settings.mode = Z8Settings.Mode.values()[index];
            case SHUTTER -> settings.shutter = index;
            case APERTURE -> settings.aperture = index+settings.firstAperture();
            case ISO -> {
                settings.autoIso = index == 0;
                if (index > 0) settings.sensitivity = index-1;
            }
            case FOCUS -> {
                double distance = settings.distance();
                boolean hasLimit = settings.lens.focusLimitMeters() > 0;
                settings.autofocus = index == 0 || hasLimit && index == 1;
                if (hasLimit) settings.focusLimit = index == 1;
                settings.clearFocus();
                if (settings.usesAutofocus()) settings.clearTouchDistance();
                else settings.manualFocus(distance);
            }
            case DISTANCE -> {
                settings.manualDistance = index+settings.firstDistance();
                settings.manualFocus(Z8Settings.DISTANCES[settings.manualDistance]);
            }
            case METER -> settings.meter = Z8Settings.Meter.values()[index];
            case FOCAL -> throw new IllegalStateException("Zoom uses a continuous control");
            case COMPENSATION -> settings.compensationThirds = index-9;
            case GRID -> settings.grid = index == 1;
            case LEVEL -> settings.horizon = index == 1;
            case SIZE -> settings.photoSize = Z8PhotoSize.forSensor(settings.photoSize.sensor())[index];
            case FORMAT -> settings.photoFormat = PhotoFormat.values()[index];
        }
    }

    private void focus() {
        var camera = minecraft.gameRenderer.mainCamera();
        if (!hasLens() || !settings.lens.supportsAutofocus() || playback
                || minecraft.level == null || !camera.isInitialized()) {
            return;
        }
        var origin = camera.position();
        var viewport = minecraft.gameRenderer.mainRenderTarget();
        var ray = frame.focusRay(camera,focusU,focusV,settings.photoSize,
                viewport.width/(double)viewport.height);
        int focusRange = Math.clamp(16*minecraft.options.getEffectiveRenderDistance(),128,MAX_FOCUS_RANGE);
        var target = origin.add(ray.scale(focusRange));
        var forward = camera.forwardVector();
        double axial = ray.x*forward.x()+ray.y*forward.y()+ray.z*forward.z();
        var start = settings.usesFocusLimit() ? origin.add(ray.scale(settings.autofocusNear()/Math.max(.1,axial))) : origin;
        var hit = minecraft.level.clip(new ClipContext(start,target,ClipContext.Block.OUTLINE,
                ClipContext.Fluid.ANY,CollisionContext.empty()));
        HitResult closest = hit;
        if (camera.entity() != null) {
            double range = hit.getType() == HitResult.Type.MISS ? start.distanceToSqr(target) : hit.getLocation().distanceToSqr(start);
            var entity = ProjectileUtil.getEntityHitResult(camera.entity(),start,target,new AABB(start,target).inflate(1),
                    e -> e.isPickable() && !e.isSpectator(),range);
            if (entity != null) closest = entity;
        }
        if (closest.getType() == HitResult.Type.MISS) {
            double visible = frame.displayedFocusDistance(focusU,focusV,settings.photoSize,settings.lens,settings.focal);
            settings.touchFocus(visible >= settings.autofocusNear() && visible <= focusRange ? visible : Double.POSITIVE_INFINITY);
        } else {
            var delta = closest.getLocation().subtract(origin);
            settings.touchFocus(delta.x*forward.x()+delta.y*forward.y()+delta.z*forward.z());
        }
    }

    private void shoot() {
        if (playback) {
            togglePlayback();
            return;
        }
        if (controller.busy()) {
            return;
        }
        var captureExposure = exposure();
        if (settings.autoIso && !frame.hasMeterSample()) {
            awaitingLight = true;
            feedback(I18n.get("screen.physical_camera.viewfinder.metering"));
            return;
        }
        awaitingLight = false;
        openList = null;
        if (settings.usesAutofocus() && !settings.confirmed()) {
            focus();
        }
        if (controller.shootZ8(minecraft,settings,captureExposure)) {
            frame.close();
        } else {
            feedback(I18n.get("screen.physical_camera.viewfinder.unavailable"));
        }
    }

    private void feedback(String text) { status = text; statusTicks = 65; }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() != InputConstants.KEY_ESCAPE) {
            for (var action : CameraKeys.Action.values()) {
                if (controller.keys().get(action).matches(event) && useShortcut(action)) return true;
            }
        }
        switch (event.key()) {
            case InputConstants.KEY_ESCAPE -> {
                if (openList != null) {
                    openList = null;
                } else if (playback) {
                    togglePlayback();
                } else {
                    onClose();
                }
            }
            case InputConstants.KEY_LEFT -> { if (playback) browse(-1); }
            case InputConstants.KEY_RIGHT -> { if (playback) browse(1); }
            default -> { return super.keyPressed(event); }
        }
        return true;
    }

    private boolean useShortcut(CameraKeys.Action action) {
        switch (action) {
            case RAISE -> onClose();
            case VIEW -> switchView(!evf);
            case RELEASE -> shoot();
            case FOCUS -> focus();
            case PLAYBACK -> togglePlayback();
            case HISTOGRAM -> toggleHistogram();
            case SETTINGS -> openSettings();
            case ASSEMBLY -> controller.openAssembly(minecraft);
            default -> {
                if (playback) return false;
                switch (action) {
                    case SHUTTER_FASTER -> step(Parameter.SHUTTER,-1);
                    case SHUTTER_SLOWER -> step(Parameter.SHUTTER,1);
                    case ISO_UP -> stepIso(1);
                    case ISO_DOWN -> stepIso(-1);
                    case F_UP -> step(Parameter.APERTURE,1);
                    case F_DOWN -> step(Parameter.APERTURE,-1);
                    case ZOOM_IN -> setZoom(settings.lens.stepFocal(settings.focal,1));
                    case ZOOM_OUT -> setZoom(settings.lens.stepFocal(settings.focal,-1));
                    case FOCUS_NEAR -> step(Parameter.DISTANCE,-1);
                    case FOCUS_FAR -> step(Parameter.DISTANCE,1);
                    case EV_UP -> step(Parameter.COMPENSATION,1);
                    case EV_DOWN -> step(Parameter.COMPENSATION,-1);
                    case EXPOSURE_MODE -> change(Parameter.MODE,1,true);
                    case FOCUS_MODE -> change(Parameter.FOCUS,1,true);
                    case METER_MODE -> change(Parameter.METER,1,true);
                    case PHOTO_SIZE -> change(Parameter.SIZE,1,true);
                    case GRID -> change(Parameter.GRID,1,true);
                    case LEVEL -> change(Parameter.LEVEL,1,true);
                    default -> { return false; }
                }
            }
        }
        return true;
    }

    private void step(Parameter parameter, int step) {
        change(parameter,step,false);
    }

    private void stepIso(int step) {
        select(Parameter.ISO,1+Math.clamp(settings.sensitivity+step,0,settings.isoCount()-1));
    }

    private void change(Parameter parameter, int step, boolean wrap) {
        for (var readout : readouts(view().width,exposure())) {
            if (readout.parameter != parameter || !readout.enabled) continue;
            var options = options(readout);
            int target = options.current+step;
            select(parameter,wrap ? Math.floorMod(target,options.items.size())
                    : Math.clamp(target,0,options.items.size()-1));
            return;
        }
    }

    private void switchView(boolean toEvf) {
        evf = toEvf;
        liftStart = System.nanoTime();
        openList = null;
        draggingView = draggingZoom = false;
        setDragging(false);
    }

    private void openSettings() {
        openList = null;
        draggingView = draggingZoom = false;
        setDragging(false);
        minecraft.gui.setScreen(new CameraSettingsScreen(controller,this));
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (playback) togglePlayback();
            else if (evf) onClose();
            else switchView(true);
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            for (var action : CameraKeys.Action.values()) {
                if (controller.keys().get(action).matchesMouse(event) && useShortcut(action)) return true;
            }
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || (!evf && lift() < 1)) {
            return false;
        }
        View view = view();
        double x = view.x(event.x()), y = view.y(event.y());
        if (playback) {
            return playbackClick(x,y,view.width);
        }
        List<Readout> readouts = readouts(view.width,exposure());
        if (openList == Parameter.FOCAL) {
            Area rail = zoomRail(view.width);
            if (zoomHitArea(rail).contains(x,y)) {
                moveZoom(x,rail);
                draggingZoom = true;
                setDragging(true);
            } else {
                openList = null;
            }
            return true;
        }
        if (openList != null) {
            Popup popup = popup(readouts,view.width);
            if (popup != null) {
                if (popup.pinnedLabel != null && popup.pinnedItem().contains(x,y)) {
                    try {
                        controller.setIsoExtension(!controller.preferences().extendedIso);
                    } catch (java.io.IOException error) {
                        feedback(I18n.get("screen.physical_camera.viewfinder.iso_save_failed"));
                    }
                    scroll = Math.clamp(scroll,0,Math.max(0,settings.isoCount()+1-Popup.VISIBLE));
                    return true;
                }
                for (int i = 0; i < popup.rows; i++) {
                    if (popup.item(i).contains(x,y)) {
                        select(openList,popup.scroll+i);
                        break;
                    }
                }
            }
            openList = null;
            return true;
        }
        if (liveDisp().contains(x,y)) {
            toggleHistogram();
            return true;
        }
        if (hasLens() && focusRail(view.width).contains(x,y)) {
            moveFocus(x,view.width);
            draggingFocus = true;
            setDragging(true);
            return true;
        }
        for (Readout readout : readouts) {
            if (readout.area.contains(x,y)) {
                if (readout.enabled) {
                    openList = readout.parameter;
                    if (openList != Parameter.FOCAL) {
                        var choices = options(readout);
                        scroll = Math.clamp(choices.current-Popup.VISIBLE/2,0,
                                Math.max(0,choices.items.size()-Popup.VISIBLE));
                    }
                }
                return true;
            }
        }
        if (gear(view.width).contains(x,y)) {
            openSettings();
            return true;
        }
        if (sceneArea(view.width).contains(x,y) && y >= 16 && y < UI_HEIGHT-16) {
            pressX = event.x();
            pressY = event.y();
            movedView = false;
            draggingView = true;
            setDragging(true);
            return true;
        }
        return false;
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (playback || controller.busy() || dy == 0) return false;
        View view = view();
        double localX = view.x(x), localY = view.y(y);
        if ((openList == null || openList == Parameter.FOCAL) && hasLens() && settings.lens.zoomable()
                && (focalArea(view.width).contains(localX,localY) || openList == Parameter.FOCAL
                        && zoomHitArea(zoomRail(view.width)).contains(localX,localY))) {
            setZoom(settings.lens.stepFocal(settings.focal,dy > 0 ? 1 : -1));
            return true;
        }
        if (openList == null || openList == Parameter.FOCAL) return false;
        Popup popup = popup(readouts(view.width,exposure()),view.width);
        if (popup == null || !popup.bounds().contains(localX,localY)) {
            return false;
        }
        int step = (int)Math.copySign(Math.max(1,Math.round(Math.abs(dy))),dy);
        scroll = Math.clamp(scroll-step,0,popup.options.items.size()-popup.rows);
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingFocus && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            draggingFocus = false;
            setDragging(false);
            return true;
        }
        if (draggingZoom && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            draggingZoom = false;
            setDragging(false);
            return true;
        }
        if (draggingView && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            draggingView = false;
            setDragging(false);
            var view = view();
            var scene = sceneArea(view.width);
            double x = view.x(event.x()), y = view.y(event.y());
            if (!movedView && Math.hypot(event.x()-pressX,event.y()-pressY) < 3
                    && settings.lens.supportsAutofocus() && controller.preferences().touchFocus && scene.contains(x,y)) {
                focusU = (x-scene.x)/scene.width;
                focusV = (y-scene.y)/scene.height;
                focus();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingFocus && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            View view = view();
            moveFocus(view.x(event.x()),view.width);
            return true;
        }
        if (draggingZoom && openList == Parameter.FOCAL && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            View view = view();
            moveZoom(view.x(event.x()),zoomRail(view.width));
            return true;
        }
        if (draggingView && event.button() == InputConstants.MOUSE_BUTTON_LEFT && minecraft.player != null) {
            if (movedView || Math.hypot(event.x()-pressX,event.y()-pressY) >= 3) {
                movedView = true;
                minecraft.player.turn(dx*1.7,dy*1.7);
                settings.clearFocus();
            }
            return true;
        }
        return super.mouseDragged(event,dx,dy);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        if (!evf && lift() < 1) {
            return;
        }
        View view = view();
        double x = view.x(mx), y = view.y(my);
        g.pose().pushMatrix();
        float scale = (float)view.scale();
        g.pose().translate((float)view.area.x,(float)view.area.y).scale(scale);
        if (controller.busy()) {
            g.fill(0,0,view.width,UI_HEIGHT,0xff000000);
            g.centeredText(font,controller.progress(),view.width/2,UI_HEIGHT/2-4,WHITE);
        } else if (playback) {
            renderPlayback(g,view.width,x,y);
        } else {
            var exposure = exposure();
            drawScene(g,view.width,exposure);
            g.nextStratum();
            List<Readout> readouts = readouts(view.width,exposure);
            renderLive(g,view.width,x,y,exposure,readouts);
            if (statusTicks > 0) {
                g.centeredText(font,status,view.width/2,29,WHITE);
            }
            if (openList == Parameter.FOCAL) {
                g.nextStratum();
                renderZoom(g,view.width);
            } else {
                var popup = popup(readouts,view.width);
                if (popup != null) {
                    g.nextStratum();
                    renderPopup(g,popup,x,y);
                }
            }
        }
        g.pose().popMatrix();
    }

    private String zoomLabel() { return settings.lens.snapFocal(settings.focal)+" mm"; }

    private Area zoomRail(int width) {
        var focal = focalArea(width);
        int length = Math.min(56,width/6);
        return new Area(focal.x-length-6,focal.y+6,length,2);
    }

    private static Area zoomHitArea(Area rail) { return new Area(rail.x-4,rail.y-5,rail.width+8,12); }

    private static Area focusRail(int width) {
        return new Area(width/2-width/7,UI_HEIGHT-27,2*(width/7),9);
    }

    private void moveFocus(double x, int width) {
        var rail = focusRail(width);
        double fraction = Math.clamp((x-rail.x)/rail.width,0,1);
        double near = settings.lens.minFocus(settings.focal);
        settings.manualFocus(fraction > FINITE_FOCUS_RAIL ? Double.POSITIVE_INFINITY
                : near*Math.pow(focusRailLimit()/near,fraction/FINITE_FOCUS_RAIL));
    }

    private int focusRailLimit() { return settings.lens.hasBuiltInConverter() ? MAX_FOCUS_RANGE : 128; }

    private void moveZoom(double x, Area rail) {
        setZoom(settings.lens.focal((x-rail.x)/rail.width));
    }

    private void setZoom(double focal) {
        if (!hasLens() || !settings.lens.zoomable()) return;
        settings.changeFocal(focal);
    }

    private void renderZoom(GuiGraphicsExtractor g, int width) {
        Area rail = zoomRail(width);
        fill(g,new Area(rail.x-3,rail.y-3,rail.width+6,8),0xb0101215);
        fill(g,rail,0xffa3aba1);
        if (settings.lens.hasBuiltInConverter()) {
            for (double x : new double[]{rail.x,rail.x+rail.width}) {
                fill(g,new Area(x,rail.y-1,1,4),WHITE);
            }
        }
        int position = (int)Math.round(rail.x+zoomFraction()*rail.width);
        fill(g,new Area(position-1,rail.y-2,3,6),GREEN);
    }

    private void drawScene(GuiGraphicsExtractor g, int width, Z8Settings.Exposure exposure) {
        var area = sceneArea(width);
        g.fill(0,0,width,UI_HEIGHT,BLACK);
        frame.draw(g,(int)area.x,(int)area.y,(int)area.width,(int)area.height,
                settings.photoSize,exposure,!hasLens(),settings.lens,settings.focal,settings.distance(),
                controller.preferences().previewWorkers(),controller.preferences().starlightView,showLiveHistogram());
    }

    private Area sceneArea(int width) {
        if (settings.mediumFormat()) {
            int units = Math.min(width/4,(UI_HEIGHT-32)/3);
            return new Area((width-units*4)/2,(UI_HEIGHT-units*3)/2,units*4,units*3);
        }
        int units = Math.min(width/3,UI_HEIGHT/2);
        int sceneWidth = units*3, sceneHeight = units*2;
        return new Area((width-sceneWidth)/2,(UI_HEIGHT-sceneHeight)/2,sceneWidth,sceneHeight);
    }

    private void renderLive(GuiGraphicsExtractor g, int w, double mx, double my, Z8Settings.Exposure exp,
                            List<Readout> readouts) {
        g.fill(0,0,w,16,BAR);
        g.fill(0,UI_HEIGHT-16,w,UI_HEIGHT,BAR);
        if (settings.mediumFormat()) {
            g.fill(0,15,w,16,0xff9e571d);
            indicator(g,"4:3",w,.72,4,WHITE);
        } else {
            indicator(g,"[S]",w,.17,4,PLACEHOLDER);
            indicator(g,"[+]",w,.40,4,PLACEHOLDER);
            indicator(g,"⚡",w,.49,4,PLACEHOLDER);
            indicator(g,"WB A0",w,.61,4,PLACEHOLDER);
            indicator(g,"ADL OFF",w,.74,4,PLACEHOLDER);
            indicator(g,"PC A",w,.85,4,PLACEHOLDER);
            indicator(g,"FX",w,.96,4,WHITE);
            indicator(g,"SL",w,.06,UI_HEIGHT-48,PLACEHOLDER);
        }

        int cx = w/2, cy = UI_HEIGHT/2;
        if (settings.grid) {
            var area = settings.mediumFormat() ? sceneArea(w) : new Area(0,16,w,UI_HEIGHT-32);
            for (int i = 1; i <= 2; i++) {
                int gx = (int)(area.x+i*area.width/3), gy = settings.mediumFormat()
                        ? (int)(area.y+i*area.height/3) : i*UI_HEIGHT/3;
                g.fill(gx,(int)area.y,gx+1,(int)(area.y+area.height),0x88ffffff);
                g.fill((int)area.x,gy,(int)(area.x+area.width),gy+1,0x88ffffff);
            }
        }
        if (settings.horizon) {
            float pitch = minecraft.gameRenderer.mainCamera().xRot();
            int y = cy-Math.clamp((int)(pitch*.7),-44,44);
            int color = Math.abs(pitch)<2 ? GREEN : WHITE;
            g.fill(cx-36,y,cx-12,y+1,color);
            g.fill(cx+12,y,cx+36,y+1,color);
        }
        drawMeter(g,exp.ev(),w-9,cy);
        if (showLiveHistogram() && frame.histogram() != null) {
            var scene = sceneArea(w);
            int right = (int)(scene.x+scene.width);
            renderHistogram(g,frame.histogram(),right-76,UI_HEIGHT-58,32,WHITE);
        }
        renderDisp(g,liveDisp(),mx,my,showLiveHistogram(),!controller.preferences().starlightView);
        if (hasLens()) {
            int focus = settings.confirmed() ? GREEN : RED;
            var area = sceneArea(w);
            int fx = (int)Math.round(area.x+focusU*area.width);
            int fy = (int)Math.round(area.y+focusV*area.height);
            g.outline(fx-6,fy-6,12,12,focus);
            int half = w/7;
            int y = UI_HEIGHT-23;
            g.fill(cx-half,y,cx+half,y+1,WHITE);
            g.fill(cx-half,y-3,cx-half+1,y+2,WHITE);
            g.fill(cx+half-1,y-3,cx+half,y+2,WHITE);
            double near = settings.lens.minFocus(settings.focal);
            double distance = settings.distance();
            double fraction = Double.isInfinite(distance) ? 1 : FINITE_FOCUS_RAIL
                    *Math.log(Math.clamp(distance,near,focusRailLimit())/near)/Math.log(focusRailLimit()/near);
            int mark = cx-half+(int)(2*half*fraction);
            g.fill(mark,y-3,mark+2,y+2,focus);
        }

        for (Readout value : readouts) {
            boolean hovered = value.area.contains(mx,my) && openList == null;
            if (hovered && value.enabled || openList == value.parameter) {
                fill(g,value.area,0xe0394944);
                outline(g,value.area,0xffb6cbbd);
            } else if (value.parameter == Parameter.MODE) {
                outline(g,value.area,0xffb5b7b1);
            }
            center(g,value.label,value.area,value.enabled || hasLens() && (value.parameter == Parameter.FOCUS
                    || value.parameter == Parameter.FOCAL) ? WHITE : PLACEHOLDER);
        }
        Area gear = gear(w);
        if (gear.contains(mx,my) && openList == null) {
            fill(g,gear,0xe0394944);
        }
        int gx = (int)(gear.x+gear.width/2), gy = (int)(gear.y+gear.height/2);
        g.outline(gx-3,gy-3,7,7,WHITE);
        g.fill(gx-1,gy-5,gx+2,gy-2,WHITE);
        g.fill(gx-1,gy+3,gx+2,gy+6,WHITE);
        g.fill(gx-5,gy-1,gx-2,gy+2,WHITE);
        g.fill(gx+3,gy-1,gx+6,gy+2,WHITE);
    }

    private void renderPopup(GuiGraphicsExtractor g, Popup popup, double x, double y) {
        fill(g,popup.bounds(),0xff151a1c);
        outline(g,popup.bounds(),0xffa3aba1);
        g.fill(popup.x+1,popup.y+1,popup.x+popup.width-1,popup.y+13,0xff303634);
        var parameter = popup.options.readout.parameter;
        g.text(font,parameter.label(),popup.x+5,popup.y+3,WHITE,false);
        if (popup.pinnedLabel != null) {
            Area row = popup.pinnedItem();
            fill(g,row,row.contains(x,y) ? 0xff4c6557 : 0xff303634);
            g.text(font,popup.pinnedLabel,(int)row.x+4,(int)row.y+2,
                    controller.preferences().extendedIso ? GREEN : WHITE,false);
        }
        for (int i = 0; i < popup.rows; i++) {
            Area row = popup.item(i);
            int index = popup.scroll+i;
            boolean selected = popup.options.current == index;
            if (row.contains(x,y) || selected) {
                fill(g,row,row.contains(x,y) ? 0xff4c6557 : 0xff293b34);
            }
            g.text(font,popup.options.items.get(index),(int)row.x+4,(int)row.y+2,selected ? GREEN : WHITE,false);
        }
        int extra = popup.options.items.size()-popup.rows;
        if (extra > 0) {
            int laneX = popup.x+popup.width-6, laneY = popup.itemsY()+1;
            int laneH = popup.rows*Popup.ROW-2;
            int thumbH = Math.max(5,laneH*popup.rows/popup.options.items.size());
            int thumbY = laneY+(laneH-thumbH)*popup.scroll/extra;
            g.fill(laneX,laneY,laneX+3,laneY+laneH,0xff303634);
            g.fill(laneX,thumbY,laneX+3,thumbY+thumbH,0xff92a69a);
        }
    }

    private String meterSymbol() {
        return switch (settings.meter) {
            case MATRIX -> "[::]";
            case CENTER -> "[( )]";
            case SPOT -> "[·]";
        };
    }

    private void drawMeter(GuiGraphicsExtractor g, double ev, int x, int y) {
        int value = (int)Math.round(Math.clamp(ev,-3,3)*3);
        for (int i = -9; i <= 9; i++) {
            int mark = i%3 == 0 ? 4 : 2;
            g.fill(x-mark,y-i*3,x,y-i*3+1,i == value ? RED : WHITE);
        }
    }

    private boolean showLiveHistogram() {
        return settings.histogram && !controller.preferences().starlightView;
    }

    private void toggleHistogram() {
        if (controller.busy()) return;
        if (playback) {
            if (playbackTexture != null) settings.playbackHistogram = !settings.playbackHistogram;
        } else if (controller.preferences().starlightView) {
            feedback(I18n.get("screen.physical_camera.viewfinder.starlight_histogram"));
        } else {
            settings.histogram = !settings.histogram;
        }
    }

    private static Area liveDisp() { return new Area(7,49,33,14); }

    private static Area playbackDisp(int width) { return new Area(width-38,1,33,13); }

    private void renderDisp(GuiGraphicsExtractor g, Area area, double mx, double my, boolean active, boolean enabled) {
        if (enabled && area.contains(mx,my) && openList == null) fill(g,area,0xe0394944);
        center(g,"DISP",area,active ? GREEN : enabled ? WHITE : PLACEHOLDER);
    }

    private static void renderHistogram(GuiGraphicsExtractor g, ToneHistogram.Curve curve, int x, int y,
                                        int height, int color) {
        int width = ToneHistogram.BINS;
        int base = y+height-2;
        g.fill(x,y,x+width+4,y+height,0xc4101215);
        g.outline(x,y,width+4,height,0xaa929991);
        for (int tick = 1; tick < 4; tick++) {
            int line = x+2+tick*width/4;
            g.fill(line,y+3,line+1,base,0x44899188);
        }
        for (int i = 0; i < width; i++) {
            int count = curve.counts()[i];
            if (count == 0) continue;
            int bar = Math.max(1,Math.round((height-6)*count/(float)curve.peak()));
            g.fill(x+2+i,base-bar,x+3+i,base,color);
        }
        if (curve.blackClipped()) g.fill(x+2,y+2,x+5,y+4,0xff90bce8);
        if (curve.whiteClipped()) g.fill(x+width-1,y+2,x+width+2,y+4,0xffffc264);
    }

    private static Area gear(int width) { return new Area(width*.88,UI_HEIGHT-15,width*.105,14); }

    private void indicator(GuiGraphicsExtractor g, String label, int w, double fraction, int y, int color) {
        g.centeredText(font,label,(int)(w*fraction),y,color);
    }

    private void center(GuiGraphicsExtractor g, String label, Area area, int color) {
        g.centeredText(font,label,(int)(area.x+area.width/2),(int)(area.y+(area.height-8)/2),color);
    }

    private static void fill(GuiGraphicsExtractor g, Area a, int color) {
        g.fill((int)a.x,(int)a.y,(int)(a.x+a.width),(int)(a.y+a.height),color);
    }

    private static void outline(GuiGraphicsExtractor g, Area a, int color) {
        g.outline((int)a.x,(int)a.y,(int)a.width,(int)a.height,color);
    }

    private static List<String> labels(int size, IntFunction<String> label) {
        return IntStream.range(0,size).mapToObj(label).toList();
    }

    private static String distanceLabel(double distance) {
        return Double.isInfinite(distance) ? "∞" : String.format(Locale.ROOT,"%.2f m",distance);
    }

    private static String evLabel(double ev) {
        return String.format(Locale.ROOT,"%+.1f EV",ev);
    }

    private void togglePlayback() {
        openList = null;
        playback = !playback;
        if (playback) {
            photoIndex = Math.max(0,controller.photos().size()-1);
            loadPhoto();
        } else {
            clearPhoto();
        }
    }

    private void browse(int step) {
        int size = controller.photos().size();
        if (size > 0) {
            photoIndex = Math.floorMod(photoIndex+step,size);
            loadPhoto();
        }
    }

    private boolean playbackClick(double x, double y, int w) {
        if (playbackTexture != null && playbackDisp(w).contains(x,y)) {
            toggleHistogram();
            return true;
        }
        if (y < UI_HEIGHT-16 || y >= UI_HEIGHT || x < 0 || x >= w) {
            return false;
        }
        switch ((int)(x*3/w)) {
            case 0 -> browse(-1);
            case 1 -> browse(1);
            case 2 -> togglePlayback();
            default -> {}
        }
        return true;
    }

    private void loadPhoto() {
        clearPhoto();
        rawPlayback = false;
        if (controller.photos().isEmpty()) {
            return;
        }
        var stem = controller.photos().get(photoIndex);
        var file = CameraClient.photoFile(stem,"png");
        rawPlayback = !Files.isRegularFile(file) && Files.isRegularFile(CameraClient.photoFile(stem,"dng"));
        if (rawPlayback) return;
        try (var input = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(input);
            playbackWidth = image.getWidth();
            playbackHeight = image.getHeight();
            playbackTexture = new DynamicTexture(() -> "Z 8 playback",image);
            minecraft.getTextureManager().register(PLAYBACK,playbackTexture);
        } catch (IOException error) {
            feedback(I18n.get("screen.physical_camera.viewfinder.read_failed"));
        }
    }

    private void clearPhoto() {
        if (playbackTexture != null) {
            minecraft.getTextureManager().release(PLAYBACK);
            playbackTexture = null;
        }
        photoHistogram = null;
    }

    private void renderPlayback(GuiGraphicsExtractor g, int w, double mx, double my) {
        g.fill(0,0,w,UI_HEIGHT,BLACK);
        if (playbackTexture != null) {
            boolean showHistogram = settings.playbackHistogram;
            int imageSpace = showHistogram ? w-83 : w;
            int width = Math.min(imageSpace,(UI_HEIGHT-28)*playbackWidth/playbackHeight);
            int height = width*playbackHeight/playbackWidth;
            g.blit(RenderPipelines.GUI_TEXTURED,PLAYBACK,(imageSpace-width)/2,(UI_HEIGHT-8-height)/2,0,0,
                    width,height,playbackWidth,playbackHeight,playbackWidth,playbackHeight);
            g.text(font,(photoIndex+1)+" / "+controller.photos().size(),6,3,WHITE);
            renderDisp(g,playbackDisp(w),mx,my,showHistogram,true);
            if (showHistogram) renderPhotoHistogram(g,w);
        } else {
            g.centeredText(font,controller.photos().isEmpty() ? I18n.get("screen.physical_camera.viewfinder.no_photos")
                            : rawPlayback ? I18n.get("screen.physical_camera.viewfinder.raw_external")
                                          : I18n.get("screen.physical_camera.viewfinder.no_preview"),
                    w/2,UI_HEIGHT/2,WHITE);
        }
        String[] labels = {"‹","›",I18n.get("screen.physical_camera.viewfinder.back")};
        for (int i = 0; i < labels.length; i++) {
            Area cell = new Area(w*i/3,UI_HEIGHT-16,w/3,16);
            fill(g,cell,cell.contains(mx,my) ? 0xff38403c : BAR);
            center(g,labels[i],cell,WHITE);
        }
    }

    private void renderPhotoHistogram(GuiGraphicsExtractor g, int width) {
        if (photoHistogram == null) {
            var pixels = playbackTexture.getPixels();
            photoHistogram = ToneHistogram.playback(playbackWidth,playbackHeight,pixels::getPixel);
        }
        renderHistogram(g,photoHistogram.luminance(),width-74,16,37,WHITE);
        renderHistogram(g,photoHistogram.red(),width-74,58,37,RED);
        renderHistogram(g,photoHistogram.green(),width-74,100,37,GREEN);
        renderHistogram(g,photoHistogram.blue(),width-74,142,37,0xff80abf0);
    }
}
