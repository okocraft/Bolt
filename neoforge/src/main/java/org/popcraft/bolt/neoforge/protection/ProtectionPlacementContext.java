package org.popcraft.bolt.neoforge.protection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;

import java.util.ArrayDeque;
import java.util.function.Supplier;

/** Associates synchronous item-created entities with the player who placed them. */
public final class ProtectionPlacementContext {
    private static final ThreadLocal<ArrayDeque<ServerPlayer>> CREATORS = new ThreadLocal<>();

    private ProtectionPlacementContext() { }

    public static InteractionResult useOn(UseOnContext context, Supplier<InteractionResult> action) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return action.get();
        push(player);
        try {
            return action.get();
        } finally {
            pop();
        }
    }

    public static InteractionResult use(Player player, Supplier<InteractionResult> action) {
        if (!(player instanceof ServerPlayer serverPlayer)) return action.get();
        push(serverPlayer);
        try {
            return action.get();
        } finally {
            pop();
        }
    }

    static ServerPlayer creator() {
        ArrayDeque<ServerPlayer> creators = CREATORS.get();
        return creators == null ? null : creators.peek();
    }

    private static void push(ServerPlayer player) {
        ArrayDeque<ServerPlayer> creators = CREATORS.get();
        if (creators == null) {
            creators = new ArrayDeque<>();
            CREATORS.set(creators);
        }
        creators.push(player);
    }

    private static void pop() {
        ArrayDeque<ServerPlayer> creators = CREATORS.get();
        creators.pop();
        if (creators.isEmpty()) CREATORS.remove();
    }
}
