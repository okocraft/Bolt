package org.popcraft.bolt.neoforge.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import org.popcraft.bolt.Bolt;
import org.popcraft.bolt.data.*;
import org.popcraft.bolt.neoforge.*;
import org.popcraft.bolt.neoforge.api.*;
import org.popcraft.bolt.protection.*;
import java.util.*;

public final class ProtectionService {
    private final Bolt bolt;
    private final BoltSettings settings;
    private final AccessService access;
    private final NeoForgePermissions permissions;
    private final SQLStore persistent;
    public ProtectionService(Bolt bolt, BoltSettings settings, AccessService access, NeoForgePermissions permissions, SQLStore persistent) {
        this.bolt=bolt; this.settings=settings; this.access=access; this.permissions=permissions; this.persistent=persistent;
    }
    public BlockProtection find(ServerLevel level, BlockPos pos) {
        for (var linked : ProtectionTargets.positions(level,pos)) {
            var protection = bolt.getStore().loadBlockProtection(new org.popcraft.bolt.util.BlockLocation(level.dimension().identifier().toString(),linked.getX(),linked.getY(),linked.getZ())).join();
            if (protection != null) return protection;
        }
        return null;
    }
    public EntityProtection find(Entity entity) { return bolt.getStore().loadEntityProtection(entity.getUUID()).join(); }
    public boolean isProtectable(ServerLevel level,BlockPos pos) { return settings.isProtectable(level,pos); }
    public boolean isProtectable(Entity entity) { return settings.isProtectable(entity); }
    public Collection<BlockProtection> blocks() { return List.copyOf(bolt.getStore().loadBlockProtections().join()); }
    public Collection<EntityProtection> entities() { return List.copyOf(bolt.getStore().loadEntityProtections().join()); }
    public BlockProtection create(ServerLevel level,BlockPos pos,UUID owner,String type) {
        long now=System.currentTimeMillis();
        return new BlockProtection(UUID.randomUUID(),owner,type,now,now,new HashMap<>(),level.dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ(),BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
    }
    public EntityProtection create(Entity entity,UUID owner,String type) {
        long now=System.currentTimeMillis();
        return new EntityProtection(entity.getUUID(),owner,type,now,now,new HashMap<>(),BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }
    public void save(Protection protection) {
        if (protection instanceof BlockProtection block) bolt.getStore().saveBlockProtection(block);
        else if (protection instanceof EntityProtection entity) bolt.getStore().saveEntityProtection(entity);
    }
    public void remove(Protection protection) {
        for (var request:bolt.getStore().loadPendingTransfers().join()) if (request.protection().equals(protection.getId())) bolt.getStore().removePendingTransfer(request);
        if (protection instanceof BlockProtection block) bolt.getStore().removeBlockProtection(block);
        else if (protection instanceof EntityProtection entity) bolt.getStore().removeEntityProtection(entity);
    }
    public BlockProtection lock(ServerPlayer player,ServerLevel level,BlockPos pos,String type) { return lockBlock(player,level,pos,type,false); }
    public BlockProtection lock(ServerPlayer player,ServerLevel level,BlockPos pos,String type,boolean force) {
        if (force && !permissions.has(player,"bolt.admin",permissions.operator(player))) return null;
        return lockBlock(player,level,pos,type,force);
    }
    public BlockProtection lockBeaconBase(ServerPlayer player,ServerLevel level,BlockPos pos,String type) {
        if (!level.getBlockState(pos).is(BlockTags.BEACON_BASE_BLOCKS) || !permissions.has(player,"boltbeaconprotection.autoprotect",false)) return null;
        return lockBlock(player,level,pos,type,true);
    }
    private BlockProtection lockBlock(ServerPlayer player,ServerLevel level,BlockPos pos,String type,boolean allowUnconfigured) {
        if (find(level,pos)!=null || (!allowUnconfigured && !isProtectable(level,pos)) || !validType(player,type)) return null;
        var protection=create(level,pos,player.getUUID(),type);
        var event=new LockBlockEvent(player,level,pos,protection);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return null;
        save(protection); return protection;
    }
    public EntityProtection lock(ServerPlayer player,Entity entity,String type) { return lock(player,entity,type,false); }
    public EntityProtection lock(ServerPlayer player,Entity entity,String type,boolean force) {
        if (force && !permissions.has(player,"bolt.admin",permissions.operator(player))) return null;
        if (find(entity)!=null || (!force && !isProtectable(entity)) || !validType(player,type)) return null;
        var protection=create(entity,player.getUUID(),type);
        var event=new LockEntityEvent(player,entity,protection); NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return null;
        save(protection); return protection;
    }
    private boolean validType(ServerPlayer player,String type) {
        var configured=bolt.getAccessRegistry().getProtectionByType(type).orElse(null);
        return configured!=null && (!configured.restricted() || permissions.has(player,"bolt.type.protection."+type,false));
    }
    public boolean unlock(ServerPlayer player,Protection protection) {
        if (protection==null || !access.canAccess(protection,player,org.popcraft.bolt.util.Permission.DESTROY)) return false;
        remove(protection); return true;
    }
    public void reload() { bolt.getStore().flush().join(); bolt.setStore(new CachedStore(persistent)); }
}
