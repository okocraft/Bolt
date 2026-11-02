package org.popcraft.bolt.neoforge.commands;

import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.PendingTransfer;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceType;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SocialCommands {
    private SocialCommands() {}

    static int run(BoltRuntime runtime, ServerPlayer player, String route, List<String> args) {
        return switch (route) {
            case "group" -> group(runtime, player, args);
            case "trust" -> trust(runtime, player, player.getUUID(), args, true);
            case "requests" -> requests(runtime, player, args);
            default -> 0;
        };
    }

    static int trust(BoltRuntime runtime, ServerPlayer player, UUID owner, List<String> args, boolean personal) {
        if (personal && !CommandSupport.permit(runtime, player, "bolt.command.trust")) return 0;
        if (args.isEmpty() || args.getFirst().equals("list")) {
            runtime.bolt().getStore().loadAccessList(owner).thenAccept(list -> runtime.server().execute(() ->
                    CommandSupport.say(player, "共有 " + AccessDisplay.access(list == null ? Map.of() : list.getAccess()))));
            return 1;
        }
        if (args.size() < 3 || !(args.getFirst().equals("add") || args.getFirst().equals("remove"))) {
            CommandSupport.usage(player, "/bolt trust <add|remove> <source> <identifier> [access]"); return 0;
        }
        SourceType sourceType = runtime.bolt().getSourceTypeRegistry().getSourceByName(args.get(1)).orElse(null);
        String accessName = args.size() > 3 ? args.get(3) : runtime.settings().defaultAccessType();
        Access access = runtime.bolt().getAccessRegistry().getAccessByType(accessName).orElse(null);
        if (sourceType == null || access == null) { CommandSupport.say(player, "共有先種別またはアクセス種別がありません。"); return 0; }
        if (sourceType.restricted() && !CommandSupport.permit(runtime, player, "bolt.type.source." + sourceType.name())) return 0;
        if (access.restricted() && !CommandSupport.permit(runtime, player, "bolt.type.access." + accessName)) return 0;
        String id = sourceType.unique() ? sourceType.name() : args.get(2);
        if (sourceType.name().equals(SourceTypes.PLAYER)) id = CommandSupport.player(runtime, player, id).map(UUID::toString).orElse(null);
        else if (sourceType.name().equals(SourceTypes.PASSWORD)) id = Source.password(id).getIdentifier();
        if (id == null) return 0;
        String key = Source.of(sourceType.name(), id).toString();
        boolean add = args.getFirst().equals("add");
        runtime.bolt().getStore().loadAccessList(owner).thenAccept(existing -> runtime.server().execute(() -> {
            AccessList list = existing == null ? new AccessList(owner, new HashMap<>()) : existing;
            if (add) list.getAccess().put(key, access.type());
            else list.getAccess().remove(key);
            runtime.bolt().getStore().saveAccessList(list);
            CommandSupport.say(player, "共有設定を変更しました。");
        }));
        return 1;
    }

    private static int group(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (!CommandSupport.permit(runtime, player, "bolt.command.group")) return 0;
        if (args.size() < 2) { CommandSupport.usage(player, "/bolt group <create|delete|add|remove|list> <group> [players...]"); return 0; }
        String action = args.getFirst();
        String name = args.get(1);
        if (!List.of("create", "delete", "add", "remove", "list").contains(action)) { CommandSupport.say(player, "操作がありません。"); return 0; }
        List<UUID> members = new ArrayList<>();
        for (String member : args.subList(2, args.size())) CommandSupport.player(runtime, player, member).ifPresent(members::add);
        runtime.bolt().getStore().loadGroup(name).thenAccept(existing -> runtime.server().execute(() -> {
            switch (action) {
                case "create" -> {
                    if (existing != null) { CommandSupport.say(player, "グループは既にあります。"); return; }
                    runtime.bolt().getStore().saveGroup(new Group(name, player.getUUID(), members));
                    CommandSupport.say(player, "グループを作成しました: " + name);
                }
                case "list" -> CommandSupport.say(player, existing == null ? "グループがありません。" : "グループ " + name + " / 所有者 " + existing.getOwner() + " / メンバー " + existing.getMembers());
                default -> {
                    if (existing == null) { CommandSupport.say(player, "グループがありません。"); return; }
                    if (!existing.getOwner().equals(player.getUUID())) { CommandSupport.say(player, "グループの所有者だけが変更できます。"); return; }
                    if (action.equals("delete")) runtime.bolt().getStore().removeGroup(existing);
                    else if (action.equals("add")) {
                        for (UUID member : members) if (!existing.getMembers().contains(member)) existing.getMembers().add(member);
                        runtime.bolt().getStore().saveGroup(existing);
                    } else {
                        existing.getMembers().removeAll(members);
                        runtime.bolt().getStore().saveGroup(existing);
                    }
                    CommandSupport.say(player, "グループを変更しました: " + name);
                }
            }
        }));
        return 1;
    }

    private static int requests(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (!CommandSupport.permit(runtime, player, "bolt.command.requests")) return 0;
        if (args.isEmpty()) { CommandSupport.usage(player, "/bolt requests <list|accept|deny|cancel> [player]"); return 0; }
        String action = args.getFirst();
        if (!List.of("list", "accept", "deny", "cancel").contains(action)) return 0;
        UUID other = action.equals("list") ? null : args.size() < 2 ? null : CommandSupport.player(runtime, player, args.get(1)).orElse(null);
        if (!action.equals("list") && other == null) return 0;
        runtime.bolt().getStore().loadPendingTransfers().thenAccept(transfers -> runtime.server().execute(() ->
                applyRequests(runtime, player, transfers, action, other)));
        return 1;
    }

    private static void applyRequests(BoltRuntime runtime, ServerPlayer player, Collection<PendingTransfer> transfers, String action, UUID other) {
        Map<UUID, Protection> protections = new HashMap<>();
        runtime.protections().blocks().forEach(protection -> protections.put(protection.getId(), protection));
        runtime.protections().entities().forEach(protection -> protections.put(protection.getId(), protection));
        for (PendingTransfer transfer : transfers) {
            if (!valid(transfer, protections.get(transfer.protection()))) runtime.bolt().getStore().removePendingTransfer(transfer);
        }
        List<PendingTransfer> matching = transfers.stream()
                .filter(transfer -> valid(transfer, protections.get(transfer.protection())))
                .filter(transfer -> action.equals("list") ? transfer.giver().equals(player.getUUID()) || transfer.recipient().equals(player.getUUID())
                        : action.equals("cancel") ? transfer.giver().equals(player.getUUID()) && transfer.recipient().equals(other)
                        : transfer.recipient().equals(player.getUUID()) && transfer.giver().equals(other))
                .sorted(Comparator.comparingLong(PendingTransfer::created).reversed()).toList();
        if (action.equals("list")) {
            CommandSupport.say(player, matching.isEmpty() ? "譲渡申請はありません。" : "譲渡申請: " + matching);
            return;
        }
        for (PendingTransfer transfer : matching) {
            if (action.equals("accept")) {
                Protection protection = protections.get(transfer.protection());
                protection.setOwner(player.getUUID());
                if (runtime.settings().transferResetsAccess()) {
                    protection.setType(runtime.settings().defaultProtectionType());
                    protection.getAccess().clear();
                }
                runtime.protections().save(protection);
            }
            runtime.bolt().getStore().removePendingTransfer(transfer);
        }
        CommandSupport.say(player, "譲渡申請 " + matching.size() + " 件を処理しました。");
    }

    private static boolean valid(PendingTransfer transfer, Protection protection) {
        return protection != null && protection.getOwner().equals(transfer.giver());
    }
}
