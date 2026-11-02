package org.popcraft.bolt.neoforge.testmod.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.protection.ProtectionMenus;
import org.popcraft.bolt.neoforge.testmod.protection.ProbeServerPlayer;
import org.popcraft.bolt.protection.BlockProtection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Exercises DQM's actual server menu creation against persisted Bolt ownership. */
public final class DqmContainerFixtures {
    private static final BlockPos MATERIAL = new BlockPos(8400, 250, 8400);
    private static final BlockPos HUGE = new BlockPos(8404, 250, 8400);
    private static final UUID OWNER = UUID.fromString("0e5db835-f7d0-4bbf-bbe0-8dcb01a560db");
    private static final UUID OTHER = UUID.fromString("23bb6746-25b5-48a0-83a0-e1bb76533b74");

    private DqmContainerFixtures() { }

    public static void run(ServerLevel level) {
        if (!ModList.get().isLoaded("dqmvii")) {
            System.out.println("BOLT_DQM_CONTAINER_TESTS_NOT_APPLICABLE");
            return;
        }
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) throw new AssertionError("Bolt runtime unavailable for DQM container fixtures");
        Block materialBlock = registeredBlock("dqmvii:build_material_chest");
        Block hugeBlock = registeredBlock("dqmvii:legacy_block_chestb7");
        ProbeServerPlayer outsider = new ProbeServerPlayer(level, new GameProfile(OTHER, "bolt_dqm_outsider"));
        Map<BlockPos, BlockState> before = new HashMap<>();
        try {
            place(level, before, MATERIAL, materialBlock);
            place(level, before, HUGE, hugeBlock);
            testMaterialMenu(runtime, level, outsider);
            testHugeMenu(runtime, level, outsider);
            System.out.println("BOLT_DQM_CONTAINER_TESTS_PASSED");
        } finally {
            outsider.closeContainer();
            for (BlockPos pos : before.keySet()) {
                BlockProtection lock = runtime.protections().find(level, pos);
                if (lock != null) runtime.protections().remove(lock);
            }
            before.forEach(level::setBlockAndUpdate);
        }
    }

    private static void testMaterialMenu(BoltRuntime runtime, ServerLevel level,
                                         ProbeServerPlayer outsider) {
        outsider.setPos(MATERIAL.getX() + 0.5, MATERIAL.getY(), MATERIAL.getZ() + 0.5);
        openMaterial(outsider, MATERIAL);
        AbstractContainerMenu control = outsider.containerMenu;
        if (control == outsider.inventoryMenu)
            throw new AssertionError("DQM material chest control did not open its menu");
        lock(runtime, level, MATERIAL);
        if (ProtectionMenus.mayClick(runtime, outsider, control, 1, ContainerInput.QUICK_MOVE))
            throw new AssertionError("Open DQM material chest still permits withdrawal after lock");
        outsider.closeContainer();
        openMaterial(outsider, MATERIAL);
        if (outsider.containerMenu != outsider.inventoryMenu)
            throw new AssertionError("Protected DQM material chest opened for an unrelated player");
    }

    private static void testHugeMenu(BoltRuntime runtime, ServerLevel level,
                                     ProbeServerPlayer outsider) {
        outsider.setPos(HUGE.getX() + 0.5, HUGE.getY(), HUGE.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(HUGE), Direction.UP, HUGE, false);
        outsider.gameMode.useItemOn(outsider, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        AbstractContainerMenu control = outsider.containerMenu;
        if (control == outsider.inventoryMenu)
            throw new AssertionError("DQM huge chest control did not open its menu");
        lock(runtime, level, HUGE);
        if (ProtectionMenus.mayClick(runtime, outsider, control, 1, ContainerInput.QUICK_MOVE))
            throw new AssertionError("Open DQM huge chest still permits withdrawal after lock");
        outsider.closeContainer();
        if (outsider.openMenu(hugeMenuProvider(level, HUGE)).isPresent()) {
            outsider.closeContainer();
            throw new AssertionError("Protected DQM huge chest menu opened for an unrelated player");
        }
    }

    private static void openMaterial(ServerPlayer player, BlockPos pos) {
        try {
            Class.forName("com.dqm.DqmvBuildMaterialChestMenu")
                    .getMethod("open", ServerPlayer.class, BlockPos.class).invoke(null, player, pos);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("DQM material chest public open failed", failure);
        }
    }

    private static SimpleMenuProvider hugeMenuProvider(ServerLevel level, BlockPos pos) {
        try {
            BlockEntity chest = level.getBlockEntity(pos);
            if (chest == null || !chest.getClass().getName().equals("com.dqm.DqmvHugeChestBlockEntity"))
                throw new AssertionError("DQM huge chest block entity unavailable");
            Object bag = chest.getClass().getMethod("storage").invoke(chest);
            UUID storageId = (UUID) chest.getClass().getMethod("storageId").invoke(chest);
            if (bag == null) throw new AssertionError("DQM huge chest storage unavailable");
            Class<?> menuClass = Class.forName("com.dqm.DqmvLargeBagMenu");
            Constructor<?> create = menuClass.getConstructor(int.class, Inventory.class, UUID.class,
                    Class.forName("com.dqm.DqmvLargeBagStorage$BagInventory"), boolean.class);
            Method fromBlock = menuClass.getMethod("openedFromBlock", Level.class, BlockPos.class);
            MenuConstructor factory = (id, inventory, player) -> {
                try {
                    AbstractContainerMenu menu = (AbstractContainerMenu) create.newInstance(
                            id, inventory, storageId, bag, true);
                    fromBlock.invoke(menu, level, pos);
                    return menu;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("DQM huge chest public menu factory failed", failure);
                }
            };
            return new SimpleMenuProvider(factory, Component.literal("DQM Huge Chest"));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("DQM huge chest public menu factory unavailable", failure);
        }
    }

    private static Block registeredBlock(String id) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        if (block == null || block == Blocks.AIR)
            throw new AssertionError("DQM block missing at registry ID " + id);
        return block;
    }

    private static void place(ServerLevel level, Map<BlockPos, BlockState> before,
                              BlockPos pos, Block block) {
        if (runtimeLock(level, pos) != null) throw new AssertionError("DQM fixture location already locked: " + pos);
        BlockState old = level.getBlockState(pos);
        if (!old.isAir()) throw new AssertionError("DQM fixture location occupied: " + pos);
        before.put(pos, old);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    private static BlockProtection runtimeLock(ServerLevel level, BlockPos pos) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        return runtime == null ? null : runtime.protections().find(level, pos);
    }

    private static void lock(BoltRuntime runtime, ServerLevel level, BlockPos pos) {
        runtime.protections().save(runtime.protections().create(level, pos, OWNER,
                runtime.settings().defaultProtectionType()));
        if (runtime.protections().find(level, pos) == null)
            throw new AssertionError("DQM fixture lock was not saved: " + pos);
    }
}
