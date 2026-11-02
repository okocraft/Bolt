package org.popcraft.bolt.neoforge.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;
import org.popcraft.bolt.neoforge.integration.BoltBeaconIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BeaconPacketMixin {
    @Shadow public ServerPlayer player;

    @WrapOperation(method = "handleSetBeaconPacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/BeaconMenu;updateEffects(Ljava/util/Optional;Ljava/util/Optional;)Z"))
    private boolean bolt$confirmed(BeaconMenu menu, Optional<Holder<MobEffect>> primary,
                                   Optional<Holder<MobEffect>> secondary, Operation<Boolean> original) {
        boolean updated = original.call(menu, primary, secondary);
        if (updated) BoltBeaconIntegration.effectsConfirmed(player, menu);
        return updated;
    }
}
