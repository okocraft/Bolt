package org.popcraft.bolt.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.popcraft.bolt.neoforge.commands.BoltCommands;
import org.popcraft.bolt.neoforge.commands.PendingActions;
import org.popcraft.bolt.neoforge.protection.ProtectionHooks;
import org.popcraft.bolt.neoforge.integration.BoltIntegrations;

@Mod("bolt")
public final class BoltNeoForge {
    private static volatile BoltRuntime runtime;
    public BoltNeoForge() {
        NeoForge.EVENT_BUS.addListener(BoltCommands::register);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(this::login);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::tick);
        ProtectionHooks.register();
        BoltIntegrations.register();
    }
    public static BoltRuntime runtime() { return runtime; }
    private void start(ServerStartingEvent event) {
        try {
            runtime=new BoltRuntime(event.getServer(),FMLPaths.CONFIGDIR.get().resolve("bolt"));
            BoltIntegrations.started(runtime);
            System.getLogger("Bolt").log(System.Logger.Level.INFO,"Bolt NeoForge initialized with "+runtime.protections().blocks().size()+" block protections");
        } catch (Exception failure) { throw new IllegalStateException("Bolt protection data could not be initialized",failure); }
    }
    private void stop(ServerStoppingEvent event) { if (runtime!=null) { try { runtime.close(); } finally { runtime=null; } } }
    private void login(PlayerEvent.PlayerLoggedInEvent event) { if (runtime!=null && event.getEntity() instanceof ServerPlayer player) runtime.loadPlayer(player); }
    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (runtime!=null && event.getEntity() instanceof ServerPlayer player) {
            runtime.savePlayer(player); PendingActions.forget(player.getUUID()); runtime.bolt().removeBoltPlayer(player.getUUID());
        }
    }
    private void tick(ServerTickEvent.Post event) {
        if (runtime!=null) for (var player:event.getServer().getPlayerList().getPlayers()) runtime.bolt().getBoltPlayer(player.getUUID()).clearInteraction();
    }
}
