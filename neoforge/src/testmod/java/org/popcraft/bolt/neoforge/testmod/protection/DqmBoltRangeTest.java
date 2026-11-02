package org.popcraft.bolt.neoforge.testmod.protection;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.util.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Actual DQM creative range action, with an unprotected effect as positive control. */
final class DqmBoltRangeTest {
    private static final Identifier TOOL = Identifier.parse("dqmvii:legacy_item_kc_magic_tool");
    private static final BlockPos CONTROL = new BlockPos(4210, 250, 4200);
    private static final BlockPos PROTECTED = new BlockPos(4220, 250, 4200);
    private static final UUID PLAYER_ID = UUID.fromString("75d49820-429c-43dc-95ba-a443c31ff03d");
    private static final UUID OWNER_ID = UUID.fromString("d8e6cb41-c923-43b2-b4d0-5f532a75a530");

    private DqmBoltRangeTest() { }

    static void run(ServerLevel level) {
        var runtime = BoltNeoForge.runtime();
        if (runtime == null) throw new AssertionError("DQM_BOLT_INCONCLUSIVE: Bolt runtime absent");
        Item tool = BuiltInRegistries.ITEM.getValue(TOOL);
        if (tool == null || !TOOL.equals(BuiltInRegistries.ITEM.getKey(tool))) {
            throw new AssertionError("DQM_BOLT_INCONCLUSIVE: DQM range tool absent");
        }
        List<BlockPos> controlCells = cells(CONTROL);
        List<BlockPos> protectedCells = cells(PROTECTED);
        for (BlockPos pos : controlCells) requireEmpty(level, pos);
        for (BlockPos pos : protectedCells) requireEmpty(level, pos);
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(PLAYER_ID, "bolt_dqm_range"));
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        if (!player.isCreative() || player.isSpectator()) {
            throw new AssertionError("DQM_BOLT_INCONCLUSIVE: creative player unavailable");
        }
        BlockProtection protection = null;
        try {
            fill(level, controlCells);
            fill(level, protectedCells);
            level.setBlockAndUpdate(CONTROL.east(), Blocks.OAK_FENCE_GATE.defaultBlockState());
            level.setBlockAndUpdate(PROTECTED.east(), Blocks.OAK_FENCE_GATE.defaultBlockState());
            if (!runtime.protections().isProtectable(level, PROTECTED.east())) {
                throw new AssertionError("DQM_BOLT_INCONCLUSIVE: fence gate is not protectable");
            }
            use(level, player, tool, CONTROL);
            boolean controlRemoved = level.getBlockState(CONTROL.east()).isAir();
            System.out.println("DQM_BOLT_CONTROL gateRemoved=" + controlRemoved);
            if (!controlRemoved) {
                throw new AssertionError("DQM_BOLT_INCONCLUSIVE: unprotected range action did not remove gate");
            }

            protection = runtime.protections().create(level, PROTECTED.east(), OWNER_ID, "display");
            runtime.protections().save(protection);
            if (!runtime.access().canAccess(protection, player, Permission.INTERACT)
                    || runtime.access().canAccess(protection, player, Permission.DESTROY)) {
                throw new AssertionError("DQM_BOLT_INCONCLUSIVE: display access must allow interact and deny destroy");
            }
            use(level, player, tool, PROTECTED);
            boolean protectedRemoved = level.getBlockState(PROTECTED.east()).isAir();
            System.out.println("DQM_BOLT_PROTECTED gateRemoved=" + protectedRemoved);
            if (protectedRemoved) {
                throw new AssertionError("DQM_BOLT_PROTECTION_FAILED: range action removed locked gate");
            }
            System.out.println("DQM_BOLT_RANGE_PROTECTION_PASSED");
        } finally {
            if (protection != null) runtime.protections().remove(protection);
            clear(level, controlCells);
            clear(level, protectedCells);
        }
    }

    private static List<BlockPos> cells(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>(27);
        for (int x = -1; x <= 1; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
            result.add(origin.offset(x, y, z));
        }
        return result;
    }

    private static void requireEmpty(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) throw new AssertionError("DQM fixture occupied at " + pos);
    }

    private static void fill(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
    }

    private static void clear(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private static void use(ServerLevel level, FakePlayer player, Item item, BlockPos pos) {
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.setYRot(0.0f);
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var interact = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        NeoForge.EVENT_BUS.post(interact);
        if (!interact.isCanceled()) stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }
}
