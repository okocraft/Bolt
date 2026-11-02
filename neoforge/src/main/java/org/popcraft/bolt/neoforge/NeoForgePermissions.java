package org.popcraft.bolt.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.neoforged.fml.ModList;

public final class NeoForgePermissions {
    public boolean has(ServerPlayer player, String node, boolean fallback) {
        if (ModList.get().isLoaded("luckperms")) {
            Boolean value = LuckPermsAccess.permission(player, node);
            if (value != null) return value;
        }
        return fallback;
    }
    public boolean operator(ServerPlayer player) {
        return player.level().getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
    }
    private static final class LuckPermsAccess {
        static Boolean permission(ServerPlayer player, String node) {
            var api = net.luckperms.api.LuckPermsProvider.get();
            var user = api.getUserManager().getUser(player.getUUID());
            if (user == null) return null;
            var options = api.getContextManager().getQueryOptions(user).orElse(api.getContextManager().getStaticQueryOptions());
            var result = user.getCachedData().getPermissionData(options).checkPermission(node);
            return result == net.luckperms.api.util.Tristate.UNDEFINED ? null : result.asBoolean();
        }
    }
}
