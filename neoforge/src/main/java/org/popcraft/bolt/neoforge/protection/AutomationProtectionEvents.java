package org.popcraft.bolt.neoforge.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explosions and piston movement cannot bypass stored block protections. */
final class AutomationProtectionEvents {
    private static final SourceTypeResolver ENTITY_SOURCE = new SourceTypeResolver(Source.of(SourceTypes.ENTITY));
    private final Map<PistonKey, PistonPlan> pistonPlans = new HashMap<>();

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        LivingEntity source = event.getExplosion().getIndirectSourceEntity();
        event.getAffectedBlocks().removeIf(pos -> {
            BlockProtection protection = runtime.protections().find(level, pos);
            if (protection == null) return false;
            if (source instanceof ServerPlayer player) {
                return !runtime.access().canAccess(protection, player, Permission.DESTROY);
            }
            return !runtime.access().canAccess(protection, ENTITY_SOURCE, Permission.DESTROY);
        });
    }

    @SubscribeEvent
    public void onPistonPre(PistonEvent.Pre event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return;
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        List<BlockPos> pushed = List.copyOf(resolver.getToPush());
        List<BlockPos> destroyed = List.copyOf(resolver.getToDestroy());
        Set<BlockPos> movingPositions = new HashSet<>(pushed);
        BlockProtection piston = runtime.protections().find(level, event.getPos());
        if (piston != null && !ProtectionAutomation.mayActivateRedstone(runtime, piston)) {
            event.setCanceled(true);
            return;
        }
        if (pushed.stream().noneMatch(pos -> runtime.protections().find(level, pos) != null)
                && destroyed.stream().noneMatch(pos -> runtime.protections().find(level, pos) != null)) return;
        if (piston == null) {
            event.setCanceled(true);
            return;
        }
        UUID owner = piston.getOwner();
        Direction movement = event.getPistonMoveType().isExtend
                ? event.getDirection() : event.getDirection().getOpposite();
        Map<UUID, Moved> moved = new HashMap<>();
        List<BlockProtection> broken = new ArrayList<>();
        for (BlockPos pos : pushed) {
            BlockProtection protection = runtime.protections().find(level, pos);
            if (protection == null) continue;
            if (!runtime.access().canAccess(protection, owner,
                    event.getPistonMoveType().isExtend ? Permission.PUSH : Permission.PULL)) {
                event.setCanceled(true);
                return;
            }
            for (BlockPos linked : ProtectionTargets.positions(level, pos)) {
                if (!movingPositions.contains(linked)) {
                    event.setCanceled(true);
                    return;
                }
            }
            BlockPos destination = pos.relative(movement);
            BlockProtection existing = runtime.protections().find(level, destination);
            if (existing != null && !movingPositions.contains(destination)
                    && !existing.getId().equals(protection.getId())) {
                event.setCanceled(true);
                return;
            }
            if (protection.getX() == pos.getX() && protection.getY() == pos.getY()
                    && protection.getZ() == pos.getZ()) {
                moved.put(protection.getId(), new Moved(protection, destination));
            }
        }
        for (BlockPos pos : destroyed) {
            BlockProtection protection = runtime.protections().find(level, pos);
            if (protection == null) continue;
            if (!runtime.access().canAccess(protection, owner, Permission.DESTROY)) {
                event.setCanceled(true);
                return;
            }
            if (!moved.containsKey(protection.getId())) broken.add(protection);
        }
        pistonPlans.put(new PistonKey(level, event.getPos()),
                new PistonPlan(List.copyOf(moved.values()), List.copyOf(broken)));
    }

    @SubscribeEvent
    public void onPistonPost(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PistonPlan plan = pistonPlans.remove(new PistonKey(level, event.getPos()));
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (plan == null || runtime == null) return;
        for (BlockProtection protection : plan.broken) runtime.protections().remove(protection);
        for (Moved move : plan.moved) {
            move.protection.setX(move.destination.getX());
            move.protection.setY(move.destination.getY());
            move.protection.setZ(move.destination.getZ());
            runtime.protections().save(move.protection);
        }
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        // Pre and Post are synchronous; a remaining plan belongs to a cancelled operation.
        pistonPlans.clear();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        pistonPlans.clear();
    }

    private record PistonKey(ServerLevel level, BlockPos pos) { }
    private record Moved(BlockProtection protection, BlockPos destination) { }
    private record PistonPlan(List<Moved> moved, List<BlockProtection> broken) { }
}
