package org.popcraft.bolt.neoforge.commands;

import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceType;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Mode;
import org.popcraft.bolt.util.Permission;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ProtectionCommands {
    private static final Map<UUID, Long> UNLOCK_CONFIRMATIONS = new HashMap<>();

    private ProtectionCommands() {}

    static int run(BoltRuntime runtime, ServerPlayer player, String route, List<String> args) {
        String command = route.startsWith("c") ? route.substring(1) : route;
        if (route.equals("caccess")) {
            if (args.isEmpty() || args.getFirst().equals("-")) { CommandSupport.usage(player, "/caccess <player/-player>"); return 0; }
            String name = args.getFirst();
            return modify(runtime, player, List.of(name.startsWith("-") ? "remove" : "add", "normal", SourceTypes.PLAYER,
                    name.startsWith("-") ? name.substring(1) : name));
        }
        if (route.equals("citemmove")) {
            Boolean allow = allow(args);
            if (allow == null) { CommandSupport.usage(player, "/citemmove <allow/deny>"); return 0; }
            return modify(runtime, player, List.of(allow ? "add" : "remove", "normal", SourceTypes.BLOCK, Permission.WITHDRAW, Permission.DEPOSIT));
        }
        if (route.equals("credstone")) {
            Boolean allow = allow(args);
            if (allow == null) { CommandSupport.usage(player, "/credstone <allow/deny>"); return 0; }
            boolean add = runtime.settings().redstoneDenyByDefault() == allow;
            return modify(runtime, player, List.of(add ? "add" : "remove", "normal", SourceTypes.REDSTONE, Permission.REDSTONE));
        }
        if (route.equals("cautoclose")) {
            Boolean allow = allow(args);
            if (allow == null) { CommandSupport.usage(player, "/cautoclose <on/off>"); return 0; }
            return modify(runtime, player, List.of(allow ? "add" : "remove", "normal", SourceTypes.DOOR, Permission.AUTO_CLOSE));
        }
        if (route.equals("cpersist") || route.equals("cnolock") || route.equals("cnospam")) {
            return mode(runtime, player, List.of(command), args.isEmpty() ? null : args.getFirst());
        }
        return switch (command) {
            case "lock" -> lock(runtime, player, args);
            case "unlock" -> unlock(runtime, player);
            case "info" -> pending(runtime, player, Action.Type.INFO, "bolt.command.info", null);
            case "edit" -> edit(runtime, player, args);
            case "modify" -> modify(runtime, player, args);
            case "mode" -> mode(runtime, player, args, null);
            case "password" -> password(runtime, player, args);
            case "transfer" -> transfer(runtime, player, args);
            default -> 0;
        };
    }

    private static Boolean allow(List<String> args) {
        if (args.isEmpty()) return null;
        return switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "allow", "on", "add", "true" -> true;
            case "deny", "off", "remove", "false" -> false;
            default -> null;
        };
    }

    private static int pending(BoltRuntime runtime, ServerPlayer player, Action.Type type, String permission, String data) {
        if (!CommandSupport.permit(runtime, player, permission)) return 0;
        runtime.bolt().getBoltPlayer(player.getUUID()).setAction(data == null ? new Action(type, permission) : new Action(type, permission, data));
        CommandSupport.promptClick(runtime, player);
        return 1;
    }

    private static int lock(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        String type = args.isEmpty() ? runtime.settings().defaultProtectionType() : args.getFirst().toLowerCase(Locale.ROOT);
        Access access = runtime.bolt().getAccessRegistry().getProtectionByType(type).orElse(null);
        if (access == null) { CommandSupport.say(player, "保護タイプがありません: " + type); return 0; }
        if (access.restricted() && !CommandSupport.permit(runtime, player, "bolt.type.protection." + type)) return 0;
        if (!CommandSupport.permit(runtime, player, "bolt.command.lock")) return 0;
        boolean force = args.size() > 1 && args.get(1).equalsIgnoreCase("force");
        if (force && !CommandSupport.permit(runtime, player, "bolt.admin")) return 0;
        runtime.bolt().getBoltPlayer(player.getUUID()).setAction(new Action(Action.Type.LOCK, "bolt.command.lock", type, force));
        CommandSupport.promptClick(runtime, player);
        return 1;
    }

    private static int unlock(BoltRuntime runtime, ServerPlayer player) {
        if (!CommandSupport.permit(runtime, player, "bolt.command.unlock")) return 0;
        if (!CommandSupport.has(runtime, player, "bolt.mod")) {
            long now = System.currentTimeMillis();
            Long prior = UNLOCK_CONFIRMATIONS.get(player.getUUID());
            if (prior == null || now - prior >= Duration.ofHours(1).toMillis()) {
                UNLOCK_CONFIRMATIONS.put(player.getUUID(), now);
                if (player.clientInformation().language().toLowerCase(Locale.ROOT).startsWith("ja"))
                    CommandSupport.say(player, "警告: 保護を解除すると他プレイヤーに盗まれる可能性があります。共有には /caccess <プレイヤー名>、ホッパーには /citemmove allow を使えます。続ける場合は /unlock をもう一度実行してください。");
                else CommandSupport.say(player, "Warning: Removing a lock can allow theft. Use /caccess <player> to share access or /citemmove allow for hoppers. Run /unlock again to continue.");
                return 1;
            }
        }
        return pending(runtime, player, Action.Type.UNLOCK, "bolt.command.unlock", null);
    }

    private static int edit(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (args.size() < 2 || !(args.getFirst().equalsIgnoreCase("add") || args.getFirst().equalsIgnoreCase("remove"))) {
            CommandSupport.usage(player, "/bolt edit <add|remove> <player>"); return 0;
        }
        if (!CommandSupport.permit(runtime, player, "bolt.command.edit")) return 0;
        UUID target = CommandSupport.player(runtime, player, args.get(1)).orElse(null);
        if (target == null) return 0;
        BoltPlayer state = runtime.bolt().getBoltPlayer(player.getUUID());
        state.getModifications().put(Source.player(target), runtime.settings().defaultAccessType());
        state.setAction(new Action(Action.Type.EDIT, "bolt.command.edit", Boolean.toString(args.getFirst().equalsIgnoreCase("add"))));
        CommandSupport.promptClick(runtime, player);
        return 1;
    }

    static int modify(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (args.size() < 3 || !(args.getFirst().equalsIgnoreCase("add") || args.getFirst().equalsIgnoreCase("remove"))) {
            CommandSupport.usage(player, "/bolt modify <add|remove> <access> <source> [identifier...]"); return 0;
        }
        if (!CommandSupport.permit(runtime, player, "bolt.command.modify")) return 0;
        String accessName = args.get(1).toLowerCase(Locale.ROOT);
        String sourceName = args.get(2).toLowerCase(Locale.ROOT);
        Access access = runtime.bolt().getAccessRegistry().getAccessByType(accessName).orElse(null);
        SourceType source = runtime.bolt().getSourceTypeRegistry().getSourceByName(sourceName).orElse(null);
        if (access == null || source == null) { CommandSupport.say(player, "アクセス種別または共有先種別がありません。"); return 0; }
        if (access.restricted() && !CommandSupport.permit(runtime, player, "bolt.type.access." + accessName)) return 0;
        if (source.restricted() && !CommandSupport.permit(runtime, player, "bolt.type.source." + sourceName)) return 0;
        List<String> ids = source.unique() ? List.of(sourceName) : args.subList(3, args.size());
        if (ids.isEmpty()) { CommandSupport.usage(player, "/bolt modify <add|remove> <access> <source> <identifier...>"); return 0; }
        BoltPlayer state = runtime.bolt().getBoltPlayer(player.getUUID());
        for (String id : ids) {
            String canonical = sourceName.equals(SourceTypes.PLAYER) ? CommandSupport.player(runtime, player, id).map(UUID::toString).orElse(null)
                    : sourceName.equals(SourceTypes.PASSWORD) ? Source.password(id).getIdentifier() : id;
            if (canonical != null) state.getModifications().put(Source.of(sourceName, canonical), accessName);
        }
        if (state.getModifications().isEmpty()) return 0;
        state.setAction(new Action(Action.Type.EDIT, "bolt.command.modify", Boolean.toString(args.getFirst().equalsIgnoreCase("add"))));
        CommandSupport.promptClick(runtime, player);
        return 1;
    }

    private static int mode(BoltRuntime runtime, ServerPlayer player, List<String> args, String desired) {
        if (!CommandSupport.permit(runtime, player, "bolt.command.mode")) return 0;
        if (args.isEmpty()) { CommandSupport.usage(player, "/bolt mode <persist|nolock|nospam>"); return 0; }
        Mode mode;
        try { mode = Mode.valueOf(args.getFirst().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { CommandSupport.say(player, "モードがありません。"); return 0; }
        BoltPlayer state = runtime.bolt().getBoltPlayer(player.getUUID());
        Boolean on = desired == null ? null : allow(List.of(desired));
        if (on == null || on != state.hasMode(mode)) state.toggleMode(mode);
        EnumSet<Mode> active = EnumSet.noneOf(Mode.class);
        for (Mode value : Mode.values()) if (state.hasMode(value)) active.add(value);
        runtime.preferences().save(player.getUUID(), active);
        CommandSupport.say(player, mode.name().toLowerCase(Locale.ROOT) + (state.hasMode(mode) ? " を有効にしました。" : " を無効にしました。"));
        return 1;
    }

    private static int password(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (!CommandSupport.permit(runtime, player, "bolt.command.password")) return 0;
        if (args.isEmpty()) { CommandSupport.usage(player, "/bolt password <password>"); return 0; }
        runtime.bolt().getBoltPlayer(player.getUUID()).addPassword(args.getFirst());
        CommandSupport.say(player, "パスワードを追加しました。");
        return 1;
    }

    private static int transfer(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (args.isEmpty()) { CommandSupport.usage(player, "/bolt transfer <player>"); return 0; }
        return CommandSupport.player(runtime, player, args.getFirst())
                .map(uuid -> pending(runtime, player, Action.Type.TRANSFER, "bolt.command.transfer", uuid.toString())).orElse(0);
    }

    static void forget(UUID player) { UNLOCK_CONFIRMATIONS.remove(player); }
}
