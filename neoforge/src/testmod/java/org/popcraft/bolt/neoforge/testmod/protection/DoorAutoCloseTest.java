package org.popcraft.bolt.neoforge.testmod.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Permission;

/** Native gate interaction and delayed close, including a replaced-block guard. */
final class DoorAutoCloseTest {
    private static final BlockPos CLOSE = new BlockPos(4200, 250, 4220);
    private static final BlockPos REPLACED = new BlockPos(4203, 250, 4220);
    private static Pending pending;

    private DoorAutoCloseTest() { }

    static void start(ServerLevel level, BoltRuntime runtime, FakePlayer owner) {
        if (pending != null || !level.getBlockState(CLOSE).isAir()
                || !level.getBlockState(REPLACED).isAir()) {
            throw new AssertionError("Auto-close fixture is occupied");
        }
        int delay = runtime.settings().autoCloseTicks();
        if (delay <= 0) throw new AssertionError("Auto-close fixture requires a positive configured delay");
        BlockProtection closeLock = null;
        BlockProtection replacedLock = null;
        try {
            level.setBlockAndUpdate(CLOSE, Blocks.OAK_FENCE_GATE.defaultBlockState());
            level.setBlockAndUpdate(REPLACED, Blocks.OAK_FENCE_GATE.defaultBlockState());
            closeLock = runtime.protections().create(level, CLOSE, owner.getUUID(), "private");
            replacedLock = runtime.protections().create(level, REPLACED, owner.getUUID(), "private");
            runtime.protections().save(closeLock);
            runtime.protections().save(replacedLock);
            var doorSource = new SourceTypeResolver(Source.of(SourceTypes.DOOR));
            if (runtime.access().canAccess(closeLock, doorSource, Permission.AUTO_CLOSE)) {
                throw new AssertionError("Auto-close was already granted before /cautoclose source");
            }
            String source = Source.of(SourceTypes.DOOR).toString();
            closeLock.getAccess().put(source, "normal");
            replacedLock.getAccess().put(source, "normal");
            runtime.protections().save(closeLock);
            runtime.protections().save(replacedLock);
            if (!runtime.access().canAccess(closeLock, doorSource, Permission.AUTO_CLOSE)) {
                throw new AssertionError("/cautoclose source did not grant AUTO_CLOSE");
            }
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            open(level, owner, CLOSE);
            open(level, owner, REPLACED);
            level.setBlockAndUpdate(REPLACED, Blocks.STONE.defaultBlockState());
            pending = new Pending(level, runtime, closeLock, replacedLock,
                    level.getGameTime() + delay + 1);
        } catch (RuntimeException | Error failure) {
            cleanup(level, runtime, closeLock, replacedLock);
            throw failure;
        }
    }

    static boolean tick(ServerLevel level) {
        Pending active = pending;
        if (active == null) return false;
        if (active.level != level) throw new AssertionError("Auto-close fixture dimension changed");
        if (level.getGameTime() < active.deadline) return false;
        try {
            if (!level.getBlockState(CLOSE).is(Blocks.OAK_FENCE_GATE)
                    || level.getBlockState(CLOSE).getValue(BlockStateProperties.OPEN)) {
                throw new AssertionError("/cautoclose gate remained open after configured server ticks");
            }
            if (!level.getBlockState(REPLACED).is(Blocks.STONE)) {
                throw new AssertionError("Auto-close changed a replaced block");
            }
            System.out.println("BOLT_AUTO_CLOSE_TESTS_PASSED");
            return true;
        } finally {
            cleanup(level, active.runtime, active.closeLock, active.replacedLock);
            pending = null;
        }
    }

    private static void open(ServerLevel level, FakePlayer owner, BlockPos pos) {
        owner.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var interact = new PlayerInteractEvent.RightClickBlock(owner, InteractionHand.MAIN_HAND, pos, hit);
        NeoForge.EVENT_BUS.post(interact);
        if (interact.isCanceled()) throw new AssertionError("Owner gate interaction was canceled");
        level.getBlockState(pos).useWithoutItem(level, owner, hit);
        if (!level.getBlockState(pos).getValue(BlockStateProperties.OPEN)) {
            throw new AssertionError("Owner gate interaction did not open the gate");
        }
    }

    private static void cleanup(ServerLevel level, BoltRuntime runtime,
                                BlockProtection closeLock, BlockProtection replacedLock) {
        if (closeLock != null) runtime.protections().remove(closeLock);
        if (replacedLock != null) runtime.protections().remove(replacedLock);
        level.setBlockAndUpdate(CLOSE, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(REPLACED, Blocks.AIR.defaultBlockState());
    }

    private record Pending(ServerLevel level, BoltRuntime runtime, BlockProtection closeLock,
                           BlockProtection replacedLock, long deadline) { }
}
