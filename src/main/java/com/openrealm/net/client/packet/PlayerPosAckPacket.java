package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server -> Client: acknowledges the last processed input sequence and sends
 * the server's authoritative position. The client uses this to reconcile
 * its predicted position by replaying unacknowledged inputs from the
 * server's position. Sent every tick for the local player only.
 * Total wire size: 5 (header) + 4 (seq) + 4 (posX) + 4 (posY) = 17 bytes.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte) 26)
public class PlayerPosAckPacket extends Packet {
    @SerializableField(order = 0, type = SerializableInt.class)
    private int seq;
    @SerializableField(order = 1, type = SerializableFloat.class)
    private float posX;
    @SerializableField(order = 2, type = SerializableFloat.class)
    private float posY;

    public static PlayerPosAckPacket from(int seq, float posX, float posY) {
        return new PlayerPosAckPacket(seq, posX, posY);
    }
}
