package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Streamable
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@PacketId(packetId = (byte) 22)
public class LoginAckPacket extends Packet {
    public static LoginAckPacket from() {
        return new LoginAckPacket();
    }
}
