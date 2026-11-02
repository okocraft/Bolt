package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.protection.ProtectionMenus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Check the native click or drag after packet validation and before menu mutation. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMenuClickMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;suppressRemoteUpdates()V"),
            cancellable = true)
    private void bolt$protectClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        var runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (!ProtectionMenus.mayClick(runtime, player, menu,
                packet.slotNum(), packet.containerInput())) {
            menu.sendAllDataToRemote();
            ci.cancel();
        }
    }
}
