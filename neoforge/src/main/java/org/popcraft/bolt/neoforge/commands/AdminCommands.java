package org.popcraft.bolt.neoforge.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.util.Action;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class AdminCommands {
    private AdminCommands() {}

    static int run(BoltRuntime runtime, CommandSourceStack source, List<String> args) {
        String operation = args.isEmpty() ? "status" : args.getFirst().toLowerCase();
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            if (!CommandSupport.has(runtime, player, "bolt.command.admin")
                    || (!operation.equals("status") && !CommandSupport.has(runtime, player, "bolt.command.admin." + operation))) {
                source.sendFailure(Component.literal("Bolt 管理権限がありません。")); return 0;
            }
        } else if (!source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)) {
            source.sendFailure(Component.literal("Bolt 管理権限がありません。")); return 0;
        }
        List<String> tail = args.isEmpty() ? List.of() : args.subList(1, args.size());
        return switch (operation) {
            case "status" -> { say(source, "ブロック保護 " + runtime.protections().blocks().size() + " / エンティティ保護 " + runtime.protections().entities().size()); yield 1; }
            case "debug" -> player == null ? playerOnly(source) : pending(runtime, player, Action.Type.DEBUG, "bolt.command.admin.debug", null, false);
            case "flush" -> { runtime.bolt().getStore().flush().whenComplete((unused, error) -> runtime.server().execute(() -> say(source, error == null ? "保存を完了しました。" : "保存に失敗しました: " + error.getMessage()))); yield 1; }
            case "reload" -> {
                if (!BulkOperations.available(source)) yield 0;
                try { runtime.reload(); say(source, "設定を再読込しました。"); yield 1; }
                catch (java.io.IOException failure) { source.sendFailure(Component.literal("Bolt 設定の再読込に失敗しました: " + failure.getMessage())); yield 0; }
            }
            case "find" -> find(runtime, source, tail);
            case "nearby" -> player == null ? playerOnly(source) : nearby(runtime, player, tail);
            case "purge" -> purge(runtime, source, tail);
            case "transfer" -> transfer(runtime, source, player, tail);
            case "trust" -> adminTrust(runtime, source, player, tail);
            case "expire" -> expire(runtime, source, tail);
            case "cleanup" -> CleanupJob.start(runtime, source);
            case "report" -> report(runtime, source);
            case "storage" -> StorageCommands.run(runtime, source, CommandSupport.word(tail, 0));
            default -> { say(source, "管理操作: cleanup, debug, expire, find, flush, nearby, purge, reload, report, storage, transfer, trust"); yield 0; }
        };
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[Bolt] " + text), false);
    }

    private static int playerOnly(CommandSourceStack source) {
        source.sendFailure(Component.literal("プレイヤーから実行してください。"));
        return 0;
    }

    private static int pending(BoltRuntime runtime, ServerPlayer player, Action.Type type, String permission, String data, boolean admin) {
        runtime.bolt().getBoltPlayer(player.getUUID()).setAction(new Action(type, permission, data, admin));
        CommandSupport.promptClick(runtime, player);
        return 1;
    }

    private static List<Protection> all(BoltRuntime runtime) {
        List<Protection> result = new ArrayList<>();
        result.addAll(runtime.protections().blocks());
        result.addAll(runtime.protections().entities());
        return result;
    }

    private static int find(BoltRuntime runtime, CommandSourceStack source, List<String> args) {
        if (args.isEmpty()) { say(source, "使い方: /bolt admin find <player>"); return 0; }
        Optional<UUID> owner = runtime.players().resolve(args.getFirst());
        if (owner.isEmpty()) { say(source, "プレイヤーが見つかりません。"); return 0; }
        List<Protection> found = all(runtime).stream().filter(p -> p.getOwner().equals(owner.get()))
                .sorted(Comparator.comparingLong(Protection::getCreated).reversed()).toList();
        return page(source, found, CommandSupport.word(args, 1));
    }

    private static int nearby(BoltRuntime runtime, ServerPlayer player, List<String> args) {
        if (args.isEmpty()) { CommandSupport.usage(player, "/bolt admin nearby <radius>"); return 0; }
        int radius;
        try { radius = Integer.parseInt(args.getFirst()); }
        catch (NumberFormatException exception) { CommandSupport.say(player, "距離は正の整数で指定してください。"); return 0; }
        if (radius < 1) { CommandSupport.say(player, "距離は正の整数で指定してください。"); return 0; }
        Map<Protection, Double> distances = new HashMap<>();
        for (BlockProtection protection : runtime.protections().blocks()) {
            if (protection.getWorld().equals(player.level().dimension().identifier().toString()))
                distances.put(protection, new BlockPos(protection.getX(), protection.getY(), protection.getZ()).distSqr(player.blockPosition()));
        }
        for (EntityProtection protection : runtime.protections().entities()) {
            var entity = player.level().getEntityInAnyDimension(protection.getId());
            if (entity != null && entity.level() == player.level()) distances.put(protection, entity.blockPosition().distSqr(player.blockPosition()));
        }
        List<Protection> found = distances.entrySet().stream().filter(entry -> entry.getValue() < (double) radius * radius)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue)).map(Map.Entry::getKey).toList();
        return page(player.createCommandSourceStack(), found, CommandSupport.word(args, 1));
    }

    private static int page(CommandSourceStack source, List<Protection> protections, String pageText) {
        int number = 1;
        if (!pageText.isEmpty()) {
            try { number = Integer.parseInt(pageText); }
            catch (NumberFormatException failure) { source.sendFailure(Component.literal("ページは正の整数で指定してください。")); return 0; }
        }
        if (number < 1) { source.sendFailure(Component.literal("ページは正の整数で指定してください。")); return 0; }
        int pages = Math.max(1, (protections.size() + 9) / 10);
        if (number > pages) { source.sendFailure(Component.literal("ページがありません。全 " + pages + " ページ")); return 0; }
        say(source, "保護 " + protections.size() + " 件 / " + number + "/" + pages + " ページ");
        int first = (number - 1) * 10;
        protections.stream().skip(first).limit(10).forEach(protection -> say(source, AccessDisplay.protection(protection)));
        return 1;
    }

    private static int purge(BoltRuntime runtime, CommandSourceStack source, List<String> args) {
        if (!BulkOperations.available(source)) return 0;
        if (args.isEmpty()) { say(source, "使い方: /bolt admin purge <player>"); return 0; }
        Optional<UUID> owner = runtime.players().resolve(args.getFirst());
        if (owner.isEmpty()) { say(source, "プレイヤーが見つかりません。"); return 0; }
        List<Protection> found = all(runtime).stream().filter(p -> p.getOwner().equals(owner.get())).toList();
        found.forEach(protection -> { runtime.protections().remove(protection); TransferState.clear(runtime, protection.getId()); });
        say(source, "保護 " + found.size() + " 件を削除しました。");
        return 1;
    }

    private static int transfer(BoltRuntime runtime, CommandSourceStack source, ServerPlayer player, List<String> args) {
        if (!BulkOperations.available(source)) return 0;
        if (args.isEmpty()) { say(source, "使い方: /bolt admin transfer <newOwner> または <oldOwner> <newOwner>"); return 0; }
        if (args.size() == 1) {
            if (player == null) return playerOnly(source);
            return runtime.players().resolve(args.getFirst())
                    .map(owner -> pending(runtime, player, Action.Type.TRANSFER, "bolt.command.admin.transfer", owner.toString(), true)).orElse(0);
        }
        Optional<UUID> oldOwner = runtime.players().resolve(args.get(0));
        Optional<UUID> newOwner = runtime.players().resolve(args.get(1));
        if (oldOwner.isEmpty() || newOwner.isEmpty()) { say(source, "プレイヤーが見つかりません。"); return 0; }
        List<Protection> found = all(runtime).stream().filter(p -> p.getOwner().equals(oldOwner.get())).toList();
        for (Protection protection : found) { protection.setOwner(newOwner.get()); runtime.protections().save(protection); TransferState.clear(runtime, protection.getId()); }
        say(source, "保護 " + found.size() + " 件を譲渡しました。");
        return 1;
    }

    private static int adminTrust(BoltRuntime runtime, CommandSourceStack source, ServerPlayer player, List<String> args) {
        if (player == null) return playerOnly(source);
        if (args.isEmpty()) { say(source, "使い方: /bolt admin trust <owner> [add|remove <source> <identifier> [access]]"); return 0; }
        Optional<UUID> owner = runtime.players().resolve(args.getFirst());
        if (owner.isEmpty()) { say(source, "プレイヤーが見つかりません。"); return 0; }
        return SocialCommands.trust(runtime, player, owner.get(), args.subList(1, args.size()), false);
    }

    private static int expire(BoltRuntime runtime, CommandSourceStack source, List<String> args) {
        if (!BulkOperations.available(source)) return 0;
        if (args.size() < 2) { say(source, "使い方: /bolt admin expire <number> <timeUnit>"); return 0; }
        long amount;
        TimeUnit unit;
        try { amount = Long.parseLong(args.get(0)); unit = TimeUnit.valueOf(args.get(1).toUpperCase()); }
        catch (IllegalArgumentException exception) { say(source, "時間を指定してください。"); return 0; }
        if (amount <= 0) { say(source, "時間は正の値で指定してください。"); return 0; }
        long threshold = System.currentTimeMillis() - unit.toMillis(amount);
        List<BlockProtection> found = runtime.protections().blocks().stream()
                .filter(p -> p.getAccessed() > 0 && p.getAccessed() < threshold).toList();
        found.forEach(protection -> { runtime.protections().remove(protection); TransferState.clear(runtime, protection.getId()); });
        say(source, "期限切れのブロック保護 " + found.size() + " 件を削除しました。");
        return 1;
    }

    private static int report(BoltRuntime runtime, CommandSourceStack source) {
        List<Protection> protections = all(runtime);
        Map<String, Long> types = protections.stream().collect(Collectors.groupingBy(Protection::getType, Collectors.counting()));
        long owners = protections.stream().map(Protection::getOwner).distinct().count();
        say(source, "保存待ち " + runtime.bolt().getStore().pendingSave()
                + " / ブロック保護 " + runtime.protections().blocks().size()
                + " / エンティティ保護 " + runtime.protections().entities().size()
                + " / 所有者 " + owners + " / 種類 " + types);
        return 1;
    }
}
