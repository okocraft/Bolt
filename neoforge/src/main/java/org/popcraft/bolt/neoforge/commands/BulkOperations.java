package org.popcraft.bolt.neoforge.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Serializes administrative scans and database import/export on one server. */
final class BulkOperations {
    private static String active;
    private BulkOperations() {}

    static boolean begin(CommandSourceStack source, String operation) {
        if (active != null) {
            source.sendFailure(Component.literal("Bolt " + active + " が実行中です。"));
            return false;
        }
        active = operation;
        return true;
    }

    static boolean available(CommandSourceStack source) {
        if (active == null) return true;
        source.sendFailure(Component.literal("Bolt " + active + " が実行中です。"));
        return false;
    }

    static void end(String operation) {
        if (operation.equals(active)) active = null;
    }
}
