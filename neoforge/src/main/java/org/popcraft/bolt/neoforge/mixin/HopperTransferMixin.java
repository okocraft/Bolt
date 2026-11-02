package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.popcraft.bolt.neoforge.protection.ProtectionAutomation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperTransferMixin {
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private static void bolt$transfer(Container source, Container destination, ItemStack stack,
                                      Direction side, CallbackInfoReturnable<ItemStack> cir) {
        if (!ProtectionAutomation.mayTransfer(source, destination)) cir.setReturnValue(stack);
    }

    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void bolt$collectItem(Container destination, ItemEntity item,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!ProtectionAutomation.mayCollectItem(destination)) cir.setReturnValue(false);
    }
}
