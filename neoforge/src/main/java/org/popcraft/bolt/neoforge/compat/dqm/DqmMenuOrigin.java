package org.popcraft.bolt.neoforge.compat.dqm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Position of a DQM menu opened from a placed storage block. */
public interface DqmMenuOrigin {
    ServerLevel bolt$level();
    BlockPos bolt$pos();
}
