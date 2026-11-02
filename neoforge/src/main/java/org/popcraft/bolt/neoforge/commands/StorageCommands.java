package org.popcraft.bolt.neoforge.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.data.SQLStore;
import org.popcraft.bolt.data.Store;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.PendingTransfer;
import org.popcraft.bolt.util.Group;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Export reads a stable cache snapshot; import applies records on bounded server ticks. */
final class StorageCommands {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static ImportJob importJob;
    private static BoltRuntime operatingRuntime;
    private StorageCommands() {}

    static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(StorageCommands::tick);
            NeoForge.EVENT_BUS.addListener(StorageCommands::stop);
        }
    }

    static int run(BoltRuntime runtime, CommandSourceStack source, String method) {
        if (!method.equals("export") && !method.equals("import")) {
            source.sendFailure(Component.literal("使い方: /bolt admin storage <export|import>")); return 0;
        }
        if (!BulkOperations.begin(source, "storage")) return 0;
        operatingRuntime = runtime;
        Path path = runtime.dataDirectory().resolve("export.db").toAbsolutePath();
        if (method.equals("export") && Files.exists(path) || method.equals("import") && !Files.exists(path)) {
            BulkOperations.end("storage");
            operatingRuntime = null;
            source.sendFailure(Component.literal(method.equals("export") ? "export.db が既にあります。退避してから再実行してください。" : "export.db がありません。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Bolt] " + method + " を開始しました。"), false);
        if (method.equals("export")) {
            Snapshot snapshot;
            try { snapshot = snapshot(runtime.bolt().getStore(), true); }
            catch (RuntimeException failure) { complete(runtime, source, "export", failure); return 0; }
            CompletableFuture.runAsync(() -> {
                SQLStore destination = database(path);
                try { write(snapshot, destination); destination.flush().join(); }
                finally { destination.close(); }
            }).whenComplete((unused, failure) -> runtime.server().execute(() -> complete(runtime, source, "export", failure)));
        } else {
            CompletableFuture.supplyAsync(() -> {
                SQLStore sourceStore = database(path);
                try { return snapshot(sourceStore, false); }
                finally { sourceStore.close(); }
            }).whenComplete((records, failure) -> runtime.server().execute(() -> {
                if (operatingRuntime != runtime) return;
                if (failure != null) complete(runtime, source, "import", failure);
                else importJob = new ImportJob(runtime, source, records);
            }));
        }
        return 1;
    }

    private static SQLStore database(Path path) {
        return new SQLStore(new SQLStore.Configuration("sqlite", path.toString(), "", "", "", "", "", Map.of()));
    }

    private static Snapshot snapshot(Store store, boolean detach) {
        List<BlockProtection> blocks = new ArrayList<>();
        for (BlockProtection p : store.loadBlockProtections().join())
            blocks.add(detach ? new BlockProtection(p.getId(), p.getOwner(), p.getType(), p.getCreated(), p.getAccessed(),
                    new HashMap<>(p.getAccess()), p.getWorld(), p.getX(), p.getY(), p.getZ(), p.getBlock()) : p);
        List<EntityProtection> entities = new ArrayList<>();
        for (EntityProtection p : store.loadEntityProtections().join())
            entities.add(detach ? new EntityProtection(p.getId(), p.getOwner(), p.getType(), p.getCreated(), p.getAccessed(),
                    new HashMap<>(p.getAccess()), p.getEntity()) : p);
        List<Group> groups = new ArrayList<>();
        for (Group group : store.loadGroups().join())
            groups.add(detach ? new Group(group.getName(), group.getOwner(), new ArrayList<>(group.getMembers())) : group);
        List<AccessList> accesses = new ArrayList<>();
        for (AccessList list : store.loadAccessLists().join())
            accesses.add(detach ? new AccessList(list.getOwner(), new HashMap<>(list.getAccess())) : list);
        List<PendingTransfer> transfers = List.copyOf(store.loadPendingTransfers().join());
        return new Snapshot(blocks, entities, groups, accesses, transfers);
    }

    private static void write(Snapshot records, Store store) {
        for (BlockProtection p : records.blocks) store.saveBlockProtection(p);
        for (EntityProtection p : records.entities) store.saveEntityProtection(p);
        for (Group group : records.groups) store.saveGroup(group);
        for (AccessList list : records.accesses) store.saveAccessList(list);
        for (PendingTransfer transfer : records.transfers) store.savePendingTransfer(transfer);
    }

    private static void tick(ServerTickEvent.Post event) {
        ImportJob job = importJob;
        if (job == null || job.runtime.server() != event.getServer()) return;
        try {
            Store store = job.runtime.bolt().getStore();
            for (int i = 0; i < 100 && !job.complete(); i++) job.apply(store);
            if (job.complete()) {
                importJob = null;
                store.flush().whenComplete((unused, failure) -> job.runtime.server().execute(() -> complete(job.runtime, job.source, "import", failure)));
            }
        } catch (RuntimeException failure) {
            importJob = null;
            complete(job.runtime, job.source, "import", failure);
        }
    }

    private static void stop(ServerStoppingEvent event) {
        if (operatingRuntime != null && operatingRuntime.server() == event.getServer()) {
            importJob = null;
            operatingRuntime = null;
            BulkOperations.end("storage");
        }
    }

    private static void complete(BoltRuntime runtime, CommandSourceStack source, String operation, Throwable failure) {
        if (operatingRuntime != runtime) return;
        operatingRuntime = null;
        BulkOperations.end("storage");
        if (failure == null) source.sendSuccess(() -> Component.literal("[Bolt] " + operation + " を完了しました。"), false);
        else source.sendFailure(Component.literal("Bolt " + operation + " に失敗しました: " + failure.getMessage()));
    }

    private record Snapshot(List<BlockProtection> blocks, List<EntityProtection> entities, List<Group> groups,
                            List<AccessList> accesses, List<PendingTransfer> transfers) {}

    private static final class ImportJob {
        final BoltRuntime runtime;
        final CommandSourceStack source;
        final Snapshot records;
        int next;
        ImportJob(BoltRuntime runtime, CommandSourceStack source, Snapshot records) {
            this.runtime = runtime; this.source = source; this.records = records;
        }
        boolean complete() {
            return next >= records.blocks.size() + records.entities.size() + records.groups.size()
                    + records.accesses.size() + records.transfers.size();
        }
        void apply(Store store) {
            int index = next++;
            if (index < records.blocks.size()) { store.saveBlockProtection(records.blocks.get(index)); return; }
            index -= records.blocks.size();
            if (index < records.entities.size()) { store.saveEntityProtection(records.entities.get(index)); return; }
            index -= records.entities.size();
            if (index < records.groups.size()) { store.saveGroup(records.groups.get(index)); return; }
            index -= records.groups.size();
            if (index < records.accesses.size()) { store.saveAccessList(records.accesses.get(index)); return; }
            index -= records.accesses.size();
            store.savePendingTransfer(records.transfers.get(index));
        }
    }
}
