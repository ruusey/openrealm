package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Streamable
@NoArgsConstructor
@PacketId(packetId=(byte)20)
public class DeathAckPacket extends Packet{
    public static DeathAckPacket from() throws Exception {
        return new DeathAckPacket();
    }
}
