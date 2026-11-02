package org.popcraft.bolt.neoforge.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.integration.mixin.BeaconMenuAccess;

/** Lock a confirmed beacon's base as the player who selected its effect. */
public final class BoltBeaconIntegration {
    private BoltBeaconIntegration() { }

    public static void effectsConfirmed(ServerPlayer player, BeaconMenu menu) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null || !runtime.permissions().has(player, "boltbeaconprotection.autoprotect", false)) return;
        ((BeaconMenuAccess) menu).bolt$access().execute((world, beaconPos) -> {
            if (!(world instanceof ServerLevel level)
                    || !(level.getBlockEntity(beaconPos) instanceof BeaconBlockEntity)) return;
            int count = protectBase(runtime, player, level, beaconPos, menu.getLevels());
            if (count > 0) {
                String message = player.clientInformation().language().startsWith("ja")
                        ? count + "個のビーコンの土台ブロックを保護しました。"
                        : "Protected " + count + " base blocks of the beacon.";
                player.sendSystemMessage(Component.literal(message));
            }
        });
    }

    static int protectBase(BoltRuntime runtime, ServerPlayer player, ServerLevel level,
                           BlockPos beaconPos, int tier) {
        int count = 0;
        for (int depth = 1; depth <= Math.min(tier, 4); depth++) {
            int y = beaconPos.getY() - depth;
            if (y < level.getMinY()) break;
            for (int x = beaconPos.getX() - depth; x <= beaconPos.getX() + depth; x++) {
                for (int z = beaconPos.getZ() - depth; z <= beaconPos.getZ() + depth; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).is(BlockTags.BEACON_BASE_BLOCKS)
                            || runtime.protections().find(level, pos) != null) continue;
                    if (runtime.protections().lockBeaconBase(player, level, pos,
                            runtime.settings().defaultProtectionType()) != null) count++;
                }
            }
        }
        return count;
    }
}
