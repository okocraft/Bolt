package org.popcraft.bolt.neoforge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.access.AccessPolicy;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypes;
import java.util.UUID;

public final class AccessService {
    private final Bolt bolt;
    private final AccessPolicy policy;
    private final MinecraftServer server;
    private final NeoForgePermissions permissions;
    public AccessService(Bolt bolt, MinecraftServer server, NeoForgePermissions permissions) {
        this.bolt = bolt; this.policy = new AccessPolicy(bolt); this.server = server; this.permissions = permissions;
    }
    public boolean canAccess(Protection protection, ServerPlayer player, String... nodes) { return canAccess(protection, player.getUUID(), nodes); }
    public boolean canAccess(Protection protection, UUID player, String... nodes) { return canAccess(protection, source -> {
        if (bolt.getBoltPlayer(player).sources().contains(source)) return true;
        var online = server.getPlayerList().getPlayer(player);
        if (source.getType().equals(SourceTypes.PERMISSION) && online != null) return permissions.has(online, source.getIdentifier(),
                (source.getIdentifier().equals("bolt.admin") || source.getIdentifier().equals("bolt.mod")) && permissions.operator(online));
        if (source.getType().equals(SourceTypes.GROUP)) {
            var group = bolt.getStore().loadGroup(source.getIdentifier()).join();
            if (group != null && (group.getOwner().equals(player) || group.getMembers().contains(player))) return true;
        }
        for (var resolver : bolt.getRegisteredPlayerResolvers()) if (resolver.resolve(source, player)) return true;
        return false;
    }, nodes); }
    public boolean canAccess(Protection protection, SourceResolver source, String... nodes) { return policy.allows(protection, source, nodes); }
}
