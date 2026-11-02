package org.popcraft.bolt.neoforge.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded admin cleanup work; world state is read only on the server thread. */
final class CleanupJob {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static Job active;

    private CleanupJob() {}

    static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(CleanupJob::tick);
            NeoForge.EVENT_BUS.addListener(CleanupJob::stop);
        }
    }

    private static void stop(ServerStoppingEvent event) {
        if (active != null && active.runtime.server() == event.getServer()) {
            active = null;
            BulkOperations.end("cleanup");
        }
    }

    static int start(BoltRuntime runtime, CommandSourceStack source) {
        if (!BulkOperations.begin(source, "cleanup")) return 0;
        List<BlockProtection> protections;
        try { protections = List.copyOf(runtime.protections().blocks()); }
        catch (RuntimeException failure) {
            BulkOperations.end("cleanup");
            source.sendFailure(Component.literal("Bolt cleanup を開始できません: " + failure.getMessage()));
            return 0;
        }
        active = new Job(runtime, source, protections.iterator());
        source.sendSuccess(() -> Component.literal("[Bolt] " + protections.size() + " 件の保護を確認します。"), false);
        return 1;
    }

    private static void tick(ServerTickEvent.Post event) {
        Job job = active;
        if (job == null || job.runtime.server() != event.getServer()) return;
        try {
            for (int i = 0; i < 4 && job.remaining.hasNext(); i++) {
                BlockProtection protection = job.remaining.next();
                ServerLevel level = null;
                for (ServerLevel candidate : job.runtime.server().getAllLevels()) {
                    if (candidate.dimension().identifier().toString().equals(protection.getWorld())) { level = candidate; break; }
                }
                if (level == null) continue;
                BlockPos pos = new BlockPos(protection.getX(), protection.getY(), protection.getZ());
                String actual = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
                if (!actual.equals(protection.getBlock())) {
                    job.runtime.protections().remove(protection);
                    TransferState.clear(job.runtime, protection.getId());
                    job.removed++;
                }
            }
            if (!job.remaining.hasNext()) {
                active = null;
                BulkOperations.end("cleanup");
                int removed = job.removed;
                job.source.sendSuccess(() -> Component.literal("[Bolt] 存在しないブロックの保護 " + removed + " 件を削除しました。"), false);
            }
        } catch (RuntimeException failure) {
            active = null;
            BulkOperations.end("cleanup");
            job.source.sendFailure(Component.literal("Bolt cleanup に失敗しました: " + failure.getMessage()));
        }
    }

    private static final class Job {
        final BoltRuntime runtime;
        final CommandSourceStack source;
        final Iterator<BlockProtection> remaining;
        int removed;
        Job(BoltRuntime runtime, CommandSourceStack source, Iterator<BlockProtection> remaining) {
            this.runtime = runtime; this.source = source; this.remaining = remaining;
        }
    }
}
