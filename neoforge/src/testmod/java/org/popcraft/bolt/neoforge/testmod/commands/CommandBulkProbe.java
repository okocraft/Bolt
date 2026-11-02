package org.popcraft.bolt.neoforge.testmod.commands;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.popcraft.bolt.data.SQLStore;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.BlockLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Exercises packaged admin export/import/cleanup through Brigadier and persisted state. */
public final class CommandBulkProbe {
    private static final UUID OWNER = UUID.fromString("01b28218-5b4d-4c54-8c57-a16371de6840");
    private static final BlockPos RESTORED = new BlockPos(4400, 250, 4400);
    private static final BlockPos STALE = new BlockPos(4404, 250, 4400);
    private static Probe active;

    private CommandBulkProbe() {}

    public static void start(MinecraftServer server, BoltRuntime runtime) {
        if (active != null) throw new AssertionError("Bulk command probe already active");
        ServerLevel level = server.overworld();
        Path export = runtime.dataDirectory().resolve("export.db");
        if (Files.exists(export)) throw new AssertionError("Isolated export.db already exists");
        if (!level.getBlockState(RESTORED).isAir() || !level.getBlockState(STALE).isAir()
                || runtime.protections().find(level, RESTORED) != null || runtime.protections().find(level, STALE) != null)
            throw new AssertionError("Bulk probe positions are occupied");
        BlockState firstOriginal = level.getBlockState(RESTORED);
        BlockState secondOriginal = level.getBlockState(STALE);
        Set<UUID> otherProtections = runtime.protections().blocks().stream().map(BlockProtection::getId).collect(Collectors.toSet());
        level.setBlockAndUpdate(RESTORED, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(STALE, Blocks.CHEST.defaultBlockState());
        BlockProtection first = runtime.protections().create(level, RESTORED, OWNER, "private");
        BlockProtection second = runtime.protections().create(level, STALE, OWNER, "private");
        runtime.protections().save(first);
        runtime.protections().save(second);
        Capture output = new Capture();
        CommandSourceStack console = server.createCommandSourceStack().withSource(output);
        active = new Probe(server, runtime, level, export, firstOriginal, secondOriginal,
                first.getId(), second.getId(), otherProtections, output, console);
        server.getCommands().performPrefixedCommand(console, "bolt admin storage export");
        active.stage = Stage.EXPORTING;
    }

    public static boolean tick(MinecraftServer server, BoltRuntime runtime) {
        Probe probe = active;
        if (probe == null) throw new AssertionError("Bulk command probe was not started");
        if (probe.server != server || probe.runtime != runtime) throw new AssertionError("Bulk command probe server changed");
        if (probe.output.failed()) throw new AssertionError("Bulk command failed: " + probe.output.messages);
        switch (probe.stage) {
            case EXPORTING -> {
                if (!probe.output.completed("export")) return false;
                verifyExport(probe);
                BlockProtection old = probe.runtime.protections().find(probe.level, RESTORED);
                if (old == null || !old.getId().equals(probe.firstId)) throw new AssertionError("Export altered live protection");
                probe.runtime.protections().remove(old);
                if (probe.runtime.protections().find(probe.level, RESTORED) != null) throw new AssertionError("Fixture removal failed");
                probe.output.clear();
                server.getCommands().performPrefixedCommand(probe.console, "bolt admin storage import");
                probe.stage = Stage.IMPORTING;
            }
            case IMPORTING -> {
                if (!probe.output.completed("import")) return false;
                BlockProtection restored = probe.runtime.protections().find(probe.level, RESTORED);
                BlockProtection stale = probe.runtime.protections().find(probe.level, STALE);
                if (restored == null || !restored.getId().equals(probe.firstId) || !OWNER.equals(restored.getOwner())
                        || stale == null || !stale.getId().equals(probe.secondId))
                    throw new AssertionError("Import did not restore exported protection identities");
                probe.level.setBlockAndUpdate(STALE, Blocks.STONE.defaultBlockState());
                if (probe.runtime.protections().find(probe.level, STALE) == null) throw new AssertionError("Stale fixture disappeared before cleanup");
                probe.output.clear();
                server.getCommands().performPrefixedCommand(probe.console, "bolt admin cleanup");
                probe.stage = Stage.CLEANING;
            }
            case CLEANING -> {
                if (!probe.output.cleaned()) return false;
                if (probe.runtime.protections().find(probe.level, STALE) != null)
                    throw new AssertionError("Cleanup retained a mismatched block lock");
                BlockProtection retained = probe.runtime.protections().find(probe.level, RESTORED);
                if (retained == null || !retained.getId().equals(probe.firstId))
                    throw new AssertionError("Cleanup removed a valid block lock");
                Set<UUID> after = probe.runtime.protections().blocks().stream().map(BlockProtection::getId).collect(Collectors.toSet());
                if (!after.containsAll(probe.otherProtections)) throw new AssertionError("Bulk commands changed unrelated locks");
                probe.runtime.protections().remove(retained);
                probe.level.setBlockAndUpdate(RESTORED, probe.firstOriginal);
                probe.level.setBlockAndUpdate(STALE, probe.secondOriginal);
                probe.runtime.bolt().getStore().flush().join();
                try { Files.delete(probe.export); }
                catch (java.io.IOException failure) { throw new AssertionError("Could not remove fixture export.db", failure); }
                probe.stage = Stage.DONE;
                return true;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private static void verifyExport(Probe probe) {
        if (!Files.isRegularFile(probe.export)) throw new AssertionError("Export did not create SQLite file");
        SQLStore exported = new SQLStore(new SQLStore.Configuration("sqlite", probe.export.toAbsolutePath().toString(),
                "", "", "", "", "", Map.of()));
        try {
            BlockProtection first = exported.loadBlockProtection(location(probe.level, RESTORED)).join();
            BlockProtection second = exported.loadBlockProtection(location(probe.level, STALE)).join();
            if (first == null || second == null || !probe.firstId.equals(first.getId())
                    || !probe.secondId.equals(second.getId()) || !OWNER.equals(first.getOwner()))
                throw new AssertionError("Export database did not preserve fixture locks");
        } finally { exported.close(); }
    }

    private static BlockLocation location(ServerLevel level, BlockPos pos) {
        return new BlockLocation(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private enum Stage { EXPORTING, IMPORTING, CLEANING, DONE }

    private static final class Probe {
        final MinecraftServer server;
        final BoltRuntime runtime;
        final ServerLevel level;
        final Path export;
        final BlockState firstOriginal;
        final BlockState secondOriginal;
        final UUID firstId;
        final UUID secondId;
        final Set<UUID> otherProtections;
        final Capture output;
        final CommandSourceStack console;
        Stage stage;
        Probe(MinecraftServer server, BoltRuntime runtime, ServerLevel level, Path export,
              BlockState firstOriginal, BlockState secondOriginal, UUID firstId, UUID secondId,
              Set<UUID> otherProtections, Capture output, CommandSourceStack console) {
            this.server = server; this.runtime = runtime; this.level = level; this.export = export;
            this.firstOriginal = firstOriginal; this.secondOriginal = secondOriginal;
            this.firstId = firstId; this.secondId = secondId; this.otherProtections = otherProtections;
            this.output = output; this.console = console;
        }
    }

    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        void clear() { messages.clear(); }
        boolean completed(String action) { return messages.stream().anyMatch(message -> message.contains(action + " を完了しました")); }
        boolean cleaned() { return messages.stream().anyMatch(message -> message.contains("件を削除しました")); }
        boolean failed() { return messages.stream().anyMatch(message -> message.contains("失敗しました") || message.contains("が実行中です") || message.contains("権限がありません")); }
    }
}
