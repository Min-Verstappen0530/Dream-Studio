package dev.physicalcamera;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class Z8ZoomModel {
    private static final ItemStack BODY = component("z8_zoom_body");
    private static final ItemStack OUTER = component("z8_zoom_outer");
    private static final ItemStack FRONT = component("z8_zoom_front");
    private static final ItemStack LONG_FIXED = component("z8_100_400_fixed");
    private static final ItemStack LONG_FRONT = component("z8_100_400_front");
    private static final ItemStack NOCT = component("nikon_z8_z58_noct");
    private static final ItemStack ULTRAWIDE = component(CameraEquipment.ULTRAWIDE_MODEL);
    private static final ItemStack HASSEL_FIXED = component("x2d_ii_xcd_fixed");
    private static final ItemStack HASSEL_FRONT = component("x2d_ii_xcd_front");
    private static final ItemStack SUPERTELE = component(CameraEquipment.SUPERTELE_MODEL);
    private static final ItemStack SUPERTELE_TC = component(CameraEquipment.SUPERTELE_TC_MODEL);

    private Z8ZoomModel() {}

    static double outerTravel(double zoom) { return 3.15*zoom; }
    static double frontTravel(double zoom) { return 4.90*zoom; }

    public static void submit(PoseStack pose, SubmitNodeCollector collector, int light, int initialFocal, double zoom) {
        if (initialFocal == ZoomLens.XCD35_100.wide) {
            submit(HASSEL_FIXED,0,pose,collector,light);
            submit(HASSEL_FRONT,4*zoom,pose,collector,light);
            return;
        }
        if (initialFocal == ZoomLens.Z14_24.wide) {
            submit(ULTRAWIDE,0,pose,collector,light);
            return;
        }
        if (initialFocal == ZoomLens.Z600_TC.wide) {
            submit(zoom >= .5 ? SUPERTELE_TC : SUPERTELE,0,pose,collector,light);
            return;
        }
        if (initialFocal == ZoomLens.Z100_400.wide) {
            submit(LONG_FIXED,0,pose,collector,light);
            submit(LONG_FRONT,4.90*zoom,pose,collector,light);
            return;
        }
        if (initialFocal == ZoomLens.Z58_NOCT.wide) {
            submit(NOCT,0,pose,collector,light);
            return;
        }
        submit(BODY,0,pose,collector,light);
        submit(OUTER,outerTravel(zoom),pose,collector,light);
        submit(FRONT,frontTravel(zoom),pose,collector,light);
    }

    private static void submit(ItemStack part, double travel, PoseStack pose, SubmitNodeCollector collector, int light) {
        var client = Minecraft.getInstance();
        var state = new ItemStackRenderState();
        client.getItemModelResolver().updateForLiving(state,part,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,client.player);
        pose.pushPose();
        pose.translate(0,0,-travel/16);
        state.submit(pose,collector,light,OverlayTexture.NO_OVERLAY,0);
        pose.popPose();
    }

    private static ItemStack component(String path) {
        return component(CameraEquipment.id(path));
    }

    private static ItemStack component(Identifier model) {
        var stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.ITEM_MODEL,model);
        return stack;
    }
}
