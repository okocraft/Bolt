package org.popcraft.bolt.neoforge.integration;

import net.neoforged.fml.ModList;
import org.popcraft.bolt.neoforge.BoltRuntime;

/** Optional platform integrations registered from Bolt's lifecycle. */
public final class BoltIntegrations {
    private BoltIntegrations() { }

    public static void register() {
        if (ModList.get().isLoaded("worldguard")) BoltWorldGuardIntegration.register();
    }

    public static void started(BoltRuntime runtime) {
        if (ModList.get().isLoaded("worldguard")) BoltWorldGuardIntegration.started(runtime);
    }
}
