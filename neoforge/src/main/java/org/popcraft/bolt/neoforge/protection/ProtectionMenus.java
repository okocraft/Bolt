package org.popcraft.bolt.neoforge.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.compat.dqm.DqmMenuOrigin;
import org.popcraft.bolt.neoforge.mixin.CompoundContainerAccessor;
import org.popcraft.bolt.neoforge.mixin.MountMenuAccessor;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.Permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Permission decisions for native menus before they are opened or mutated. */
public final class ProtectionMenus {
    private static final Map<AbstractContainerMenu, BlockPos> MENU_ORIGINS = new WeakHashMap<>();
    private ProtectionMenus() { }

    public static boolean mayOpen(BoltRuntime runtime, ServerPlayer player, AbstractContainerMenu menu,
                                  MenuProvider provider) {
        BlockPos origin = null;
        if (provider instanceof BlockEntity block) {
            origin = block.getBlockPos();
            Protection protection = runtime.protections().find(player.level(), block.getBlockPos());
            if (protection != null && !runtime.access().canAccess(protection, player, Permission.OPEN)) return false;
        }
        if (menu instanceof DqmMenuOrigin dqm && dqm.bolt$level() != null && dqm.bolt$pos() != null) {
            Protection protection = runtime.protections().find(dqm.bolt$level(), dqm.bolt$pos());
            if (protection != null && !runtime.access().canAccess(protection, player, Permission.OPEN)) return false;
        }
        for (Protection protection : protections(runtime, player, menu)) {
            if (!runtime.access().canAccess(protection, player, Permission.OPEN)) return false;
        }
        if (origin != null) MENU_ORIGINS.put(menu, origin);
        return true;
    }

    public static boolean mayClick(BoltRuntime runtime, ServerPlayer player, AbstractContainerMenu menu,
                                   int rawSlot, ContainerInput input) {
        List<Protection> all = protections(runtime, player, menu);
        if (all.isEmpty() || input == ContainerInput.CLONE) return true;
        boolean top = rawSlot >= 0 && rawSlot < menu.slots.size()
                && !(menu.slots.get(rawSlot).container instanceof Inventory);
        List<Protection> selected = top ? protections(runtime, player, menu.slots.get(rawSlot).container) : all;
        if (top && selected.isEmpty()) selected = all;
        if (selected.isEmpty()) return true;
        boolean deposit = false;
        boolean withdraw = false;
        switch (input) {
            case PICKUP -> {
                if (!top) return true;
                Slot slot = menu.slots.get(rawSlot);
                ItemStack carried = menu.getCarried();
                ItemStack inSlot = slot.getItem();
                deposit = !carried.isEmpty();
                withdraw = !inSlot.isEmpty();
                if (deposit && withdraw && ItemStack.isSameItemSameComponents(carried, inSlot)
                        && slot.mayPlace(carried) && inSlot.getCount() < slot.getMaxStackSize(inSlot)) {
                    withdraw = false;
                }
            }
            case QUICK_MOVE -> {
                deposit = !top;
                withdraw = top;
            }
            case SWAP -> {
                if (!top) return true;
                deposit = true;
                withdraw = true;
            }
            case THROW -> {
                if (!top) return true;
                withdraw = true;
            }
            case QUICK_CRAFT -> {
                if (!top) return true;
                deposit = true;
            }
            case PICKUP_ALL -> withdraw = true;
            default -> {
                deposit = true;
                withdraw = true;
            }
        }
        for (Protection protection : selected) {
            if (deposit && !runtime.access().canAccess(protection, player, Permission.DEPOSIT)) return false;
            if (withdraw && !runtime.access().canAccess(protection, player, Permission.WITHDRAW)) return false;
        }
        return true;
    }

    private static List<Protection> protections(BoltRuntime runtime, ServerPlayer player,
                                                AbstractContainerMenu menu) {
        List<Protection> found = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        BlockPos origin = MENU_ORIGINS.get(menu);
        if (origin != null) {
            Protection protection = runtime.protections().find(player.level(), origin);
            if (protection != null && seen.add(protection.getId())) found.add(protection);
        }
        if (menu instanceof DqmMenuOrigin dqm && dqm.bolt$level() != null && dqm.bolt$pos() != null) {
            Protection protection = runtime.protections().find(dqm.bolt$level(), dqm.bolt$pos());
            if (protection != null && seen.add(protection.getId())) found.add(protection);
        }
        if (menu instanceof AbstractMountInventoryMenu mountMenu) {
            Protection protection = runtime.protections().find(((MountMenuAccessor) mountMenu).bolt$mount());
            if (protection != null && seen.add(protection.getId())) found.add(protection);
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            for (Protection protection : protections(runtime, player, slot.container)) {
                if (seen.add(protection.getId())) found.add(protection);
            }
        }
        return found;
    }

    private static List<Protection> protections(BoltRuntime runtime, ServerPlayer player, Container container) {
        if (container instanceof BlockEntity block) {
            Protection protection = runtime.protections().find(player.level(), block.getBlockPos());
            return protection == null ? List.of() : List.of(protection);
        }
        if (container instanceof CompoundContainer combined) {
            CompoundContainerAccessor parts = (CompoundContainerAccessor) combined;
            List<Protection> found = new ArrayList<>();
            found.addAll(protections(runtime, player, parts.bolt$first()));
            found.addAll(protections(runtime, player, parts.bolt$second()));
            return found;
        }
        return List.of();
    }
}
