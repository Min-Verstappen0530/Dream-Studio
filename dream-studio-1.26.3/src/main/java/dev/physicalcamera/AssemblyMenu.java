package dev.physicalcamera;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class AssemblyMenu extends AbstractContainerMenu {
    static final int TRIPOD_X = 41, HOT_SHOE_X = 81, LENS_X = 121, EQUIPMENT_Y = 115;
    static final int INVENTORY_X = 8, INVENTORY_Y = 154, HOTBAR_Y = 212;
    static final int EQUIPMENT_SLOTS = 3;
    private final Inventory inventory;
    private final int bodySlot;
    private final ItemStack body;

    AssemblyMenu(int id, Inventory inventory, int bodySlot) {
        super(CameraEquipment.menu,id);
        this.inventory = inventory;
        this.bodySlot = bodySlot;
        body = inventory.getItem(bodySlot);
        var fitting = new SimpleContainer(CameraEquipment.fittedLens(body)) {
            @Override public void setChanged() {
                super.setChanged();
                if (inventory.player instanceof ServerPlayer && inventory.getItem(bodySlot) == body) {
                    CameraEquipment.fit(body,getItem(0));
                    inventory.setChanged();
                }
            }
        };
        addSlot(new Slot(fitting,0,LENS_X,EQUIPMENT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return CameraEquipment.canFit(body,stack); }
        });
        var reserved = new SimpleContainer(2);
        addReservedSlot(reserved,0,HOT_SHOE_X,EQUIPMENT_Y);
        addReservedSlot(reserved,1,TRIPOD_X,EQUIPMENT_Y);
        for (int i = 0; i < 36; i++) {
            int index = i < 27 ? i+9 : i-27;
            int x = INVENTORY_X+i%9*18, y = i < 27 ? INVENTORY_Y+i/9*18 : HOTBAR_Y;
            addSlot(new Slot(inventory,index,x,y) {
                @Override public boolean mayPlace(ItemStack stack) { return index != bodySlot; }
                @Override public boolean mayPickup(Player player) { return index != bodySlot; }
            });
        }
    }

    private void addReservedSlot(SimpleContainer container, int index, int x, int y) {
        addSlot(new Slot(container,index,x,y) {
            @Override public boolean isActive() { return false; }
        });
    }

    boolean attached() { return getSlot(0).hasItem(); }
    ZoomLens lensType() { return CameraEquipment.lensType(getSlot(0).getItem()); }
    boolean mediumFormat() { return CameraEquipment.sensor(body) == SensorFormat.X2D_II; }

    @Override public boolean stillValid(Player player) {
        return !(player instanceof ServerPlayer) || inventory.getItem(bodySlot) == body && CameraEquipment.isBody(body)
                && (bodySlot == Inventory.SLOT_OFFHAND || inventory.getSelectedSlot() == bodySlot);
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (slot > 0 && slot < EQUIPMENT_SLOTS
                || (input == ContainerInput.SWAP && button == bodySlot)
                || (slot >= EQUIPMENT_SLOTS && slot < slots.size() && slots.get(slot).getContainerSlot() == bodySlot)) return;
        super.clicked(slot,button,input,player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        var slot = slots.get(index);
        if (!slot.isActive() || !slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        if (!moveItemStackTo(stack,index == 0 ? EQUIPMENT_SLOTS : 0,index == 0 ? slots.size() : 1,index == 0)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player,stack);
        return original;
    }
}
