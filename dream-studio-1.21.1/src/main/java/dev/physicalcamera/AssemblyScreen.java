package dev.physicalcamera;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final int WIDTH = 306, HEIGHT = 233, INK = 0xff404040;
    private static final String LABEL = "screen.physical_camera.assembly.";
    private static final ResourceLocation BODY = CameraEquipment.id("textures/gui/z8_plan.png");
    private static final ResourceLocation MOUNTED = CameraEquipment.id("textures/gui/z8_with_lens_plan.png");
    private static final ResourceLocation LONG_MOUNTED = CameraEquipment.id("textures/gui/z8_with_100_400_plan.png");
    private static final ResourceLocation NOCT_MOUNTED = CameraEquipment.id("textures/gui/z8_with_58_noct_plan.png");
    private static final ResourceLocation SUPERTELE_MOUNTED = CameraEquipment.id("textures/gui/z8_with_600_plan.png");
    private static final ResourceLocation SUPERTELE_TC_MOUNTED = CameraEquipment.id("textures/gui/z8_with_840_plan.png");
    private static final ResourceLocation NOCT_PREVIEW = CameraEquipment.id("textures/gui/assembly_preview_58_noct.png");
    private static final ResourceLocation SUPERTELE_PREVIEW = CameraEquipment.id("textures/gui/assembly_preview_600.png");
    private static final ResourceLocation BODY_PREVIEW = CameraEquipment.id("textures/gui/assembly_preview_body.png");
    private static final ResourceLocation MOUNTED_PREVIEW = CameraEquipment.id("textures/gui/assembly_preview_24_120.png");
    private static final ResourceLocation LONG_PREVIEW = CameraEquipment.id("textures/gui/assembly_preview_100_400.png");

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu,inventory,title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    static void register() {
        MenuScreens.register(CameraEquipment.menu,AssemblyScreen::new);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        renderBackground(graphics,mouseX,mouseY,tick);
        super.render(graphics,mouseX,mouseY,tick);
        renderTooltip(graphics,mouseX,mouseY);
    }

    @Override protected void renderBg(GuiGraphics graphics, float tick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x,y,x+WIDTH,y+HEIGHT,0xff181818);
        graphics.fill(x+1,y+1,x+WIDTH-1,y+HEIGHT-1,0xff555555);
        graphics.fill(x+1,y+1,x+WIDTH-3,y+HEIGHT-3,0xffffffff);
        graphics.fill(x+3,y+3,x+WIDTH-3,y+HEIGHT-3,0xffc6c6c6);
        panel(graphics,x+7,y+19,165,117,0xffaaa9a6);
        panel(graphics,x+177,y+19,122,117,0xff151e27);
        panel(graphics,x+177,y+147,122,80,0xffdadada);
        graphics.fill(x+8,y+110,x+171,y+111,0xff91908e);
        drawSlot(graphics,x+AssemblyMenu.LENS_X,y+AssemblyMenu.EQUIPMENT_Y,true);
        drawSlot(graphics,x+AssemblyMenu.HOT_SHOE_X,y+AssemblyMenu.EQUIPMENT_Y,false);
        drawSlot(graphics,x+AssemblyMenu.TRIPOD_X,y+AssemblyMenu.EQUIPMENT_Y,false);
        for (int i = 0; i < 36; i++) {
            drawSlot(graphics,x+AssemblyMenu.INVENTORY_X+i%9*18,
                    y+(i<27 ? AssemblyMenu.INVENTORY_Y+i/9*18 : AssemblyMenu.HOTBAR_Y),true);
        }
        describeSlot(graphics,mouseX,mouseY,AssemblyMenu.HOT_SHOE_X,LABEL+"hot_shoe",true);
        describeSlot(graphics,mouseX,mouseY,AssemblyMenu.TRIPOD_X,LABEL+"tripod",true);
        if (!menu.attached()) {
            describeSlot(graphics,mouseX,mouseY,AssemblyMenu.LENS_X,"screen.physical_camera.lens",false);
        }
    }

    private static void panel(GuiGraphics g, int x, int y, int width, int height, int fill) {
        g.fill(x,y,x+width,y+height,0xff828282);
        g.fill(x+1,y+1,x+width-1,y+height-1,fill);
    }

    private static void drawSlot(GuiGraphics g, int x, int y, boolean enabled) {
        g.fill(x-1,y-1,x+17,y+17,0xffffffff);
        g.fill(x-1,y-1,x+16,y+16,0xff373737);
        g.fill(x,y,x+16,y+16,0xff8b8b8b);
        if (!enabled) {
            g.fill(x+1,y+1,x+15,y+15,0xff777777);
            g.fill(x+5,y+8,x+11,y+9,0xff4e4e4e);
        }
    }

    private void describeSlot(GuiGraphics g, int mouseX, int mouseY, int slotX, String label, boolean reserved) {
        int x = leftPos+slotX, y = topPos+AssemblyMenu.EQUIPMENT_Y;
        if (mouseX >= x && mouseX < x+16 && mouseY >= y && mouseY < y+16) {
            var hint = Component.translatable(label);
            if (reserved) hint.append(" · ").append(assemblyLabel("reserved"));
            g.renderTooltip(font,hint,mouseX,mouseY);
        }
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        ZoomLens lens = menu.lensType();
        graphics.drawString(font,menu.mediumFormat() ? Component.translatable("item.physical_camera.hasselblad_x2d_ii") : title,8,6,INK,false);
        graphics.drawString(font,playerInventoryTitle,8,143,INK,false);
        drawPreview(graphics,lens);
        ResourceLocation plan = switch (lens) {
            case Z24_120 -> MOUNTED;
            case Z100_400 -> LONG_MOUNTED;
            case Z58_NOCT -> NOCT_MOUNTED;
            case Z600_TC -> superteleConverterEngaged() ? SUPERTELE_TC_MOUNTED : SUPERTELE_MOUNTED;
            case Z14_24 -> CameraEquipment.id("textures/gui/z8_with_14_24_plan.png");
            case XCD35_100 -> CameraEquipment.id("textures/gui/x2d_ii_xcd35_100_plan.png");
            case null -> menu.mediumFormat() ? CameraEquipment.id("textures/gui/x2d_ii_body_plan.png") : BODY;
        };
        graphics.blit(plan,182,21,112,112,0f,0f,448,448,448,448);
        drawParameters(graphics,lens);
    }

    private void drawPreview(GuiGraphics g, ZoomLens lens) {
        ResourceLocation preview = switch (lens) {
            case Z24_120 -> MOUNTED_PREVIEW;
            case Z100_400 -> LONG_PREVIEW;
            case Z58_NOCT -> NOCT_PREVIEW;
            case Z600_TC -> SUPERTELE_PREVIEW;
            case Z14_24 -> CameraEquipment.id("textures/gui/assembly_preview_14_24.png");
            case XCD35_100 -> CameraEquipment.id("textures/gui/assembly_preview_xcd35_100.png");
            case null -> menu.mediumFormat() ? CameraEquipment.id("textures/gui/assembly_preview_x2d_ii.png") : BODY_PREVIEW;
        };
        g.blit(preview,9,21,160,88,0f,0f,640,352,640,352);
    }

    private static boolean superteleConverterEngaged() {
        var settings = CameraClient.assemblySettings();
        return settings.lens.converterEngaged(settings.focalLength());
    }

    private void drawParameters(GuiGraphics g, ZoomLens lens) {
        boolean mounted = lens != null;
        var settings = CameraClient.assemblySettings();
        boolean current = !mounted || lens == settings.lens;
        var measured = current ? settings.lastExposure(mounted) : null;
        boolean metered = measured != null;
        var exposure = metered ? measured : settings.exposure(.18,!mounted);
        var auto = assemblyLabel("auto");
        var missing = Component.literal("—");
        var focal = mounted ? Component.literal((current ? lens.snapFocal(settings.focalLength()) : lens.wide)+" mm") : missing;
        boolean fixedAperture = settings.mode == Z8Settings.Mode.M || settings.mode == Z8Settings.Mode.A;
        var aperture = mounted ? metered || fixedAperture
                ? Component.literal(Z8Settings.apertureLabel(current ? exposure.aperture()
                        : Math.clamp(settings.lens.apertures()[settings.apertureIndex()],lens.wideOpen(lens.wide),
                                lens.apertures()[lens.lastAperture(lens.wide)])))
                : auto : missing;
        boolean fixedShutter = settings.mode == Z8Settings.Mode.M || settings.mode == Z8Settings.Mode.S;
        var shutter = metered || fixedShutter ? Component.literal(Z8Settings.shutterLabel(exposure.seconds())) : auto;
        var iso = !settings.autoIso || metered
                ? Component.literal((settings.autoIso ? "A " : "")+exposure.iso()) : auto;
        g.drawString(font,assemblyLabel("parameters"),182,151,INK,false);
        g.fill(182,161,294,162,0xffaaaaaa);
        parameter(g,"mode",Component.literal(settings.mode.name()),164);
        parameter(g,"focal",focal,173);
        parameter(g,"aperture",aperture,182);
        parameter(g,"shutter",shutter,191);
        parameter(g,"iso",iso,200);
        parameter(g,"size",Component.literal(settings.photoSize.label),209);
        parameter(g,"format",Component.literal(settings.photoFormat.label),218);
    }

    private void parameter(GuiGraphics g, String label, Component value, int y) {
        g.drawString(font,assemblyLabel(label),182,y,INK,false);
        g.drawString(font,value,294-font.width(value),y,0xff252525,false);
    }

    private static Component assemblyLabel(String name) {
        return Component.translatable(LABEL+name);
    }
}
