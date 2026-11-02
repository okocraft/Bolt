package org.popcraft.bolt.neoforge.testmod.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.protection.BlockProtection;

import java.util.UUID;

/** Vanilla hopper extraction from an actual DQM huge chest, with a positive control. */
final class DqmHugeChestHopperTest {
    private static final Identifier CHEST = Identifier.parse("dqmvii:legacy_block_chestb7");
    private static final BlockPos CONTROL = new BlockPos(4240, 250, 4200);
    private static final BlockPos LOCKED = new BlockPos(4250, 250, 4200);
    private static final UUID OWNER = UUID.fromString("8b4a4e82-1852-4ba0-b913-e3fb5f52789a");

    private DqmHugeChestHopperTest() { }

    static void run(ServerLevel level) {
        var runtime = BoltNeoForge.runtime();
        if (runtime == null) throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: Bolt runtime absent");
        Block block = BuiltInRegistries.BLOCK.getValue(CHEST);
        if (block == null || !CHEST.equals(BuiltInRegistries.BLOCK.getKey(block))) {
            throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: huge chest block absent");
        }
        for (BlockPos pos : new BlockPos[] {CONTROL, LOCKED}) {
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).isAir()) {
                throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: fixture space occupied");
            }
        }
        BlockProtection protection = null;
        try {
            for (BlockPos pos : new BlockPos[] {CONTROL, LOCKED}) {
                level.setBlockAndUpdate(pos.below(), Blocks.HOPPER.defaultBlockState());
                level.setBlockAndUpdate(pos, block.defaultBlockState());
            }
            Container openChest = chest(level, CONTROL);
            Container lockedChest = chest(level, LOCKED);
            HopperBlockEntity openHopper = hopper(level, CONTROL.below());
            HopperBlockEntity lockedHopper = hopper(level, LOCKED.below());
            openChest.setItem(0, new ItemStack(Items.DIAMOND));
            lockedChest.setItem(0, new ItemStack(Items.EMERALD));
            if (!openChest.getItem(0).is(Items.DIAMOND) || !lockedChest.getItem(0).is(Items.EMERALD)) {
                throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: chest storage rejected fixture items");
            }
            HopperBlockEntity.suckInItems(level, openHopper);
            boolean controlMoved = openChest.getItem(0).isEmpty() && openHopper.getItem(0).is(Items.DIAMOND);
            System.out.println("DQM_HUGE_CHEST_HOPPER_CONTROL moved=" + controlMoved);
            if (!controlMoved) {
                throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: unprotected native hopper did not extract");
            }

            if (!runtime.protections().isProtectable(level, LOCKED)) {
                throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: DQM chest not protectable");
            }
            protection = runtime.protections().create(level, LOCKED, OWNER,
                    runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
            HopperBlockEntity.suckInItems(level, lockedHopper);
            boolean lockedMoved = lockedChest.getItem(0).isEmpty() || !lockedHopper.getItem(0).isEmpty();
            System.out.println("DQM_HUGE_CHEST_HOPPER_LOCKED moved=" + lockedMoved);
            if (lockedMoved) throw new AssertionError("DQM_HUGE_CHEST_PROTECTION_FAILED: hopper extracted from locked DQM chest");
            System.out.println("DQM_HUGE_CHEST_HOPPER_PASSED");
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            for (BlockPos pos : new BlockPos[] {CONTROL, LOCKED}) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static Container chest(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container container) return container;
        throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: placed chest has no Container");
    }

    private static HopperBlockEntity hopper(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof HopperBlockEntity block) return block;
        throw new AssertionError("DQM_HUGE_CHEST_INCONCLUSIVE: hopper block entity absent");
    }
}
