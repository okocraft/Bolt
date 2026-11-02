package org.popcraft.bolt.neoforge.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/** Positions that represent one interactable block from either half. */
public final class ProtectionTargets {
    private ProtectionTargets() { }

    public static List<BlockPos> positions(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() == state.getBlock()
                    && otherState.hasProperty(ChestBlock.TYPE)
                    && otherState.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && other.relative(ChestBlock.getConnectedDirection(otherState)).equals(pos)) {
                return List.of(pos, other);
            }
        }
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            Direction direction = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    ? Direction.UP : Direction.DOWN;
            BlockPos other = pos.relative(direction);
            if (level.getBlockState(other).getBlock() == state.getBlock()) return List.of(pos, other);
        }
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART)) {
            BlockPos other = pos.relative(BedBlock.getConnectedDirection(state));
            if (level.getBlockState(other).getBlock() == state.getBlock()) return List.of(pos, other);
        }
        return List.of(pos);
    }
}
