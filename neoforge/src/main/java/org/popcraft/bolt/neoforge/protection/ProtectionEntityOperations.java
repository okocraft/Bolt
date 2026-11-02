package org.popcraft.bolt.neoforge.protection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.util.Permission;

/** Checks the actual armor-stand equipment mutation, including swaps. */
public final class ProtectionEntityOperations {
    private ProtectionEntityOperations() { }

    public static boolean maySwap(ArmorStand stand, Player player, EquipmentSlot slot, ItemStack inserted) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        var runtime = BoltNeoForge.runtime();
        if (runtime == null) return true;
        var protection = runtime.protections().find(stand);
        if (protection == null) return true;
        if (!runtime.access().canAccess(protection, serverPlayer, Permission.INTERACT)) return false;
        if (!inserted.isEmpty() && !runtime.access().canAccess(protection, serverPlayer, Permission.DEPOSIT)) {
            return false;
        }
        return stand.getItemBySlot(slot).isEmpty()
                || runtime.access().canAccess(protection, serverPlayer, Permission.WITHDRAW);
    }
}
