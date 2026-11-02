package org.popcraft.bolt.neoforge.testmod.protection;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.fml.ModList;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.protection.ProtectionMenus;
import org.popcraft.bolt.neoforge.protection.ProtectionAutomation;
import org.popcraft.bolt.neoforge.protection.ProtectionTargets;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Permission;

import java.util.UUID;

/** Native event-bus and menu decisions against independently owned chest fixtures. */
public final class ProtectionBehaviorTests {
    private static final BlockPos FIRST = new BlockPos(4200, 250, 4200);
    private static final BlockPos SECOND = FIRST.east();
    private static final UUID OWNER_ID = UUID.fromString("ca61d214-c512-45ae-8776-3467486db82b");
    private static final UUID OUTSIDER_ID = UUID.fromString("e5289e66-f14e-4d1c-a852-40b2f6c9400d");

    private ProtectionBehaviorTests() { }

    public static void run(ServerLevel level) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) throw new AssertionError("Bolt runtime was not started");
        if (!level.getBlockState(FIRST).isAir() || !level.getBlockState(SECOND).isAir()) {
            throw new AssertionError("Isolated chest fixture positions are occupied");
        }
        FakePlayer owner = FakePlayerFactory.get(level, new GameProfile(OWNER_ID, "bolt_test_owner"));
        FakePlayer outsider = FakePlayerFactory.get(level, new GameProfile(OUTSIDER_ID, "bolt_test_outsider"));
        BlockState left = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockProtection protection = null;
        try {
            level.setBlockAndUpdate(FIRST, left);
            level.setBlockAndUpdate(SECOND, right);
            if (!ProtectionTargets.positions(level, SECOND).contains(FIRST)) {
                throw new AssertionError("Linked double-chest fixture did not connect");
            }
            protection = runtime.protections().create(level, FIRST, OWNER_ID,
                    runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
            if (runtime.protections().find(level, SECOND) == null) {
                throw new AssertionError("Second chest half did not resolve its lock");
            }
            checkRedstoneAccess(level, runtime, protection);

            expect("outsider interact second chest half", rightClick(outsider, SECOND), true);
            expect("owner interact second chest half", rightClick(owner, SECOND), false);
            expect("outsider break first chest half", breakBlock(level, outsider, FIRST), true);
            expect("owner break first chest half", breakBlock(level, owner, FIRST), false);

            Container chest = (Container) level.getBlockEntity(FIRST);
            chest.setItem(0, new ItemStack(Items.DIAMOND));
            ChestMenu menu = ChestMenu.threeRows(1, owner.getInventory(), chest);
            expect("outsider menu open", ProtectionMenus.mayOpen(runtime, outsider, menu, (MenuProvider) chest), false);
            expect("owner menu open", ProtectionMenus.mayOpen(runtime, owner, menu, (MenuProvider) chest), true);
            expect("outsider withdrawal", ProtectionMenus.mayClick(runtime, outsider, menu,
                    0, ContainerInput.PICKUP), false);
            expect("owner withdrawal", ProtectionMenus.mayClick(runtime, owner, menu,
                    0, ContainerInput.PICKUP), true);
            menu.setCarried(new ItemStack(Items.EMERALD));
            expect("outsider deposit", ProtectionMenus.mayClick(runtime, outsider, menu,
                    1, ContainerInput.PICKUP), false);
            expect("outsider shift deposit", ProtectionMenus.mayClick(runtime, outsider, menu,
                    27, ContainerInput.QUICK_MOVE), false);
            expect("outsider drag deposit", ProtectionMenus.mayClick(runtime, outsider, menu,
                    1, ContainerInput.QUICK_CRAFT), false);
            expect("owner deposit", ProtectionMenus.mayClick(runtime, owner, menu,
                    1, ContainerInput.PICKUP), true);
            checkNativeMenu(runtime, level, chest, protection);
            protection.setType("withdrawal");
            runtime.protections().save(protection);
            menu.setCarried(ItemStack.EMPTY);
            expect("withdrawal-only pickup", ProtectionMenus.mayClick(runtime, outsider, menu,
                    0, ContainerInput.PICKUP), true);
            menu.setCarried(new ItemStack(Items.EMERALD));
            expect("withdrawal-only deposit", ProtectionMenus.mayClick(runtime, outsider, menu,
                    2, ContainerInput.PICKUP), false);
            protection.setType(runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
            checkHopperTransfer(level, runtime);
            checkReplacement(level, runtime, outsider);
            checkEntity(level, runtime, owner, outsider);
            checkAutoEntity(level, runtime, owner);
            if (ModList.get().isLoaded("dqmvii")) {
                DqmBoltRangeTest.run(level);
                DqmHugeChestHopperTest.run(level);
            }
            DoorAutoCloseTest.start(level, runtime, owner);
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            level.setBlockAndUpdate(FIRST, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(SECOND, Blocks.AIR.defaultBlockState());
        }
    }

    public static boolean tick(ServerLevel level) {
        if (!DoorAutoCloseTest.tick(level)) return false;
        System.out.println("BOLT_PROTECTION_TESTS_PASSED");
        return true;
    }

    private static void checkRedstoneAccess(ServerLevel level, BoltRuntime runtime,
                                            BlockProtection protection) {
        var resolver = new SourceTypeResolver(Source.of(SourceTypes.REDSTONE));
        String source = Source.of(SourceTypes.REDSTONE).toString();
        if (runtime.access().canAccess(protection, resolver, Permission.REDSTONE)) {
            throw new AssertionError("Redstone fixture already has an explicit REDSTONE source");
        }
        expect("redstone default rule", ProtectionAutomation.mayActivateRedstone(level, FIRST),
                !runtime.settings().redstoneDenyByDefault());
        protection.getAccess().put(source, "normal");
        runtime.protections().save(protection);
        try {
            if (!runtime.access().canAccess(protection, resolver, Permission.REDSTONE)) {
                throw new AssertionError("REDSTONE source did not grant its configured access type");
            }
            expect("redstone explicit source rule", ProtectionAutomation.mayActivateRedstone(level, FIRST),
                    runtime.settings().redstoneDenyByDefault());
        } finally {
            protection.getAccess().remove(source);
            runtime.protections().save(protection);
        }
    }

    private static void checkReplacement(ServerLevel level, BoltRuntime runtime, FakePlayer outsider) {
        BlockPos pos = new BlockPos(4200, 250, 4208);
        if (!level.getBlockState(pos).isAir()) throw new AssertionError("Replacement fixture occupied");
        BlockProtection protection = null;
        try {
            level.setBlockAndUpdate(pos, Blocks.OAK_FENCE_GATE.defaultBlockState());
            protection = runtime.protections().create(level, pos, OWNER_ID, "display");
            runtime.protections().save(protection);
            if (!runtime.access().canAccess(protection, outsider, org.popcraft.bolt.util.Permission.INTERACT)
                    || runtime.access().canAccess(protection, outsider, org.popcraft.bolt.util.Permission.DESTROY)) {
                throw new AssertionError("Replacement fixture needs interact without destroy permission");
            }
            BlockSnapshot before = BlockSnapshot.create(level.dimension(), level, pos);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            var replacement = new BlockEvent.EntityPlaceEvent(before,
                    Blocks.OAK_FENCE_GATE.defaultBlockState(), outsider);
            NeoForge.EVENT_BUS.post(replacement);
            expect("locked block replacement requires destroy", replacement.isCanceled(), true);
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void checkNativeMenu(BoltRuntime runtime, ServerLevel level, Container chest,
                                        BlockProtection protection) {
        ProbeServerPlayer owner = new ProbeServerPlayer(level,
                new GameProfile(OWNER_ID, "bolt_packet_owner"));
        ProbeServerPlayer outsider = new ProbeServerPlayer(level,
                new GameProfile(OUTSIDER_ID, "bolt_packet_outsider"));
        owner.setPos(FIRST.getX() + 0.5, FIRST.getY(), FIRST.getZ() + 0.5);
        outsider.setPos(FIRST.getX() + 0.5, FIRST.getY(), FIRST.getZ() + 0.5);
        MenuProvider provider = (MenuProvider) chest;
        if (outsider.openMenu(provider).isPresent()) {
            outsider.closeContainer();
            throw new AssertionError("Native pre-open hook exposed a private chest to an outsider");
        }
        if (owner.openMenu(provider).isEmpty()) {
            throw new AssertionError("Native menu fixture could not open for its owner");
        }
        owner.closeContainer();

        BlockPos controlPos = FIRST.offset(0, 0, 5);
        if (!level.getBlockState(controlPos).isAir()) {
            throw new AssertionError("Native menu positive-control fixture space occupied");
        }
        try {
            level.setBlockAndUpdate(controlPos, Blocks.CHEST.defaultBlockState());
            Container control = (Container) level.getBlockEntity(controlPos);
            if (outsider.openMenu((MenuProvider) control).isEmpty()) {
                throw new AssertionError("Unprotected native menu did not open");
            }
            var unprotectedMenu = outsider.containerMenu;
            unprotectedMenu.setCarried(new ItemStack(Items.EMERALD));
            var controlDeposit = new ServerboundContainerClickPacket(unprotectedMenu.containerId,
                    unprotectedMenu.getStateId(), (short) 1, (byte) 0, ContainerInput.PICKUP,
                    new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
            outsider.connection.handleContainerClick(controlDeposit);
            if (!control.getItem(1).is(Items.EMERALD) || !unprotectedMenu.getCarried().isEmpty()) {
                throw new AssertionError("Unprotected native packet did not deposit into chest");
            }
        } finally {
            outsider.closeContainer();
            level.setBlockAndUpdate(controlPos, Blocks.AIR.defaultBlockState());
        }

        protection.setType("deposit");
        runtime.protections().save(protection);
        try {
            if (outsider.openMenu(provider).isEmpty()) {
                throw new AssertionError("Deposit-only chest fixture did not open for outsider");
            }
            var active = outsider.containerMenu;
            if (chest.getItem(0).isEmpty()) {
                throw new AssertionError("Withdrawal fixture item disappeared before packet click");
            }
            var packet = new ServerboundContainerClickPacket(active.containerId, active.getStateId(),
                    (short) 0, (byte) 0, ContainerInput.PICKUP,
                    new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
            outsider.connection.handleContainerClick(packet);
            if (chest.getItem(0).isEmpty() || !active.getCarried().isEmpty()) {
                throw new AssertionError("Native menu packet withdrew from deposit-only chest");
            }
            active.setCarried(new ItemStack(Items.EMERALD));
            var deposit = new ServerboundContainerClickPacket(active.containerId, active.getStateId(),
                    (short) 1, (byte) 0, ContainerInput.PICKUP,
                    new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
            outsider.connection.handleContainerClick(deposit);
            if (!chest.getItem(1).is(Items.EMERALD) || !active.getCarried().isEmpty()) {
                throw new AssertionError("Native menu packet could not deposit into deposit-only chest");
            }
        } finally {
            outsider.closeContainer();
            protection.setType(runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
        }
    }


    private static void checkAutoEntity(ServerLevel level, BoltRuntime runtime, FakePlayer owner) {
        BlockPos base = new BlockPos(4200, 250, 4215);
        if (!level.getBlockState(base).isAir() || !level.getBlockState(base.above()).isAir()) {
            throw new AssertionError("Armor stand placement fixture occupied");
        }
        AABB area = new AABB(base).inflate(2);
        if (!level.getEntitiesOfClass(ArmorStand.class, area).isEmpty()) {
            throw new AssertionError("Armor stand placement fixture contains an existing entity");
        }
        level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
        ArmorStand placed = null;
        ArmorStandJoinCapture capture = new ArmorStandJoinCapture(area);
        NeoForge.EVENT_BUS.register(capture);
        try {
            owner.setPos(base.getX() + 0.5, base.getY() + 1, base.getZ() - 2.5);
            ItemStack stack = new ItemStack(Items.ARMOR_STAND);
            owner.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false);
            var result = owner.getItemInHand(InteractionHand.MAIN_HAND)
                    .useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, hit));
            placed = capture.placed;
            if (capture.count != 1 || capture.placed == null) {
                throw new AssertionError("Armor stand item did not spawn exactly one uncanceled entity"
                        + ": result=" + result + ", joinEvents=" + capture.count
                        + ", canceledEvents=" + capture.canceled);
            }
            EntityProtection protection = runtime.protections().find(placed);
            if (protection == null || !OWNER_ID.equals(protection.getOwner())) {
                throw new AssertionError("Placed armor stand was not automatically locked to its placer");
            }
        } finally {
            NeoForge.EVENT_BUS.unregister(capture);
            if (placed != null) {
                EntityProtection protection = runtime.protections().find(placed);
                if (protection != null) runtime.protections().remove(protection);
                placed.discard();
            }
            level.setBlockAndUpdate(base, Blocks.AIR.defaultBlockState());
        }
    }

    public static final class ArmorStandJoinCapture {
        private final AABB area;
        private ArmorStand placed;
        private int count;
        private int canceled;

        private ArmorStandJoinCapture(AABB area) { this.area = area; }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onJoin(EntityJoinLevelEvent event) {
            if (event.getEntity() instanceof ArmorStand stand && area.intersects(stand.getBoundingBox())) {
                if (event.isCanceled()) canceled++;
                else {
                    count++;
                    placed = stand;
                }
            }
        }
    }

    private static void checkEntity(ServerLevel level, BoltRuntime runtime,
                                    FakePlayer owner, FakePlayer outsider) {
        ArmorStand stand = new ArmorStand(level, 4200.5, 250.0, 4210.5);
        if (!level.addFreshEntity(stand)) throw new AssertionError("Could not spawn armor stand fixture");
        EntityProtection protection = null;
        try {
            var unprotectedOwnerAttack = new AttackEntityEvent(owner, stand);
            NeoForge.EVENT_BUS.post(unprotectedOwnerAttack);
            boolean otherModCanceledAttack = unprotectedOwnerAttack.isCanceled();
            if (otherModCanceledAttack) {
                System.out.println("BOLT_ATTACK_EVENT_INCONCLUSIVE: unprotected owner attack canceled by another listener");
            }
            var unprotectedOwnerDamage = new EntityInvulnerabilityCheckEvent(
                    stand, level.damageSources().playerAttack(owner), false);
            NeoForge.EVENT_BUS.post(unprotectedOwnerDamage);
            boolean otherModMadeInvulnerable = unprotectedOwnerDamage.isInvulnerable();
            if (otherModMadeInvulnerable) {
                System.out.println("BOLT_DAMAGE_EVENT_INCONCLUSIVE: unprotected owner damage marked invulnerable");
            }
            protection = runtime.protections().create(stand, OWNER_ID,
                    runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
            expect("owner entity destroy policy", runtime.access().canAccess(protection, owner, Permission.DESTROY), true);
            expect("outsider entity destroy policy", runtime.access().canAccess(protection, outsider, Permission.DESTROY), false);
            var outsiderInteract = new PlayerInteractEvent.EntityInteract(
                    outsider, InteractionHand.MAIN_HAND, stand, Vec3.ZERO);
            NeoForge.EVENT_BUS.post(outsiderInteract);
            expect("outsider entity interact", outsiderInteract.isCanceled(), true);
            var ownerInteract = new PlayerInteractEvent.EntityInteract(
                    owner, InteractionHand.MAIN_HAND, stand, Vec3.ZERO);
            NeoForge.EVENT_BUS.post(ownerInteract);
            expect("owner entity interact", ownerInteract.isCanceled(), false);
            var outsiderAttack = new AttackEntityEvent(outsider, stand);
            NeoForge.EVENT_BUS.post(outsiderAttack);
            expect("outsider entity attack", outsiderAttack.isCanceled(), true);
            var ownerAttack = new AttackEntityEvent(owner, stand);
            NeoForge.EVENT_BUS.post(ownerAttack);
            expect("owner entity attack compared with unprotected control",
                    ownerAttack.isCanceled(), otherModCanceledAttack);
            var outsiderDamage = new EntityInvulnerabilityCheckEvent(
                    stand, level.damageSources().playerAttack(outsider), false);
            NeoForge.EVENT_BUS.post(outsiderDamage);
            expect("outsider entity damage", outsiderDamage.isInvulnerable(), true);
            var ownerDamage = new EntityInvulnerabilityCheckEvent(
                    stand, level.damageSources().playerAttack(owner), false);
            NeoForge.EVENT_BUS.post(ownerDamage);
            expect("owner entity damage compared with unprotected control",
                    ownerDamage.isInvulnerable(), otherModMadeInvulnerable);
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_HELMET));
            stand.interact(outsider, InteractionHand.MAIN_HAND, new Vec3(0, 1.6, 0));
            if (!stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)
                    || !outsider.getItemInHand(InteractionHand.MAIN_HAND).is(Items.NETHERITE_HELMET)) {
                throw new AssertionError("Outsider swapped a locked armor stand item");
            }
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HELMET));
            stand.interact(owner, InteractionHand.MAIN_HAND, new Vec3(0, 1.6, 0));
            if (!stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)) {
                throw new AssertionError("Owner could not swap armor stand equipment");
            }
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            stand.discard();
        }
    }

    private static void checkHopperTransfer(ServerLevel level, BoltRuntime runtime) {
        BlockPos sourcePos = new BlockPos(4200, 250, 4205);
        BlockPos controlPos = new BlockPos(4203, 250, 4205);
        BlockPos deniedPos = new BlockPos(4206, 250, 4205);
        for (BlockPos pos : new BlockPos[] {sourcePos, controlPos, deniedPos}) {
            if (!level.getBlockState(pos).isAir()) throw new AssertionError("Hopper fixture occupied at " + pos);
        }
        BlockProtection protection = null;
        try {
            for (BlockPos pos : new BlockPos[] {sourcePos, controlPos, deniedPos}) {
                level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            }
            Container source = (Container) level.getBlockEntity(sourcePos);
            Container control = (Container) level.getBlockEntity(controlPos);
            Container denied = (Container) level.getBlockEntity(deniedPos);
            ItemStack controlRemainder = HopperBlockEntity.addItem(source, control,
                    new ItemStack(Items.DIAMOND), Direction.DOWN);
            if (!controlRemainder.isEmpty() || !control.getItem(0).is(Items.DIAMOND)) {
                throw new AssertionError("Unprotected native hopper transfer did not insert its item");
            }
            protection = runtime.protections().create(level, deniedPos, OWNER_ID,
                    runtime.settings().defaultProtectionType());
            runtime.protections().save(protection);
            ItemStack deniedRemainder = HopperBlockEntity.addItem(source, denied,
                    new ItemStack(Items.EMERALD), Direction.DOWN);
            if (deniedRemainder.getCount() != 1 || !denied.getItem(0).isEmpty()) {
                throw new AssertionError("Native hopper transfer inserted into a locked chest");
            }
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            for (BlockPos pos : new BlockPos[] {sourcePos, controlPos, deniedPos}) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean rightClick(FakePlayer player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    private static boolean breakBlock(ServerLevel level, FakePlayer player, BlockPos pos) {
        var event = new BreakBlockEvent(level, pos, level.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    private static void expect(String description, boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }
}
