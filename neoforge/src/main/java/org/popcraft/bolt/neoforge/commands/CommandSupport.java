package org.popcraft.bolt.neoforge.commands;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.util.Mode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class CommandSupport {
    private CommandSupport() {}

    static void say(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Bolt] " + message));
    }

    static void promptClick(BoltRuntime runtime, ServerPlayer player) {
        if (!runtime.bolt().getBoltPlayer(player.getUUID()).hasMode(Mode.NOSPAM))
            say(player, "対象をクリックしてください。");
    }

    static boolean permit(BoltRuntime runtime, ServerPlayer player, String node) {
        if (has(runtime, player, node)) return true;
        say(player, "権限がありません: " + node);
        return false;
    }

    static boolean has(BoltRuntime runtime, ServerPlayer player, String node) {
        boolean fallback = node.equals("bolt.mod") || node.equals("bolt.admin")
                || node.equals("bolt.command.admin") || node.startsWith("bolt.command.admin.")
                || node.equals("bolt.command.info.full") ? runtime.permissions().operator(player)
                : node.equals("bolt.command") || node.startsWith("bolt.command.")
                && !node.startsWith("bolt.command.admin.");
        return runtime.permissions().has(player, node, fallback);
    }

    static Optional<UUID> player(BoltRuntime runtime, ServerPlayer sender, String name) {
        Optional<UUID> resolved = runtime.players().resolve(name);
        if (resolved.isEmpty()) say(sender, "プレイヤーが見つかりません: " + name);
        return resolved;
    }

    static List<String> words(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.asList(raw.trim().split("\\s+"));
    }

    static String word(List<String> words, int index) {
        return index < words.size() ? words.get(index) : "";
    }

    static void usage(ServerPlayer player, String usage) {
        say(player, "使い方: " + usage);
    }
}
