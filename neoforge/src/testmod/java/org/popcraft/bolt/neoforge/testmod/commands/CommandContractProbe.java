package org.popcraft.bolt.neoforge.testmod.commands;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.fml.ModList;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.commands.PendingActions;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.Mode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side command contract probe. Caller supplies a real or fake player in an isolated test world. */
public final class CommandContractProbe {
    private CommandContractProbe() {}

    public static void run(MinecraftServer server, ServerPlayer actor, BoltRuntime runtime) {
        ServerLevel level = actor.level();
        RecordingPlayer commandActor = new RecordingPlayer(level, new GameProfile(actor.getUUID(), "bolt_test_cmd"));
        commandActor.setPos(actor.getX(), actor.getY(), actor.getZ());
        actor = commandActor;
        BlockPos pos = actor.blockPosition().offset(3, 0, 3);
        var before = level.getBlockState(pos);
        if (runtime.protections().find(level, pos) != null) throw new AssertionError("Test position already protected");
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        User loadedUser = null;
        boolean cleanupUser = false;
        try {
            if (ModList.get().isLoaded("luckperms")) {
                var users = LuckPermsProvider.get().getUserManager();
                loadedUser = users.getUser(actor.getUUID());
                if (loadedUser == null) {
                    loadedUser = users.loadUser(actor.getUUID(), actor.getGameProfile().name()).join();
                    cleanupUser = true;
                }
            }
            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "lock");
            if (!PendingActions.consume(actor, level, pos)) {
                var dispatcher = server.getCommands().getDispatcher();
                var alias = dispatcher.getRoot().getChild("lock");
                var bolt = dispatcher.getRoot().getChild("bolt");
                var source = actor.createCommandSourceStack();
                var parsed = dispatcher.parse("lock", source);
                var canonical = dispatcher.parse("bolt lock", source);
                throw new AssertionError("Lock command did not queue a click; alias=" + alias
                        + "; aliasCommand=" + (alias == null ? null : alias.getCommand())
                        + "; aliasAllowed=" + (alias == null ? null : alias.canUse(source))
                        + "; aliasRequirement=" + (alias == null ? null : alias.getRequirement())
                        + "; parsedNodes=" + parsed.getContext().getNodes()
                        + "; parsedCommand=" + parsed.getContext().getCommand()
                        + "; parseExceptions=" + parsed.getExceptions()
                        + "; canonicalNodes=" + canonical.getContext().getNodes()
                        + "; canonicalCommand=" + canonical.getContext().getCommand()
                        + "; canonicalExceptions=" + canonical.getExceptions()
                        + "; boltRoot=" + bolt + "; chat=" + commandActor.messages
                        + "; fallbackPermission=" + runtime.permissions().has(actor, "bolt.command.lock", true)
                        + "; action=" + runtime.bolt().getBoltPlayer(actor.getUUID()).getAction());
            }
            BlockProtection locked = runtime.protections().find(level, pos);
            if (locked == null || !locked.getOwner().equals(actor.getUUID())) throw new AssertionError("Lock did not persist ownership");

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "caccess " + actor.getUUID());
            if (!PendingActions.consume(actor, level, pos)) throw new AssertionError("Share alias did not queue a click");
            BlockProtection shared = runtime.protections().find(level, pos);
            if (!"normal".equals(shared.getAccess().get("player:" + actor.getUUID()))) throw new AssertionError("Share alias did not grant normal access");

            try (PermissionScope permissions = new PermissionScope(loadedUser)) {
                permissions.set("bolt.command.modify", true);
                permissions.set("bolt.command.edit", false);
                server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "citemmove allow");
                if (!PendingActions.consume(actor, level, pos)) throw new AssertionError("Item move alias did not queue a click");
                if (!"normal".equals(runtime.protections().find(level, pos).getAccess().get("block:block")))
                    throw new AssertionError("Modify permission did not allow item move when edit was denied");

                if (loadedUser != null) {
                    UUID rejected = UUID.fromString("8f4ece9b-a181-4e78-97de-8ee1b47f5726");
                    UUID accepted = UUID.fromString("a9f1fc89-abd5-4482-bdd2-d2608bd132aa");
                    server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(),
                            "bolt modify add normal player " + rejected);
                    if (runtime.bolt().getBoltPlayer(actor.getUUID()).getAction() == null)
                        throw new AssertionError("Modify permission did not queue a click");
                    permissions.set("bolt.command.modify", false);
                    if (!PendingActions.consume(actor, level, pos)
                            || runtime.protections().find(level, pos).getAccess().containsKey("player:" + rejected))
                        throw new AssertionError("Revoked modify permission still changed access");
                    permissions.set("bolt.command.modify", true);
                    server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(),
                            "bolt modify add normal player " + accepted);
                    if (!PendingActions.consume(actor, level, pos)
                            || runtime.protections().find(level, pos).getAccess().containsKey("player:" + rejected)
                            || !"normal".equals(runtime.protections().find(level, pos).getAccess().get("player:" + accepted)))
                        throw new AssertionError("Denied modification carried into the next click");
                }
            }

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "bolt modify add normal password password");
            if (!PendingActions.consume(actor, level, pos)) throw new AssertionError("Password sharing did not queue a click");
            String knownDigest = "5baa61e4c9b93f3f0682250b6cf8331b7ee68fd8";
            if (!"normal".equals(runtime.protections().find(level, pos).getAccess().get("password:" + knownDigest)))
                throw new AssertionError("Password source did not use the persisted hash format");

            RecordingPlayer inspector = new RecordingPlayer(level, new GameProfile(actor.getUUID(), "bolt_test_info"));
            inspector.setPos(actor.getX(), actor.getY(), actor.getZ());
            server.getCommands().performPrefixedCommand(inspector.createCommandSourceStack(), "cinfo");
            if (!PendingActions.consume(inspector, level, pos)) throw new AssertionError("Info alias did not inspect the lock");
            String info = String.join(" ", inspector.messages);
            if (!info.contains("Password=normal") || info.contains(knownDigest) || info.contains("password:"))
                throw new AssertionError("Info exposed a password source identifier: " + info);

            if (loadedUser != null) {
                try (PermissionScope permissions = new PermissionScope(loadedUser)) {
                    permissions.set("bolt.command.admin", true);
                    permissions.set("bolt.command.admin.transfer", true);
                    UUID recipient = UUID.fromString("303d9e28-d745-4d3f-a3dc-042e288f5687");
                    server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(),
                            "bolt admin transfer " + recipient);
                    if (runtime.bolt().getBoltPlayer(actor.getUUID()).getAction() == null)
                        throw new AssertionError("Admin transfer did not queue a click");
                    permissions.set("bolt.command.admin", false);
                    if (!PendingActions.consume(actor, level, pos)
                            || !actor.getUUID().equals(runtime.protections().find(level, pos).getOwner()))
                        throw new AssertionError("Revoked admin permission still transferred ownership");
                }
            }

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cnospam on");
            if (!runtime.bolt().getBoltPlayer(actor.getUUID()).hasMode(Mode.NOSPAM)
                    || !runtime.preferences().load(actor.getUUID()).contains(Mode.NOSPAM))
                throw new AssertionError("No spam mode did not persist");
            commandActor.messages.clear();
            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cinfo");
            if (!PendingActions.consume(actor, level, pos)) throw new AssertionError("No spam mode stopped the pending click");
            String quietInfo = String.join(" ", commandActor.messages);
            if (quietInfo.contains("対象をクリックしてください。") || !quietInfo.contains("保護 private"))
                throw new AssertionError("No spam mode hid the result or displayed the repeat prompt: " + quietInfo);
            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cnospam off");
            if (runtime.bolt().getBoltPlayer(actor.getUUID()).hasMode(Mode.NOSPAM)
                    || runtime.preferences().load(actor.getUUID()).contains(Mode.NOSPAM))
                throw new AssertionError("No spam mode did not persist being disabled");
            commandActor.messages.clear();
            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cinfo");
            if (!PendingActions.consume(actor, level, pos)
                    || commandActor.messages.stream().noneMatch(message -> message.contains("対象をクリックしてください。")))
                throw new AssertionError("Click prompt did not return after disabling no spam mode");

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "unlock");
            if (!runtime.permissions().operator(actor)) {
                if (PendingActions.consume(actor, level, pos)) throw new AssertionError("First unlock should require confirmation");
                server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "unlock");
            }
            if (!PendingActions.consume(actor, level, pos) || runtime.protections().find(level, pos) != null)
                throw new AssertionError("Confirmed unlock did not remove protection");

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cpersist on");
            if (!runtime.bolt().getBoltPlayer(actor.getUUID()).hasMode(Mode.PERSIST)
                    || !runtime.preferences().load(actor.getUUID()).contains(Mode.PERSIST))
                throw new AssertionError("Persist alias did not save the enabled mode");
            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "cpersist off");
            if (runtime.bolt().getBoltPlayer(actor.getUUID()).hasMode(Mode.PERSIST)
                    || runtime.preferences().load(actor.getUUID()).contains(Mode.PERSIST))
                throw new AssertionError("Persist alias did not save the disabled mode");
        } finally {
            BlockProtection residual = runtime.protections().find(level, pos);
            if (residual != null) runtime.protections().remove(residual);
            level.setBlockAndUpdate(pos, before);
            PendingActions.forget(actor.getUUID());
            if (cleanupUser) LuckPermsProvider.get().getUserManager().cleanupUser(loadedUser);
        }
    }

    private static final class PermissionScope implements AutoCloseable {
        private final User user;
        private final Map<String, List<Node>> original = new HashMap<>();

        PermissionScope(User user) { this.user = user; }

        void set(String permission, boolean value) {
            if (user == null) return;
            original.computeIfAbsent(permission, this::current);
            current(permission).forEach(user.data()::remove);
            user.data().add(Node.builder(permission).value(value).build());
        }

        private List<Node> current(String permission) {
            return user.data().toCollection().stream().filter(node -> node.getKey().equals(permission)).toList();
        }

        @Override public void close() {
            if (user == null) return;
            original.forEach((permission, nodes) -> {
                current(permission).forEach(user.data()::remove);
                nodes.forEach(user.data()::add);
            });
        }
    }

    private static final class RecordingPlayer extends FakePlayer {
        private final List<String> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level, GameProfile profile) { super(level, profile); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public void sendSystemMessage(Component message, boolean overlay) { messages.add(message.getString()); }
    }
}
