package dev.physicalcamera;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public final class Z8ZoomModel {
    private static final ItemStack BODY = component(11);
    private static final ItemStack OUTER = component(12);
    private static final ItemStack FRONT = component(13);
    private static final ItemStack LONG_FIXED = component(14);
    private static final ItemStack LONG_FRONT = component(15);
    private static final ItemStack ULTRAWIDE = component(CameraEquipment.ULTRAWIDE_MODEL);
    private static final ItemStack HASSEL_FIXED = component(new ItemStack(CameraEquipment.mediumBody),CameraEquipment.XCD_FIXED);
    private static final ItemStack HASSEL_FRONT = component(new ItemStack(CameraEquipment.mediumBody),CameraEquipment.XCD_FRONT);
    private static final ItemStack NOCT = component(CameraEquipment.NOCT_MODEL);
    private static final ItemStack SUPERTELE = component(CameraEquipment.SUPERTELE_MODEL);
    private static final ItemStack SUPERTELE_TC = component(CameraEquipment.SUPERTELE_TC_MODEL);

    private Z8ZoomModel() {}

    static double outerTravel(double zoom) { return 3.15*zoom; }
    static double frontTravel(double zoom) { return 4.90*zoom; }

    public static void submit(PoseStack pose, MultiBufferSource collector, int light, int initialFocal, double zoom) {
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

    private static void submit(ItemStack part, double travel, PoseStack pose, MultiBufferSource collector, int light) {
        var client = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(0,0,-travel/16);
        client.getItemRenderer().renderStatic(part,ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                light,OverlayTexture.NO_OVERLAY,pose,collector,client.level,0);
        pose.popPose();
    }

    private static ItemStack component(int model) {
        return component(new ItemStack(CameraEquipment.body),model);
    }

    private static ItemStack component(ItemStack part,int model) {
        CameraEquipment.setModel(part,model);
        return part;
    }
}
