package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.popcraft.bolt.neoforge.protection.ProtectionAutomation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DropperBlock.class)
public abstract class DropperRedstoneMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void bolt$dispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!ProtectionAutomation.mayActivateRedstone(level, pos)) ci.cancel();
    }
}
