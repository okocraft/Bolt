package org.popcraft.bolt.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.popcraft.bolt.neoforge.protection.ProtectionAutomation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireProtectionMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void bolt$burn(Level level, BlockPos target, int chance, RandomSource random,
                           int age, Direction face, CallbackInfo ci) {
        if (level instanceof ServerLevel server && !ProtectionAutomation.mayBurn(server, target)) ci.cancel();
    }
}
