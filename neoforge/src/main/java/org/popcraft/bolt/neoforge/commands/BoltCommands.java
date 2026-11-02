package org.popcraft.bolt.neoforge.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;

import java.util.List;

/** Brigadier entry points for Bolt and OkoCraft's chat-command aliases. */
public final class BoltCommands {
    private BoltCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CleanupJob.register();
        StorageCommands.register();
        LiteralArgumentBuilder<CommandSourceStack> bolt = Commands.literal("bolt")
                .executes(ctx -> execute(ctx.getSource(), "help", ""));
        for (String sub : List.of("lock", "unlock", "info", "edit", "modify", "mode", "password",
                "transfer", "requests", "group", "trust", "admin", "help")) {
            bolt.then(node(sub, sub));
        }
        event.getDispatcher().register(bolt);
        for (String alias : List.of("lock", "unlock", "info", "edit", "modify", "mode", "password",
                "transfer", "requests", "group", "trust", "cinfo", "cmodify", "ctransfer",
                "caccess", "citemmove", "credstone", "cautoclose", "cpersist", "cnolock", "cnospam")) {
            event.getDispatcher().register(node(alias, alias));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node(String label, String route) {
        return Commands.literal(label)
                .executes(ctx -> execute(ctx.getSource(), route, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> execute(ctx.getSource(), route, StringArgumentType.getString(ctx, "args"))));
    }

    private static int execute(CommandSourceStack source, String route, String raw) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("Bolt はまだ利用できません。"));
            return 0;
        }
        if (route.equals("admin")) return AdminCommands.run(runtime, source, CommandSupport.words(raw));
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("プレイヤーから実行してください。"));
            return 0;
        }
        List<String> args = CommandSupport.words(raw);
        return switch (route) {
            case "lock", "unlock", "info", "edit", "modify", "mode", "password", "transfer",
                 "cinfo", "cmodify", "ctransfer", "caccess", "citemmove", "credstone", "cautoclose",
                 "cpersist", "cnolock", "cnospam" -> ProtectionCommands.run(runtime, player, route, args);
            case "group", "trust", "requests" -> SocialCommands.run(runtime, player, route, args);
            case "help" -> {
                CommandSupport.say(player, "/lock [type], /unlock, /cinfo, /cmodify, /caccess, /citemmove, /credstone, /cautoclose, /ctransfer, /bolt group|trust|requests|admin");
                yield 1;
            }
            default -> 0;
        };
    }
}
