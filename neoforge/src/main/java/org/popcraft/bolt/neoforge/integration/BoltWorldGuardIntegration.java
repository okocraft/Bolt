package org.popcraft.bolt.neoforge.integration;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import de.kuro.worldguard.api.WorldGuardFlagRegistrationEvent;
import de.kuro.worldguard.api.WorldGuardNeoForge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.api.LockBlockEvent;
import org.popcraft.bolt.neoforge.api.LockEntityEvent;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypes;

import java.util.UUID;

/** WorldGuard flag and region membership policy for Bolt locks. */
public final class BoltWorldGuardIntegration {
    private static final BoltWorldGuardIntegration INSTANCE = new BoltWorldGuardIntegration();
    private StateFlag boltProtections;

    private BoltWorldGuardIntegration() { }

    public static void register() {
        NeoForge.EVENT_BUS.register(INSTANCE);
        RegionCommands.registerTicks();
    }

    public static void started(BoltRuntime runtime) {
        runtime.bolt().getRegisteredPlayerResolvers().add(BoltWorldGuardIntegration::isRegionMember);
    }

    @SubscribeEvent
    public void onFlagRegistration(WorldGuardFlagRegistrationEvent event) {
        Flag<?> existing = event.getRegistry().get("bolt-protections");
        if (existing == null) {
            StateFlag created = new StateFlag("bolt-protections", false);
            event.getRegistry().register(created);
            boltProtections = created;
        } else if (existing instanceof StateFlag state) {
            boltProtections = state;
        } else {
            throw new IllegalStateException("WorldGuard flag bolt-protections has a conflicting type");
        }
    }

    @SubscribeEvent
    public void onLockBlock(LockBlockEvent event) {
        if (!mayLock(event.getPlayer(), event.getLevel(), event.getPos(), false)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onLockEntity(LockEntityEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || !mayLock(event.getPlayer(), level, event.getEntity().blockPosition(), true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        RegionCommands.register(event);
    }

    private boolean mayLock(ServerPlayer player, ServerLevel level, BlockPos pos, boolean entity) {
        if (BoltNeoForge.runtime() == null) return false;
        if (!WorldGuardNeoForge.isReady() || boltProtections == null) return false;
        World world = WorldGuardNeoForge.world(level);
        LocalPlayer local = WorldGuardNeoForge.player(player);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        Location location = new Location(world, pos.getX(), pos.getY(), pos.getZ());
        StateFlag.State state = query.queryState(location, local, boltProtections);
        if (state == StateFlag.State.ALLOW) return true;
        if (state == StateFlag.State.DENY) return false;
        return WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, world)
                || (entity ? query.testBuild(location, local)
                        : query.testBuild(location, local, Flags.BLOCK_PLACE));
    }

    private static boolean isRegionMember(Source source, UUID playerId) {
        if (!SourceTypes.REGION.equals(source.getType()) || !WorldGuardNeoForge.isReady()) return false;
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return false;
        ServerPlayer player = runtime.server().getPlayerList().getPlayer(playerId);
        if (player == null) return false;
        World world = WorldGuardNeoForge.world(player.level());
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
        if (manager == null) return false;
        var region = manager.getRegion(source.getIdentifier());
        return region != null && region.isMember(WorldGuardNeoForge.player(player));
    }
}
