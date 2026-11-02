package org.popcraft.bolt.neoforge.compat.dqm.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.popcraft.bolt.neoforge.compat.dqm.DqmMenuOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.dqm.DqmvLargeBagMenu", remap = false)
public abstract class DqmLargeBagMenuMixin implements DqmMenuOrigin {
    @Shadow private BlockPos openedFrom;
    @Shadow private Level openedFromLevel;
    @Shadow public abstract boolean isFromBlock();

    @Override
    public ServerLevel bolt$level() {
        return isFromBlock() && openedFrom != null && openedFromLevel instanceof ServerLevel level
                ? level : null;
    }

    @Override
    public BlockPos bolt$pos() {
        return bolt$level() == null ? null : openedFrom;
    }
}
