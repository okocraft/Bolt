package org.popcraft.bolt.neoforge.commands;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.protection.PendingTransfer;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.util.Action;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.Permission;

import java.util.UUID;

/** Executes a queued command on the next clicked block or entity. All calls run on the server thread. */
public final class PendingActions {
    private PendingActions() {}

    public static boolean consume(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return false;
        BoltPlayer state = runtime.bolt().getBoltPlayer(player.getUUID());
        Action action = state.getAction();
        if (action == null) return false;
        if (!allowed(runtime, player, action)) {
            CommandSupport.say(player, "権限がありません。");
            state.setAction(null);
            state.getModifications().clear();
            return true;
        }
        Protection protection = runtime.protections().find(level, pos);
        execute(runtime, player, state, action, protection,
                () -> runtime.protections().isProtectable(level, pos),
                () -> runtime.protections().lock(player, level, pos, action.getData(), action.isAdmin()));
        return true;
    }

    public static boolean consume(ServerPlayer player, Entity entity) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return false;
        BoltPlayer state = runtime.bolt().getBoltPlayer(player.getUUID());
        Action action = state.getAction();
        if (action == null) return false;
        if (!allowed(runtime, player, action)) {
            CommandSupport.say(player, "権限がありません。");
            state.setAction(null);
            state.getModifications().clear();
            return true;
        }
        Protection protection = runtime.protections().find(entity);
        execute(runtime, player, state, action, protection,
                () -> runtime.protections().isProtectable(entity),
                () -> runtime.protections().lock(player, entity, action.getData(), action.isAdmin()));
        return true;
    }

    private static void execute(BoltRuntime runtime, ServerPlayer player, BoltPlayer state, Action action,
                                Protection protection, BooleanOperation protectable, LockOperation lock) {
        switch (action.getType()) {
            case LOCK -> {
                Access type = runtime.bolt().getAccessRegistry().getProtectionByType(action.getData()).orElse(null);
                if (type == null || type.restricted() && !CommandSupport.has(runtime, player, "bolt.type.protection." + type.type())
                        || action.isAdmin() && !CommandSupport.has(runtime, player, "bolt.admin")) {
                    CommandSupport.say(player, "この保護タイプは利用できません。");
                    break;
                }
                if (protection != null) {
                    if (runtime.access().canAccess(protection, player, Permission.EDIT)) {
                        protection.setType(action.getData());
                        runtime.protections().save(protection);
                        CommandSupport.say(player, "保護タイプを " + action.getData() + " に変更しました。");
                    } else CommandSupport.say(player, "この保護を変更する権限がありません。");
                } else if (protectable.get() || action.isAdmin()) {
                    Protection created = lock.run();
                    CommandSupport.say(player, created == null ? "保護できませんでした。" : "保護しました: " + created.getType());
                } else CommandSupport.say(player, "この対象は保護できません。");
            }
            case UNLOCK -> {
                if (protection == null) CommandSupport.say(player, "保護されていません。");
                else if (runtime.protections().unlock(player, protection)) {
                    TransferState.clear(runtime, protection.getId());
                    CommandSupport.say(player, "保護を解除しました。");
                }
                else CommandSupport.say(player, "この保護を解除する権限がありません。");
            }
            case INFO -> {
                if (protection == null) CommandSupport.say(player, "保護されていません。");
                else if (protection.getOwner().equals(player.getUUID()) || CommandSupport.has(runtime, player, "bolt.command.info.full")) {
                    CommandSupport.say(player, "保護 " + protection.getType() + " / 所有者 " + protection.getOwner()
                            + " / 共有 " + AccessDisplay.access(protection.getAccess()) + " / 作成 " + protection.getCreated());
                } else CommandSupport.say(player, "保護 " + protection.getType() + " / 所有者 " + protection.getOwner());
            }
            case EDIT -> {
                if (protection == null) CommandSupport.say(player, "保護されていません。");
                else if (!runtime.access().canAccess(protection, player, Permission.EDIT)) CommandSupport.say(player, "この保護を編集する権限がありません。");
                else {
                    state.consumeModifications().forEach((source, type) -> {
                        if (Boolean.parseBoolean(action.getData())) protection.getAccess().put(source.toString(), type);
                        else protection.getAccess().remove(source.toString());
                    });
                    runtime.protections().save(protection);
                    CommandSupport.say(player, "共有設定を変更しました。");
                }
            }
            case DEBUG -> CommandSupport.say(player, protection == null ? "保護されていません。" : AccessDisplay.protection(protection));
            case TRANSFER -> {
                if (protection == null) CommandSupport.say(player, "保護されていません。");
                else if (!action.isAdmin() && !protection.getOwner().equals(player.getUUID())) CommandSupport.say(player, "所有者だけが譲渡できます。");
                else {
                    UUID recipient = UUID.fromString(action.getData());
                    boolean instant = action.isAdmin() || !runtime.settings().transferRequestsEnabled()
                            || !CommandSupport.has(runtime, player, "bolt.command.requests");
                    if (!instant && recipient.equals(protection.getOwner())) CommandSupport.say(player, "自分への譲渡申請はできません。");
                    else if (instant) {
                        protection.setOwner(recipient);
                        if (!action.isAdmin() && runtime.settings().transferResetsAccess()) {
                            protection.setType(runtime.settings().defaultProtectionType());
                            protection.getAccess().clear();
                        }
                        runtime.protections().save(protection);
                        TransferState.clear(runtime, protection.getId());
                        CommandSupport.say(player, "所有者を " + recipient + " に変更しました。");
                    } else {
                        runtime.bolt().getStore().savePendingTransfer(new PendingTransfer(protection.getId(), protection.getOwner(), recipient, System.currentTimeMillis()));
                        ServerPlayer online = runtime.server().getPlayerList().getPlayer(recipient);
                        if (online != null) CommandSupport.say(online, player.getGameProfile().name() + " から保護の譲渡申請が届きました。/bolt requests list で確認できます。");
                        CommandSupport.say(player, "譲渡申請を送りました: " + recipient);
                    }
                }
            }
        }
        state.clearAction();
    }

    public static void forget(UUID player) {
        ProtectionCommands.forget(player);
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime != null) runtime.bolt().removeBoltPlayer(player);
    }

    private static boolean allowed(BoltRuntime runtime, ServerPlayer player, Action action) {
        return CommandSupport.has(runtime, player, action.getPermission())
                && (!action.getPermission().startsWith("bolt.command.admin.")
                    || CommandSupport.has(runtime, player, "bolt.command.admin"));
    }

    @FunctionalInterface private interface BooleanOperation { boolean get(); }
    @FunctionalInterface private interface LockOperation { Protection run(); }
}
