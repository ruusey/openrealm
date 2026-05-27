package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableInt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte) 28)
public class InteractTilePacket extends Packet {
    @SerializableField(order = 0, type = SerializableInt.class)
    private int tileX;
    @SerializableField(order = 1, type = SerializableInt.class)
    private int tileY;
}
