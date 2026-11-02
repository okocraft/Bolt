package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.popcraft.bolt.neoforge.protection.ProtectionEntityOperations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmorStand.class)
public abstract class ArmorStandSwapMixin {
    @Inject(method = "swapItem", at = @At("HEAD"), cancellable = true)
    private void bolt$protectEquipment(Player player, EquipmentSlot slot, ItemStack inserted,
                                        InteractionHand hand, CallbackInfoReturnable<Boolean> cir) {
        if (!ProtectionEntityOperations.maySwap((ArmorStand) (Object) this, player, slot, inserted)) {
            cir.setReturnValue(false);
        }
    }
}
