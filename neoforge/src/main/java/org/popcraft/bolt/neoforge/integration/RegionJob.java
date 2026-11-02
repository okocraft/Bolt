package org.popcraft.bolt.neoforge.integration;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;

import java.util.Iterator;
import java.util.UUID;

/** A bounded cursor over a WorldGuard region or Bolt's cached protections. */
final class RegionJob {
    private final BoltRuntime runtime;
    private final ServerLevel level;
    private final UUID actor;
    private final String name;
    private final ProtectedRegion region;
    private final RegionCommands.RegionFilters filters;
    private final boolean purge;
    private final Iterator<BlockProtection> protections;
    private final long minX;
    private final long maxX;
    private final long minY;
    private final long maxY;
    private final long minZ;
    private final long maxZ;
    private long x;
    private long y;
    private long z;
    private int changed;
    private boolean finished;

    RegionJob(BoltRuntime runtime, ServerLevel level, UUID actor, String name,
              ProtectedRegion region, RegionCommands.RegionFilters filters, boolean purge) {
        this.runtime = runtime;
        this.level = level;
        this.actor = actor;
        this.name = name;
        this.region = region;
        this.filters = filters;
        this.purge = purge;
        protections = purge ? runtime.protections().blocks().iterator() : null;
        var min = region.getMinimumPoint();
        var max = region.getMaximumPoint();
        minX = min.x();
        maxX = max.x();
        minY = Math.max(min.y(), level.getMinY());
        maxY = Math.min(max.y(), level.getMaxY() - 1);
        minZ = min.z();
        maxZ = max.z();
        x = minX;
        y = minY;
        z = minZ;
        finished = !purge && minY > maxY;
    }

    BoltRuntime runtime() { return runtime; }
    UUID actor() { return actor; }

    boolean advance(int budget) {
        boolean loadedChunk = false;
        long deadline = System.nanoTime() + 4_000_000L;
        boolean processed = false;
        while (budget-- > 0 && !finished) {
            if (processed && System.nanoTime() >= deadline) break;
            if (purge) {
                if (protections.hasNext()) purge(protections.next());
                else finished = true;
            } else {
                BlockPos pos = new BlockPos((int) x, (int) y, (int) z);
                if (region.contains(pos.getX(), pos.getY(), pos.getZ())
                        && level.getWorldBorder().isWithinBounds(pos)
                        && !level.hasChunkAt(pos)) {
                    if (loadedChunk) break;
                    loadedChunk = true;
                }
                protect(pos);
                nextPosition();
            }
            processed = true;
        }
        return finished;
    }

    private void protect(BlockPos pos) {
        if (!region.contains(pos.getX(), pos.getY(), pos.getZ())
                || !level.getWorldBorder().isWithinBounds(pos)
                || !runtime.protections().isProtectable(level, pos)
                || runtime.protections().find(level, pos) != null) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        if (filters.blockId() != null && !filters.blockId().equals(blockId)) return;
        UUID owner = filters.owner() == null ? actor : filters.owner();
        String type = filters.type() == null ? runtime.settings().defaultProtectionType() : filters.type();
        runtime.protections().save(runtime.protections().create(level, pos, owner, type));
        changed++;
    }

    private void purge(BlockProtection protection) {
        if (!protection.getWorld().equals(level.dimension().identifier().toString())
                || !region.contains(protection.getX(), protection.getY(), protection.getZ())
                || filters.blockId() != null && !filters.blockId().equals(protection.getBlock())
                || filters.owner() != null && !filters.owner().equals(protection.getOwner())
                || filters.type() != null && !filters.type().equalsIgnoreCase(protection.getType())) return;
        runtime.protections().remove(protection);
        changed++;
    }

    private void nextPosition() {
        if (++z <= maxZ) return;
        z = minZ;
        if (++y <= maxY) return;
        y = minY;
        if (++x <= maxX) return;
        finished = true;
    }

    String summary() {
        return (purge ? "Purged " : "Protected ") + changed + " blocks in region " + name + ".";
    }
}
