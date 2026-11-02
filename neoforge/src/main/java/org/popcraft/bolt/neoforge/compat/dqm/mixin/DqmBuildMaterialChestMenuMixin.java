package org.popcraft.bolt.neoforge.compat.dqm.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.popcraft.bolt.neoforge.compat.dqm.DqmMenuOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.dqm.DqmvBuildMaterialChestMenu", remap = false)
public abstract class DqmBuildMaterialChestMenuMixin implements DqmMenuOrigin {
    @Shadow private BlockPos blockPos;
    @Unique private BlockEntity bolt$chest;

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/core/BlockPos;Lcom/dqm/DqmvBuildMaterialChestBlockEntity;Lnet/minecraft/server/level/ServerPlayer;Z)V",
            at = @At("RETURN"), remap = false)
    private void bolt$captureChest(int id, Inventory inventory, BlockPos pos,
                                   @Coerce BlockEntity chest, ServerPlayer player,
                                   boolean layout, CallbackInfo ci) {
        bolt$chest = chest;
    }

    @Override
    public ServerLevel bolt$level() {
        if (bolt$chest == null || !(bolt$chest.getLevel() instanceof ServerLevel level)
                || !blockPos.equals(bolt$chest.getBlockPos())
                || level.getBlockEntity(blockPos) != bolt$chest) return null;
        return level;
    }

    @Override
    public BlockPos bolt$pos() {
        return bolt$level() == null ? null : blockPos;
    }
}
