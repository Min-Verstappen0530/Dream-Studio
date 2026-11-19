package dev.physicalcamera;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;

final class CameraSettingsScreen extends Screen {
    private final CameraClient controller;
    private final Screen parent;
    private final CameraPreferences settings;
    private boolean shortcuts, saveFailed;
    private boolean valuesChanged;
    private int page;
    private CameraKeys.Action listening;

    CameraSettingsScreen(CameraClient controller, Screen parent) {
        super(Component.translatable("screen.physical_camera.settings"));
        this.controller = controller;
        this.parent = parent;
        settings = controller.preferences();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        if (shortcuts) {
            initShortcuts();
            return;
        }
        int w = Math.min(width-16,300), x = (width-w)/2;
        boolean iris = settings.renderMode == CameraPreferences.RenderMode.IRIS;
        int lastRow = iris ? 8 : 7;
        int spacing = Math.clamp((height-51)/lastRow,20,24);
        int y = Math.max(30,(height-lastRow*spacing-20)/2);
        addSamplingControls(x,y,w);
        button(Component.translatable("screen.physical_camera.settings.capture_cpu", settings.allCores ? Component.translatable("screen.physical_camera.settings.cpu_all") : Component.translatable("screen.physical_camera.settings.cpu_reserve"), settings.workers()),
                x,y+spacing,w,() -> { settings.allCores = !settings.allCores; save(); });
        addRenderableWidget(new PreviewWorkersSlider(x,y+2*spacing,w));
        button(Component.translatable("screen.physical_camera.settings.touch_focus", onOff(settings.touchFocus)),x,y+3*spacing,w,
                () -> { settings.touchFocus = !settings.touchFocus; save(); });
        Component renderLabel = switch (settings.renderMode) {
            case VIEWPORT -> Component.translatable("screen.physical_camera.settings.render.viewport");
            case NATIVE -> Component.translatable("screen.physical_camera.settings.render.native");
            case IRIS -> Component.translatable("screen.physical_camera.settings.render.iris");
        };
        button(Component.translatable("screen.physical_camera.settings.render", renderLabel),x,y+4*spacing,w,() -> {
            var modes = CameraPreferences.RenderMode.values();
            settings.renderMode = modes[(settings.renderMode.ordinal()+1)%modes.length];
            save();
        });
        button(Component.translatable("screen.physical_camera.settings.starlight", onOff(settings.starlightView)),x,y+5*spacing,w,
                () -> { settings.starlightView = !settings.starlightView; save(); });
        if (iris) {
            var profile = IrisCompatibility.captureProfile();
            button(Component.translatable("screen.physical_camera.settings.iris_history", settings.irisHistory.label(profile)),x,y+6*spacing,w,
                    () -> { settings.irisHistory = settings.irisHistory.next(); save(); });
        }
        button(Component.translatable("screen.physical_camera.settings.language"),x,y+(lastRow-1)*spacing,w,() -> {
            if (valuesChanged) persist();
            minecraft.gui.setScreen(new LanguageSelectScreen(this,minecraft.options,minecraft.getLanguageManager()));
        });
        int half = (w-4)/2;
        button(Component.translatable("screen.physical_camera.settings.shortcuts"),x,y+lastRow*spacing,half,() -> { shortcuts = true; page = 0; rebuildWidgets(); });
        button(Component.translatable("screen.physical_camera.settings.back"),x+half+4,y+lastRow*spacing,w-half-4,this::onClose);
    }

    private final class PreviewWorkersSlider extends AbstractSliderButton {
        private final int maximum = CameraPreferences.availableProcessors();

        PreviewWorkersSlider(int x, int y, int width) {
            super(x,y,width,20,Component.empty(),settings.previewThreadLimit()/(double)CameraPreferences.availableProcessors());
            updateMessage();
        }

        @Override protected void updateMessage() {
            var count = settings.previewThreadLimit() == 0 ? Component.translatable("screen.physical_camera.settings.cpu_auto", settings.previewWorkers())
                    : Component.literal(Integer.toString(settings.previewWorkers()));
            setMessage(Component.translatable("screen.physical_camera.settings.preview_cpu", count, maximum));
        }

        @Override protected void applyValue() {
            int limit = (int)Math.round(value*maximum);
            if (limit != settings.previewThreadLimit()) {
                settings.setPreviewThreadLimit(limit);
                valuesChanged = true;
            }
        }
    }

    private void addSamplingControls(int x, int y, int width) {
        int fieldWidth = 53;
        var label = Component.translatable("screen.physical_camera.settings.samples");
        var field = new EditBox(font,x+width-fieldWidth,y,fieldWidth,20,label);
        field.setMaxLength(4);
        field.setValue(Integer.toString(settings.samples()));
        var slider = new SamplingSlider(x,y,width-fieldWidth-4,label,field);
        field.setResponder(input -> {
            int samples = input.matches("[0-9]{1,4}") ? Integer.parseInt(input) : 0;
            boolean valid = samples >= CameraPreferences.MIN_SAMPLES && samples <= CameraPreferences.MAX_SAMPLES;
            field.setTextColor(valid ? 0xffe0e0e0 : 0xffff9b89);
            if (valid) {
                updateSamples(samples);
                slider.valueFromSettings();
            }
        });
        addRenderableWidget(slider);
        addRenderableWidget(field);
    }

    private void updateSamples(int samples) {
        if (samples != settings.samples()) {
            settings.setSamples(samples);
            valuesChanged = true;
        }
    }

    private static double sliderPosition(int samples) {
        return (samples-CameraPreferences.MIN_SAMPLES)
                / (double)(CameraPreferences.MAX_SAMPLES-CameraPreferences.MIN_SAMPLES);
    }

    private final class SamplingSlider extends AbstractSliderButton {
        private final EditBox field;

        SamplingSlider(int x, int y, int width, Component label, EditBox field) {
            super(x,y,width,20,label,sliderPosition(settings.samples()));
            this.field = field;
        }

        void valueFromSettings() { value = sliderPosition(settings.samples()); }

        @Override protected void updateMessage() {}

        @Override protected void applyValue() {
            int samples = CameraPreferences.MIN_SAMPLES + (int)Math.round(value
                    *(CameraPreferences.MAX_SAMPLES-CameraPreferences.MIN_SAMPLES));
            updateSamples(samples);
            field.setValue(Integer.toString(settings.samples()));
        }
    }

    private int rows() { return Math.max(1,(height-68)/23); }
    private int pages() { return (CameraKeys.Action.values().length+rows()-1)/rows(); }

    private void initShortcuts() {
        var actions = CameraKeys.Action.values();
        page = Math.clamp(page,0,pages()-1);
        int w = Math.min(350,width-16), x = (width-w)/2;
        for (int i = 0; i < rows() && page*rows()+i < actions.length; i++) {
            var action = actions[page*rows()+i];
            var key = controller.keys().get(action);
            var message = Component.translatable(action.id).append("   ").append(listening == action
                    ? Component.translatable("screen.physical_camera.settings.press_key") : key.getTranslatedKeyMessage());
            int y = 32+i*23;
            addRenderableWidget(Button.builder(message,b -> { listening = action; rebuildWidgets(); })
                    .bounds(x,y,w-57,20).build());
            button(Component.translatable("screen.physical_camera.settings.reset"),x+w-53,y,53,() -> bind(action,key.getDefaultKey()));
        }
        int y = height-27, w3 = (w-8)/3;
        button(Component.translatable("screen.physical_camera.settings.previous"),x,y,w3,() -> turn(-1)).active = page > 0;
        button(Component.translatable("screen.physical_camera.settings.back"),x+w3+4,y,w3,this::onClose);
        button(Component.translatable("screen.physical_camera.settings.next"),x+2*(w3+4),y,w3,() -> turn(1)).active = page+1 < pages();
    }

    private Button button(Component text, int x, int y, int w, Runnable pressed) {
        return addRenderableWidget(Button.builder(text,b -> pressed.run()).bounds(x,y,w,20).build());
    }

    private static Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    private void turn(int direction) { listening = null; page += direction; rebuildWidgets(); }

    private void save() {
        persist();
        rebuildWidgets();
    }

    private void persist() {
        try {
            settings.save();
            saveFailed = false;
            valuesChanged = false;
        } catch (IOException error) {
            saveFailed = true;
        }
    }

    @Override public void removed() {
        if (valuesChanged) persist();
        super.removed();
    }

    private void bind(CameraKeys.Action action, InputConstants.Key newKey) {
        controller.keys().get(action).setKey(newKey);
        KeyMapping.resetMapping();
        minecraft.options.save();
        listening = null;
        rebuildWidgets();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (listening == null) return super.keyPressed(event);
        bind(listening,event.key() == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(event));
        return true;
    }

    @Override public void onClose() {
        if (shortcuts) {
            listening = null;
            shortcuts = false;
            rebuildWidgets();
        } else {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        var heading = shortcuts ? Component.translatable("screen.physical_camera.settings.key_page", page+1, pages()) : title;
        g.centeredText(font,heading,
                width/2,13,0xfff6f6f5);
        if (saveFailed) g.centeredText(font,Component.translatable("screen.physical_camera.settings.write_failed"),width/2,height-14,0xffff9b89);
        super.extractRenderState(g,x,y,tick);
    }
}
