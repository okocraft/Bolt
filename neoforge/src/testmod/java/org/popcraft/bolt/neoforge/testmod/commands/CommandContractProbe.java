package org.popcraft.bolt.neoforge.testmod.commands;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.commands.PendingActions;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.Mode;

import java.util.ArrayList;
import java.util.List;

/** Server-side command contract probe. Caller supplies a real or fake player in an isolated test world. */
public final class CommandContractProbe {
    private CommandContractProbe() {}

    public static void run(MinecraftServer server, ServerPlayer actor, BoltRuntime runtime) {
        ServerLevel level = actor.level();
        RecordingPlayer commandActor = new RecordingPlayer(level, new GameProfile(actor.getUUID(), actor.getGameProfile().name()));
        commandActor.setPos(actor.getX(), actor.getY(), actor.getZ());
        actor = commandActor;
        BlockPos pos = actor.blockPosition().offset(3, 0, 3);
        var before = level.getBlockState(pos);
        if (runtime.protections().find(level, pos) != null) throw new AssertionError("Test position already protected");
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        try {
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

            server.getCommands().performPrefixedCommand(actor.createCommandSourceStack(), "citemmove allow");
            if (!PendingActions.consume(actor, level, pos)) throw new AssertionError("Item move alias did not queue a click");
            if (!"normal".equals(runtime.protections().find(level, pos).getAccess().get("block:block")))
                throw new AssertionError("Item move alias did not grant block access");

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
        }
    }

    private static final class RecordingPlayer extends FakePlayer {
        private final List<String> messages = new ArrayList<>();
        RecordingPlayer(ServerLevel level, GameProfile profile) { super(level, profile); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public void sendSystemMessage(Component message, boolean overlay) { messages.add(message.getString()); }
    }
}
