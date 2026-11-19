package dev.physicalcamera;

import static com.mojang.blaze3d.platform.InputConstants.*;

import java.util.EnumMap;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

final class CameraKeys {
    enum Action {
        RAISE("raise",KEY_G), VIEW("z8_evf",KEY_V), FOCUS("focus",KEY_F), RELEASE("z8_release",KEY_RETURN),
        PLAYBACK("playback",KEY_P), SETTINGS("settings",KEY_O), ASSEMBLY("assembly",KEY_Y),
        SHUTTER_FASTER("shutter_faster",KEY_UP), SHUTTER_SLOWER("shutter_slower",KEY_DOWN),
        ISO_UP("iso_up",KEY_PAGEUP), ISO_DOWN("iso_down",KEY_PAGEDOWN),
        F_UP("aperture_up",KEY_HOME), F_DOWN("aperture_down",KEY_END),
        ZOOM_IN("zoom_in",KEY_EQUALS), ZOOM_OUT("zoom_out",KEY_MINUS),
        FOCUS_NEAR("focus_near",UNKNOWN.getValue()), FOCUS_FAR("focus_far",UNKNOWN.getValue()),
        EV_UP("ev_up",UNKNOWN.getValue()), EV_DOWN("ev_down",UNKNOWN.getValue()),
        EXPOSURE_MODE("mode",UNKNOWN.getValue()), FOCUS_MODE("focus_mode",UNKNOWN.getValue()),
        METER_MODE("meter",UNKNOWN.getValue()), PHOTO_SIZE("photo_size",UNKNOWN.getValue()),
        GRID("grid",UNKNOWN.getValue()), LEVEL("level",UNKNOWN.getValue()), HISTOGRAM("histogram",KEY_H);

        final String id;
        final int defaultCode;
        Action(String id, int defaultCode) { this.id = "key.physical_camera."+id; this.defaultCode = defaultCode; }
    }

    private final EnumMap<Action,KeyMapping> bindings = new EnumMap<>(Action.class);

    CameraKeys() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("physical_camera","z8"));
        for (var action : Action.values()) {
            bindings.put(action,KeyMappingHelper.registerKeyMapping(new KeyMapping(action.id,action.defaultCode,category)));
        }
    }

    KeyMapping get(Action action) { return bindings.get(action); }
}
