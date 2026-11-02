package org.popcraft.bolt.neoforge.testmod.protection;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Isolated packet-capable server player with a no-output connection. */
public final class ProbeServerPlayer extends ServerPlayer {
    public ProbeServerPlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        connection = new ProbePacketListener(level.getServer(), new ProbeConnection(), this,
                CommonListenerCookie.createInitial(profile, false));
    }

    private static final class ProbePacketListener extends ServerGamePacketListenerImpl {
        ProbePacketListener(MinecraftServer server, net.minecraft.network.Connection connection,
                            ServerPlayer player, CommonListenerCookie cookie) {
            super(server, connection, player, cookie);
        }

        @Override
        public void send(Packet<?> packet) { }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) { }
    }

    private static final class ProbeConnection extends net.minecraft.network.Connection {
        ProbeConnection() {
            super(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(this);
        }

        @Override
        public void send(Packet<?> packet) { }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) { }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { }
    }
}
