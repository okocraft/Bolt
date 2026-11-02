package org.popcraft.bolt.neoforge.protection;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.popcraft.bolt.neoforge.BoltNeoForge;
import org.popcraft.bolt.neoforge.BoltRuntime;
import org.popcraft.bolt.neoforge.mixin.CompoundContainerAccessor;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceTypeResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.Permission;

import java.util.ArrayList;
import java.util.List;

/** Checks native inventory transport and environmental block mutation. */
public final class ProtectionAutomation {
    private static final SourceTypeResolver BLOCK_SOURCE = new SourceTypeResolver(Source.of(SourceTypes.BLOCK));
    private static final SourceTypeResolver ENTITY_SOURCE = new SourceTypeResolver(Source.of(SourceTypes.ENTITY));
    private static final SourceTypeResolver REDSTONE_SOURCE = new SourceTypeResolver(Source.of(SourceTypes.REDSTONE));

    private ProtectionAutomation() { }

    public static boolean mayTransfer(Container source, Container destination) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return true;
        List<BlockProtection> sources = protections(runtime, source);
        List<BlockProtection> destinations = protections(runtime, destination);
        if (source instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level
                && level.getBlockState(block.getBlockPos()).getBlock() instanceof DropperBlock) {
            for (BlockProtection protection : sources) {
                if (!mayActivateRedstone(runtime, protection)) return false;
            }
        }
        for (BlockProtection from : sources) {
            if (destinations.isEmpty()) {
                if (!runtime.access().canAccess(from, BLOCK_SOURCE, Permission.WITHDRAW)) return false;
            } else for (BlockProtection to : destinations) {
                if (!runtime.access().canAccess(from, to.getOwner(), Permission.WITHDRAW)
                        || !runtime.access().canAccess(to, from.getOwner(), Permission.DEPOSIT)) return false;
            }
        }
        if (sources.isEmpty()) {
            for (BlockProtection to : destinations) {
                if (!runtime.access().canAccess(to, BLOCK_SOURCE, Permission.DEPOSIT)) return false;
            }
        }
        return true;
    }

    public static boolean mayCollectItem(Container destination) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return true;
        for (BlockProtection protection : protections(runtime, destination)) {
            if (!runtime.access().canAccess(protection, ENTITY_SOURCE, Permission.DEPOSIT)) return false;
        }
        return true;
    }

    public static boolean mayActivateRedstone(ServerLevel level, BlockPos pos) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        if (runtime == null) return true;
        BlockProtection protection = runtime.protections().find(level, pos);
        return protection == null || mayActivateRedstone(runtime, protection);
    }

    static boolean mayActivateRedstone(BoltRuntime runtime, BlockProtection protection) {
        boolean explicit = runtime.access().canAccess(protection, REDSTONE_SOURCE, Permission.REDSTONE);
        return redstoneAllowed(runtime.settings().redstoneDenyByDefault(), explicit);
    }

    static boolean redstoneAllowed(boolean denyByDefault, boolean explicitRedstoneAccess) {
        return !(denyByDefault ^ explicitRedstoneAccess);
    }

    public static boolean mayBurn(ServerLevel level, net.minecraft.core.BlockPos pos) {
        BoltRuntime runtime = BoltNeoForge.runtime();
        return runtime == null || runtime.protections().find(level, pos) == null;
    }

    private static List<BlockProtection> protections(BoltRuntime runtime, Container container) {
        if (container == null) return List.of();
        if (container instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level) {
            BlockProtection protection = runtime.protections().find(level, block.getBlockPos());
            return protection == null ? List.of() : List.of(protection);
        }
        if (container instanceof CompoundContainer combined) {
            CompoundContainerAccessor parts = (CompoundContainerAccessor) combined;
            List<BlockProtection> result = new ArrayList<>();
            result.addAll(protections(runtime, parts.bolt$first()));
            result.addAll(protections(runtime, parts.bolt$second()));
            return result;
        }
        return List.of();
    }
}
