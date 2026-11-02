package org.popcraft.bolt.neoforge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.data.*;
import org.popcraft.bolt.neoforge.protection.ProtectionService;
import org.popcraft.bolt.util.Mode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class BoltRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final Path directory;
    private final Bolt bolt;
    private BoltSettings settings;
    private final NeoForgePermissions permissions = new NeoForgePermissions();
    private final AccessService access;
    private final PlayerDirectory players;
    private final PlayerPreferences preferences;
    private SQLStore persistent;
    private ProtectionService protections;
    public BoltRuntime(MinecraftServer server,Path directory) throws IOException {
        this.server=server; this.directory=directory;
        settings=new BoltSettings(directory);
        persistent=new SQLStore(settings.database());
        bolt=new Bolt(new CachedStore(persistent));
        settings.register(bolt.getAccessRegistry()); registerSources();
        access=new AccessService(bolt,server,permissions);
        players=new PlayerDirectory(server); preferences=new PlayerPreferences(directory);
        protections=new ProtectionService(bolt,settings,access,permissions,persistent);
    }
    private void registerSources() {
        var registry=bolt.getSourceTypeRegistry(); registry.unregisterAll();
        var unique=Set.of("block","redstone","door","entity");
        for (var entry:settings.section("sources").entrySet()) {
            var options=(Map<?,?>)entry.getValue();
            registry.registerSourceType(entry.getKey(),Boolean.TRUE.equals(options.get("require-permission")),unique.contains(entry.getKey())||Boolean.TRUE.equals(options.get("unique")));
        }
        for (String type:List.of("block","redstone","door","entity","region"))
            if (registry.getSourceByName(type).isEmpty()) registry.registerSourceType(type,false,unique.contains(type));
    }
    public Bolt bolt() { return bolt; }
    public ProtectionService protections() { return protections; }
    public AccessService access() { return access; }
    public NeoForgePermissions permissions() { return permissions; }
    public BoltSettings settings() { return settings; }
    public PlayerDirectory players() { return players; }
    public PlayerPreferences preferences() { return preferences; }
    public MinecraftServer server() { return server; }
    public Path dataDirectory() { return directory; }
    public void reload() throws IOException {
        var next=new BoltSettings(directory);
        if (!next.database().equals(settings.database())) {
            persistent.flush().join();
            var replacement=new SQLStore(next.database());
            CachedStore cache;
            try { cache=new CachedStore(replacement); }
            catch (RuntimeException failed) { replacement.close(); throw failed; }
            persistent.close(); persistent=replacement; bolt.setStore(cache);
        }
        settings=next; settings.register(bolt.getAccessRegistry()); registerSources();
        protections=new ProtectionService(bolt,settings,access,permissions,persistent);
    }
    public void loadPlayer(ServerPlayer player) {
        var state=bolt.getBoltPlayer(player.getUUID());
        for (Mode mode:preferences.load(player.getUUID())) if (!state.hasMode(mode)) state.toggleMode(mode);
    }
    public void savePlayer(ServerPlayer player) {
        var state=bolt.getBoltPlayer(player.getUUID());
        var selected=EnumSet.noneOf(Mode.class);
        for (Mode mode:Mode.values()) if (state.hasMode(mode)) selected.add(mode);
        preferences.save(player.getUUID(),selected);
    }
    @Override public void close() {
        for (var player:server.getPlayerList().getPlayers()) savePlayer(player);
        persistent.close();
    }
}
