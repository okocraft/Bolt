package org.popcraft.bolt.neoforge.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.commands.PendingActions;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.Mode;
import org.popcraft.bolt.util.Permission;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.UUID;

/** Player block actions and post-success lock lifecycle. */
final class BlockProtectionEvents {
    private static final SourceTypeResolver DOOR_SOURCE = new SourceTypeResolver(Source.of(SourceTypes.DOOR));
    private final List<BreakCheck> broken = new ArrayList<>();
    private final List<PlaceCheck> placed = new ArrayList<>();
    private final Map<CloseKey, CloseCheck> closing = new HashMap<>();

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        BlockPos pos = event.getPos();
        if (PendingActions.consume(player, level, pos)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        BlockProtection protection = runtime.protections().find(level, pos);
        if (protection != null && !runtime.access().canAccess(protection, player, Permission.INTERACT)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        } else if (protection != null) {
            protection.setAccessed(System.currentTimeMillis());
            runtime.protections().save(protection);
            scheduleAutoClose(runtime, player, level, pos, protection);
        }
    }

    @SubscribeEvent
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (PendingActions.consume(player, player.level(), event.getPos())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onBreak(BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        BlockProtection protection = runtime.protections().find(level, event.getPos());
        if (protection == null) return;
        if (!runtime.access().canAccess(protection, player, Permission.DESTROY)) {
            event.setCanceled(true);
            event.setNotifyClient(true);
            return;
        }
        BlockPos partner = null;
        if (event.getState().getBlock() instanceof ChestBlock) {
            for (BlockPos linked : ProtectionTargets.positions(level, event.getPos())) {
                if (!linked.equals(event.getPos())) partner = linked;
            }
        }
        broken.add(new BreakCheck(level, event.getPos(), event.getState(), partner, protection));
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent many
                ? many.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        for (BlockSnapshot snapshot : snapshots) {
            BlockProtection protection = runtime.protections().find(level, snapshot.getPos());
            BlockState before = snapshot.getState();
            BlockState after = snapshot.getCurrentState();
            String required = !before.isAir() && before.getBlock() != after.getBlock()
                    ? Permission.DESTROY : Permission.INTERACT;
            if (protection != null && !runtime.access().canAccess(protection, player, required)) {
                event.setCanceled(true);
                return;
            }
        }
        if (runtime.bolt().getBoltPlayer(player.getUUID()).hasMode(Mode.NOLOCK)) return;
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            if (runtime.settings().autoProtect(level, pos)) {
                placed.add(new PlaceCheck(level, pos, level.getBlockState(pos), player));
            }
        }
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) {
            broken.clear();
            placed.clear();
            closing.clear();
            return;
        }
        MinecraftServer server = event.getServer();
        for (BreakCheck check : broken) {
            if (check.level.getServer() != server || check.level.getBlockState(check.pos).getBlock()
                    == check.oldState.getBlock()) continue;
            if (check.partner != null && check.level.getBlockState(check.partner).getBlock()
                    == check.oldState.getBlock()) {
                check.protection.setX(check.partner.getX());
                check.protection.setY(check.partner.getY());
                check.protection.setZ(check.partner.getZ());
                runtime.protections().save(check.protection);
            } else runtime.protections().remove(check.protection);
        }
        broken.clear();
        for (PlaceCheck check : placed) {
            if (check.level.getServer() != server || check.level.getBlockState(check.pos).getBlock()
                    != check.state.getBlock() || !runtime.settings().autoProtect(check.level, check.pos)
                    || runtime.protections().find(check.level, check.pos) != null) continue;
            ServerPlayer player = check.player;
            if (player != null && !runtime.bolt().getBoltPlayer(player.getUUID()).hasMode(Mode.NOLOCK)) {
                runtime.protections().lock(player, check.level, check.pos,
                        runtime.settings().autoProtectionType(check.level, check.pos));
            }
        }
        placed.clear();
        Iterator<Map.Entry<CloseKey, CloseCheck>> iterator = closing.entrySet().iterator();
        while (iterator.hasNext()) {
            CloseCheck check = iterator.next().getValue();
            if (check.level.getServer() != server || check.level.getGameTime() < check.dueGameTime) continue;
            iterator.remove();
            BlockState current = check.level.getBlockState(check.pos);
            BlockProtection protection = runtime.protections().find(check.level, check.pos);
            if (current.getBlock() != check.block.getBlock()
                    || !current.hasProperty(BlockStateProperties.OPEN)
                    || !current.getValue(BlockStateProperties.OPEN)
                    || protection == null || !protection.getId().equals(check.protectionId)) continue;
            for (BlockPos linked : ProtectionTargets.positions(check.level, check.pos)) {
                BlockState linkedState = check.level.getBlockState(linked);
                if (linkedState.getBlock() == check.block.getBlock()
                        && linkedState.hasProperty(BlockStateProperties.OPEN)
                        && linkedState.getValue(BlockStateProperties.OPEN)) {
                    check.level.setBlockAndUpdate(linked,
                            linkedState.setValue(BlockStateProperties.OPEN, false));
                }
            }
        }
    }

    private void scheduleAutoClose(BoltRuntime runtime, ServerPlayer player, ServerLevel level,
                                   BlockPos pos, BlockProtection protection) {
        int delay = runtime.settings().autoCloseTicks();
        if (delay <= 0) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock)
                || !state.hasProperty(BlockStateProperties.OPEN)
                || state.getValue(BlockStateProperties.OPEN)) return;
        if (!runtime.access().canAccess(protection, player, Permission.AUTO_CLOSE)
                && !runtime.access().canAccess(protection, DOOR_SOURCE, Permission.AUTO_CLOSE)) return;
        BlockPos canonical = ProtectionTargets.positions(level, pos).stream()
                .min((first, second) -> Integer.compare(first.getY(), second.getY())).orElse(pos);
        closing.put(new CloseKey(level, canonical), new CloseCheck(level, canonical,
                level.getBlockState(canonical), protection.getId(), level.getGameTime() + delay));
    }

    private record BreakCheck(ServerLevel level, BlockPos pos, BlockState oldState,
                              BlockPos partner, BlockProtection protection) { }

    private record PlaceCheck(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) { }
    private record CloseKey(ServerLevel level, BlockPos pos) { }
    private record CloseCheck(ServerLevel level, BlockPos pos, BlockState block,
                              UUID protectionId, long dueGameTime) { }
}
