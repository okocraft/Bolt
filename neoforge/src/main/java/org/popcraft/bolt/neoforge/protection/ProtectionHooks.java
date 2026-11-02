package org.popcraft.bolt.neoforge.protection;

import net.neoforged.neoforge.common.NeoForge;

/** Registers the server-side native protection listeners once during mod construction. */
public final class ProtectionHooks {
    private static boolean registered;

    private ProtectionHooks() { }

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.register(new BlockProtectionEvents());
        NeoForge.EVENT_BUS.register(new EntityProtectionEvents());
        NeoForge.EVENT_BUS.register(new AutomationProtectionEvents());
    }
}
