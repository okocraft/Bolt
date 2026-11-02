package org.popcraft.bolt.neoforge;

import net.minecraft.server.MinecraftServer;
import java.util.Optional;
import java.util.UUID;

public final class PlayerDirectory {
    private final MinecraftServer server;
    public PlayerDirectory(MinecraftServer server) { this.server = server; }
    public Optional<UUID> resolve(String name) {
        try { return Optional.of(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getUUID());
        return server.services().nameToIdCache().get(name).map(profile -> profile.id());
    }
    public String name(UUID id) {
        var player = server.getPlayerList().getPlayer(id);
        return player != null ? player.getGameProfile().name() : id.toString();
    }
}
