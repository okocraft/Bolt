package org.popcraft.bolt.neoforge.api;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.popcraft.bolt.protection.BlockProtection;

public final class LockBlockEvent extends Event implements ICancellableEvent {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockProtection protection;
    public LockBlockEvent(ServerPlayer player,ServerLevel level,BlockPos pos,BlockProtection protection) {
        this.player=player; this.level=level; this.pos=pos.immutable(); this.protection=protection;
    }
    public ServerPlayer getPlayer() { return player; }
    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockProtection getProtection() { return protection; }
}
