package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.protection.ProtectionMenus;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.util.Permission;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reject protected menus before the open-screen packet is sent. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMenuMixin {
    @Redirect(method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
            at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/MenuProvider;createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;"))
    private AbstractContainerMenu bolt$createMenu(MenuProvider provider, int id, Inventory inventory, Player player) {
        AbstractContainerMenu menu = provider.createMenu(id, inventory, player);
        if (menu == null) return null;
        var runtime = BoltNeoForge.runtime();
        if (runtime == null || ProtectionMenus.mayOpen(runtime, (ServerPlayer) (Object) this, menu, provider)) {
            return menu;
        }
        menu.removed(player);
        return null;
    }

    @Inject(method = "openHorseInventory", at = @At("HEAD"), cancellable = true)
    private void bolt$openHorseInventory(AbstractHorse horse, Container inventory, CallbackInfo ci) {
        var runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        EntityProtection protection = runtime.protections().find(horse);
        if (protection != null && !runtime.access().canAccess(protection,
                (ServerPlayer) (Object) this, Permission.OPEN)) ci.cancel();
    }
}
