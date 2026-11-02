package org.popcraft.bolt.neoforge.api;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.popcraft.bolt.protection.EntityProtection;

public final class LockEntityEvent extends Event implements ICancellableEvent {
    private final ServerPlayer player;
    private final Entity entity;
    private final EntityProtection protection;
    public LockEntityEvent(ServerPlayer player,Entity entity,EntityProtection protection) {
        this.player=player; this.entity=entity; this.protection=protection;
    }
    public ServerPlayer getPlayer() { return player; }
    public Entity getEntity() { return entity; }
    public EntityProtection getProtection() { return protection; }
}
