package org.popcraft.bolt.neoforge.testmod.integration;

import com.mojang.authlib.GameProfile;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import de.kuro.worldguard.api.WorldGuardNeoForge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.model.user.User;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.protection.BlockProtection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Isolated-world behavior checks, invoked once after Bolt and WorldGuard are ready. */
public final class IntegrationFixtures {
    private static final String REGION_ID = "bolt_test_worldguard_lock";
    private static final BlockPos CLAIM = new BlockPos(8192, 250, 8192);
    private static final BlockPos OUTSIDE = new BlockPos(8208, 250, 8192);
    private static final BlockPos BEACON = new BlockPos(8240, 250, 8192);
    private static final UUID OWNER = UUID.fromString("7df77846-c62e-4a85-b250-9c3a1a324bc3");
    private static final UUID OTHER = UUID.fromString("99ebaa61-0174-47cf-a091-97042deeb08a");
    private static Session session;
    private static boolean completed;

    private IntegrationFixtures() { }

    public static void run(ServerLevel level) {
        if (session != null || completed) throw new AssertionError("Integration fixture already started");
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null || !WorldGuardNeoForge.isReady())
            throw new AssertionError("Bolt and WorldGuard must be ready before integration fixtures");
        Object registered = WorldGuard.getInstance().getFlagRegistry().get("bolt-protections");
        if (!(registered instanceof StateFlag flag))
            throw new AssertionError("bolt-protections flag was not registered before WorldGuard initialization");
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(WorldGuardNeoForge.world(level));
        if (manager == null || manager.hasRegion(REGION_ID))
            throw new AssertionError("Isolated WorldGuard region fixture unavailable");
        FakePlayer owner = fake(level, OWNER, "bolt_test_owner", CLAIM);
        FakePlayer other = fake(level, OTHER, "bolt_test_other", CLAIM);
        var sessions = WorldGuard.getInstance().getPlatform().getSessionManager();
        for (FakePlayer player : new FakePlayer[] {owner, other}) {
            var local = WorldGuardNeoForge.player(player);
            if (sessions.hasBypass(local, local.getWorld()))
                throw new AssertionError("Fixture player unexpectedly bypasses WorldGuard");
        }
        Map<BlockPos, BlockState> before = new HashMap<>();
        var region = new ProtectedCuboidRegion(REGION_ID,
                BlockVector3.at(CLAIM.getX(), CLAIM.getY(), CLAIM.getZ()),
                BlockVector3.at(CLAIM.getX() + 1, CLAIM.getY(), CLAIM.getZ()));
        region.getOwners().addPlayer(OWNER);
        User adminUser = null;
        PermissionNode adminNode = PermissionNode.builder("bolt.command.admin").build();
        try {
            if (runtime.protections().find(level, CLAIM) != null
                    || runtime.protections().find(level, OUTSIDE) != null)
                throw new AssertionError("Isolated chest fixture positions already have locks");
            saveAndSet(level, before, CLAIM, Blocks.CHEST.defaultBlockState());
            saveAndSet(level, before, OUTSIDE, Blocks.CHEST.defaultBlockState());
            manager.addRegion(region);
            String type = runtime.settings().defaultProtectionType();
            BlockProtection outside = runtime.protections().lock(other, level, OUTSIDE, type);
            if (outside == null) throw new AssertionError("Unclaimed control chest could not be locked");
            runtime.protections().remove(outside);

            if (runtime.protections().lock(other, level, CLAIM, type) != null)
                throw new AssertionError("Nonmember locked chest with unset bolt-protections flag");
            BlockProtection memberLock = runtime.protections().lock(owner, level, CLAIM, type);
            if (memberLock == null) throw new AssertionError("Region owner could not lock chest with unset flag");
            runtime.protections().remove(memberLock);

            region.setFlag(flag, StateFlag.State.ALLOW);
            BlockProtection allowed = runtime.protections().lock(other, level, CLAIM, type);
            if (allowed == null) throw new AssertionError("Explicit ALLOW did not permit nonmember lock");
            runtime.protections().remove(allowed);

            region.setFlag(flag, StateFlag.State.DENY);
            if (runtime.protections().lock(owner, level, CLAIM, type) != null)
                throw new AssertionError("Explicit DENY did not reject region owner lock");

            testBeaconOwnership(runtime, level, owner, other, before);
            adminUser = LuckPermsProvider.get().getUserManager().loadUser(OWNER).join();
            adminUser.data().add(adminNode);
            if (!runtime.permissions().has(owner, "bolt.command.admin", false))
                throw new AssertionError("Region command actor did not receive admin permission");
            BlockPos wrongBlock = CLAIM.east();
            saveAndSet(level, before, wrongBlock, Blocks.STONE.defaultBlockState());
            session = new Session(runtime, level, manager, before, owner, type, adminUser, adminNode);
            command(owner, "protectregion " + REGION_ID + " block:chest owner:" + OWNER + " type:" + type);
        } catch (Throwable failure) {
            cleanup(runtime, level, manager, before, adminUser, adminNode);
            session = null;
            throw failure;
        }
    }

    public static boolean tick(ServerLevel level) {
        if (completed) return true;
        Session active = session;
        if (active == null) return false;
        if (active.level != level) throw new AssertionError("Integration fixture dimension changed");
        try {
            active.phaseTicks++;
            if (active.phaseTicks > 120)
                throw new AssertionError("Region command did not finish phase " + active.phase);
            BlockProtection selected = active.runtime.protections().find(level, CLAIM);
            if (active.phase == 0 && selected != null && active.phaseTicks >= 8) {
                if (!OWNER.equals(selected.getOwner())
                        || active.runtime.protections().find(level, CLAIM.east()) != null
                        || active.runtime.protections().find(level, OUTSIDE) != null)
                    throw new AssertionError("Region protect command ignored block or region filter");
                command(active.owner, "purgeregion " + REGION_ID
                        + " block:chest owner:" + OTHER + " type:" + active.type);
                active.phase = 1;
                active.phaseTicks = 0;
            } else if (active.phase == 1 && active.phaseTicks >= 8) {
                if (selected == null)
                    throw new AssertionError("Region purge removed another owner's lock");
                command(active.owner, "purgeregion " + REGION_ID
                        + " block:chest owner:" + OWNER + " type:" + active.type);
                active.phase = 2;
                active.phaseTicks = 0;
            } else if (active.phase == 2 && selected == null && active.phaseTicks >= 2) {
                cleanup(active.runtime, level, active.manager, active.before,
                        active.adminUser, active.adminNode);
                session = null;
                completed = true;
                System.out.println("BOLT_INTEGRATION_TESTS_PASSED");
                return true;
            }
            return false;
        } catch (Throwable failure) {
            cleanup(active.runtime, level, active.manager, active.before,
                    active.adminUser, active.adminNode);
            session = null;
            throw failure;
        }
    }

    private static void testBeaconOwnership(BoltRuntime runtime, ServerLevel level,
                                            FakePlayer owner, FakePlayer other,
                                            Map<BlockPos, BlockState> before) {
        saveAndSet(level, before, BEACON, Blocks.BEACON.defaultBlockState());
        for (int x = BEACON.getX() - 1; x <= BEACON.getX() + 1; x++) {
            for (int z = BEACON.getZ() - 1; z <= BEACON.getZ() + 1; z++) {
                BlockPos pos = new BlockPos(x, BEACON.getY() - 1, z);
                if (runtime.protections().find(level, pos) != null)
                    throw new AssertionError("Isolated beacon fixture position already has a lock");
                saveAndSet(level, before, pos,
                        Blocks.IRON_BLOCK.defaultBlockState());
            }
        }
        if (!ModList.get().isLoaded("luckperms"))
            throw new AssertionError("Beacon permission fixture requires LuckPerms");
        var users = LuckPermsProvider.get().getUserManager();
        var ownerUser = users.loadUser(OWNER).join();
        var otherUser = users.loadUser(OTHER).join();
        var permission = PermissionNode.builder("boltbeaconprotection.autoprotect").build();
        ownerUser.data().add(permission);
        otherUser.data().add(permission);
        try {
            if (!runtime.permissions().has(owner, "boltbeaconprotection.autoprotect", false)
                    || !runtime.permissions().has(other, "boltbeaconprotection.autoprotect", false))
                throw new AssertionError("Beacon test actors did not receive the required permission");
            BlockPos alreadyOwned = BEACON.below();
            BlockProtection existing = runtime.protections().lockBeaconBase(owner, level, alreadyOwned,
                    runtime.settings().defaultProtectionType());
            if (existing == null) throw new AssertionError("Beacon base control lock could not be created");
            var data = new SimpleContainerData(3);
            data.set(0, 1);
            BeaconMenu menu = new BeaconMenu(1, new SimpleContainer(1), data,
                    ContainerLevelAccess.create(level, BEACON));
            menu.getSlot(0).set(new ItemStack(Items.EMERALD));
            other.setPos(BEACON.getX() + 0.5, BEACON.getY(), BEACON.getZ() + 0.5);
            other.containerMenu = menu;
            setBeaconNative(other, new ServerboundSetBeaconPacket(Optional.of(MobEffects.SPEED), Optional.empty()));
            if (menu.getSlot(0).hasItem())
                throw new AssertionError("Beacon packet did not consume the valid payment");
            int created = 0;
            for (int x = BEACON.getX() - 1; x <= BEACON.getX() + 1; x++) {
                for (int z = BEACON.getZ() - 1; z <= BEACON.getZ() + 1; z++) {
                    if (runtime.protections().find(level, new BlockPos(x, BEACON.getY() - 1, z)) != null) created++;
                }
            }
            if (created != 9) throw new AssertionError("Expected 9 protected beacon base blocks, got " + created);
            BlockProtection retained = runtime.protections().find(level, alreadyOwned);
            if (retained == null || !OWNER.equals(retained.getOwner()))
                throw new AssertionError("Beacon autoprotect replaced another player's existing lock");
            BlockProtection adjacent = runtime.protections().find(level, alreadyOwned.east());
            if (adjacent == null || !OTHER.equals(adjacent.getOwner()))
                throw new AssertionError("Beacon autoprotect did not assign a new base lock to the actor");
        } finally {
            other.containerMenu = other.inventoryMenu;
            ownerUser.data().remove(permission);
            otherUser.data().remove(permission);
        }
    }

    private static void setBeaconNative(FakePlayer player, ServerboundSetBeaconPacket packet) {
        try {
            ServerGamePacketListenerImpl connection = player.connection;
            MethodHandle handler = MethodHandles.privateLookupIn(connection.getClass(), MethodHandles.lookup())
                    .findSpecial(ServerGamePacketListenerImpl.class, "handleSetBeaconPacket",
                            MethodType.methodType(void.class, ServerboundSetBeaconPacket.class),
                            connection.getClass());
            handler.invokeWithArguments(connection, packet);
        } catch (Throwable failure) {
            throw new AssertionError("Native beacon packet failed", failure);
        }
    }

    private static void command(FakePlayer player, String text) {
        player.level().getServer().getCommands()
                .performPrefixedCommand(player.createCommandSourceStack(), text);
    }

    private static void cleanup(BoltRuntime runtime, ServerLevel level, RegionManager manager,
                                Map<BlockPos, BlockState> before, User adminUser,
                                PermissionNode adminNode) {
        if (adminUser != null) adminUser.data().remove(adminNode);
        manager.removeRegion(REGION_ID);
        for (BlockPos pos : before.keySet()) {
            BlockProtection protection = runtime.protections().find(level, pos);
            if (protection != null) runtime.protections().remove(protection);
        }
        before.forEach(level::setBlockAndUpdate);
    }

    private static final class Session {
        final BoltRuntime runtime;
        final ServerLevel level;
        final RegionManager manager;
        final Map<BlockPos, BlockState> before;
        final FakePlayer owner;
        final String type;
        final User adminUser;
        final PermissionNode adminNode;
        int phase;
        int phaseTicks;

        Session(BoltRuntime runtime, ServerLevel level, RegionManager manager,
                Map<BlockPos, BlockState> before, FakePlayer owner, String type,
                User adminUser, PermissionNode adminNode) {
            this.runtime = runtime;
            this.level = level;
            this.manager = manager;
            this.before = before;
            this.owner = owner;
            this.type = type;
            this.adminUser = adminUser;
            this.adminNode = adminNode;
        }
    }

    private static FakePlayer fake(ServerLevel level, UUID id, String name, BlockPos pos) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(id, name));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static void saveAndSet(ServerLevel level, Map<BlockPos, BlockState> before,
                                   BlockPos pos, BlockState replacement) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) throw new AssertionError("Isolated fixture position is occupied: " + pos);
        before.put(pos, state);
        level.setBlockAndUpdate(pos, replacement);
    }
}
