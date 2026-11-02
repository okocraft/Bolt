package org.popcraft.bolt.neoforge.integration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import de.kuro.worldguard.api.WorldGuardNeoForge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Admin region jobs advance in bounded slices on server ticks. */
final class RegionCommands {
    private static final RegionCommands INSTANCE = new RegionCommands();
    private static final int POSITIONS_PER_TICK = 128;
    private final Deque<RegionJob> jobs = new ArrayDeque<>();

    private RegionCommands() { }

    static void registerTicks() {
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        register(dispatcher, "protectregion", false);
        register(dispatcher, "purgeregion", true);
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                 String name, boolean purge) {
        dispatcher.register(Commands.literal(name)
                .requires(source -> source.getEntity() instanceof ServerPlayer player
                        && BoltNeoForge.runtime() != null
                        && BoltNeoForge.runtime().permissions().has(player, "bolt.command.admin",
                                BoltNeoForge.runtime().permissions().operator(player)))
                .then(Commands.argument("region", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            if (!(context.getSource().getEntity() instanceof ServerPlayer player)
                                    || !WorldGuardNeoForge.isReady()) return builder.buildFuture();
                            RegionManager manager = manager(player);
                            return manager == null ? builder.buildFuture()
                                    : SharedSuggestionProvider.suggest(manager.getRegions().keySet(), builder);
                        })
                        .executes(context -> start(context, purge, ""))
                        .then(Commands.argument("filters", StringArgumentType.greedyString())
                                .executes(context -> start(context, purge,
                                        StringArgumentType.getString(context, "filters"))))));
    }

    private static int start(CommandContext<CommandSourceStack> context,
                             boolean purge, String filterText) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null || !WorldGuardNeoForge.isReady()) {
            source.sendFailure(Component.literal("Bolt or WorldGuard is not ready."));
            return 0;
        }
        RegionManager manager = manager(player);
        String name = StringArgumentType.getString(context, "region");
        ProtectedRegion region = manager == null ? null : manager.getRegion(name);
        if (region == null) {
            source.sendFailure(Component.literal("Region does not exist: " + name));
            return 0;
        }
        RegionFilters filters = RegionFilters.parse(filterText, runtime, purge, source);
        if (filters == null) return 0;
        INSTANCE.jobs.addLast(new RegionJob(runtime, player.level(), player.getUUID(),
                name, region, filters, purge));
        source.sendSuccess(() -> Component.literal("Queued "
                + (purge ? "purge" : "protection") + " for region " + name + "."), false);
        return 1;
    }

    private static RegionManager manager(ServerPlayer player) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(WorldGuardNeoForge.world(player.level()));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        RegionJob job = jobs.pollFirst();
        if (job == null) return;
        if (job.advance(POSITIONS_PER_TICK)) {
            ServerPlayer player = job.runtime().server().getPlayerList().getPlayer(job.actor());
            if (player != null) player.sendSystemMessage(Component.literal(job.summary()));
        } else jobs.addLast(job);
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        jobs.clear();
    }

    record RegionFilters(String blockId, UUID owner, String type) {
        static RegionFilters parse(String input, BoltRuntime runtime, boolean purge,
                                   CommandSourceStack source) {
            String blockId = null;
            UUID owner = null;
            String type = null;
            for (String token : input.trim().isEmpty() ? new String[0] : input.trim().split("\\s+")) {
                int separator = token.indexOf(':');
                if (separator < 1 || separator == token.length() - 1) {
                    source.sendFailure(Component.literal("Expected block:, owner:, or type: filter."));
                    return null;
                }
                String key = token.substring(0, separator);
                String value = token.substring(separator + 1);
                switch (key) {
                    case "block" -> {
                        Identifier id = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
                        if (id == null) {
                            source.sendFailure(Component.literal("Invalid block ID: " + value));
                            return null;
                        }
                        blockId = id.toString();
                    }
                    case "owner" -> {
                        var resolved = runtime.players().resolve(value);
                        if (resolved.isEmpty()) {
                            source.sendFailure(Component.literal("Unknown owner: " + value));
                            return null;
                        }
                        owner = resolved.get();
                    }
                    case "type" -> {
                        if (!purge && runtime.bolt().getAccessRegistry().getProtectionByType(value).isEmpty()) {
                            source.sendFailure(Component.literal("Unknown protection type: " + value));
                            return null;
                        }
                        type = value;
                    }
                    default -> {
                        source.sendFailure(Component.literal("Unknown filter: " + key));
                        return null;
                    }
                }
            }
            return new RegionFilters(blockId, owner, type);
        }
    }
}
