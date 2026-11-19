package dev.physicalcamera;

import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomModelData;

public final class CameraEquipment implements ModInitializer {
    static final int BODY_MODEL = 0;
    static final int MOUNTED_MODEL = 1;
    static final int EXTENDED_MODEL = 2;
    static final int LONG_MODEL = 3;
    static final int NOCT_MODEL = 4;
    static final int SUPERTELE_MODEL = 5;
    static final int SUPERTELE_TC_MODEL = 6;
    static final int ULTRAWIDE_MODEL = 7, XCD_MODEL = 1, XCD_FIXED = 2, XCD_FRONT = 3;
    static Item body, lens, longLens, noct, supertele, ultrawide, mediumBody, xcd;
    static ExtendedScreenHandlerType<AssemblyMenu,Integer> menu;

    @Override public void onInitialize() {
        body = registerItem("nikon_z8");
        lens = registerItem("nikkor_z_24_120");
        longLens = registerItem("nikkor_z_100_400");
        noct = registerItem("nikkor_z_58_095_noct");
        supertele = registerItem("nikkor_z_600_tc");
        ultrawide = registerItem("nikkor_z_14_24");
        mediumBody = registerItem("hasselblad_x2d_ii");
        xcd = registerItem("hasselblad_xcd_35_100e");
        menu = Registry.register(BuiltInRegistries.MENU,id("assembly"),new ExtendedScreenHandlerType<>(AssemblyMenu::new,ByteBufCodecs.VAR_INT));
        ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB,id("equipment"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,tab,FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.physical_camera.equipment"))
                .icon(() -> displayBody(true)).displayItems((parameters, output) -> {
                    output.accept(body);
                    output.accept(lens);
                    output.accept(longLens);
                    output.accept(noct);
                    output.accept(supertele);
                    output.accept(ultrawide);
                    output.accept(mediumBody);
                    output.accept(xcd);
                }).build());
        PayloadTypeRegistry.playC2S().register(OpenAssembly.TYPE,StreamCodec.unit(OpenAssembly.INSTANCE));
        ServerPlayNetworking.registerGlobalReceiver(OpenAssembly.TYPE,(payload,context) -> openAssembly(context.player()));
    }

    static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("physical_camera",path); }

    private static Item registerItem(String path) {
        return Registry.register(BuiltInRegistries.ITEM,id(path),new Item(new Item.Properties().stacksTo(1)));
    }

    static ItemStack fittedLens(ItemStack bodyStack) {
        var contents = bodyStack.get(DataComponents.CONTAINER);
        if (contents == null) return ItemStack.EMPTY;
        var fitted = contents.copyOne();
        return canFit(bodyStack,fitted) ? fitted : ItemStack.EMPTY;
    }

    static boolean isBody(ItemStack stack) { return stack.is(body) || stack.is(mediumBody); }
    static SensorFormat sensor(ItemStack stack) { return stack.is(mediumBody) ? SensorFormat.X2D_II : SensorFormat.Z8_FX; }

    static boolean canFit(ItemStack bodyStack, ItemStack lensStack) {
        var lensType = lensType(lensStack);
        return lensType != null && (lensType == ZoomLens.XCD35_100) == bodyStack.is(mediumBody);
    }

    static ZoomLens lensType(ItemStack stack) {
        if (stack.is(xcd)) return ZoomLens.XCD35_100;
        if (stack.is(ultrawide)) return ZoomLens.Z14_24;
        if (stack.is(lens)) return ZoomLens.Z24_120;
        if (stack.is(longLens)) return ZoomLens.Z100_400;
        if (stack.is(supertele)) return ZoomLens.Z600_TC;
        return stack.is(noct) ? ZoomLens.Z58_NOCT : null;
    }

    static void fit(ItemStack bodyStack, ItemStack lensStack) {
        if (!lensStack.isEmpty() && !canFit(bodyStack,lensStack)) return;
        if (lensStack.isEmpty()) bodyStack.remove(DataComponents.CONTAINER);
        else bodyStack.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(lensStack)));
        setModel(bodyStack,switch (lensType(lensStack)) {
            case Z24_120 -> MOUNTED_MODEL;
            case Z100_400 -> LONG_MODEL;
            case Z58_NOCT -> NOCT_MODEL;
            case Z600_TC -> SUPERTELE_MODEL;
            case Z14_24 -> ULTRAWIDE_MODEL;
            case XCD35_100 -> XCD_MODEL;
            case null -> BODY_MODEL;
        });
    }

    static ItemStack displayBody(boolean attached) {
        var stack = new ItemStack(body);
        if (attached) setModel(stack,MOUNTED_MODEL);
        return stack;
    }

    static boolean canAssemble(ItemStack stack) {
        return isBody(stack) || (stack.is(Items.STICK) && legacyFocal(stack) >= 0);
    }

    static int legacyFocal(ItemStack stack) {
        var model = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (model == null || !stack.is(Items.STICK)) return -1;
        return switch (model.value()) {
            case 88000 -> 0;
            case 88001 -> 24;
            case 88002 -> 120;
            default -> -1;
        };
    }

    static void setModel(ItemStack stack, int model) {
        if (model == BODY_MODEL) stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        else stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model));
    }

    private static void openAssembly(ServerPlayer player) {
        var inventory = player.getInventory();
        int slot = canAssemble(inventory.getSelected()) ? inventory.selected : Inventory.SLOT_OFFHAND;
        var held = inventory.getItem(slot);
        if (!canAssemble(held) || player.isSpectator() || player.containerMenu != player.inventoryMenu) return;
        if (!isBody(held)) {
            var upgraded = held.transmuteCopy(body,1);
            fit(upgraded,legacyFocal(held) > 0 ? new ItemStack(lens) : ItemStack.EMPTY);
            inventory.setItem(slot,upgraded);
            if (held.getCount() > 1) {
                inventory.placeItemBackInInventory(held.copyWithCount(held.getCount()-1));
            }
            player.inventoryMenu.broadcastChanges();
        }
        player.openMenu(new ExtendedScreenHandlerFactory<Integer>() {
            @Override public Integer getScreenOpeningData(ServerPlayer target) { return slot; }
            @Override public Component getDisplayName() { return Component.translatable("screen.physical_camera.assembly"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player owner) {
                return new AssemblyMenu(id,inv,slot);
            }
        });
    }

    enum OpenAssembly implements CustomPacketPayload {
        INSTANCE;
        static final Type<OpenAssembly> TYPE = new Type<>(id("open_assembly_v2"));
        @Override public Type<OpenAssembly> type() { return TYPE; }
    }
}
