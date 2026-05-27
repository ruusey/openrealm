package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
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
@PacketId(packetId = (byte) 30)
public class ForgeEnchantPacket extends Packet {
    @SerializableField(order = 0, type = SerializableByte.class)
    private byte targetItemSlot;
    @SerializableField(order = 1, type = SerializableInt.class)
    private int crystalItemId;
    @SerializableField(order = 2, type = SerializableByte.class)
    private byte crystalSlotIndex;
    @SerializableField(order = 3, type = SerializableByte.class)
    private byte essenceSlotIndex;
    @SerializableField(order = 4, type = SerializableByte.class)
    private byte pixelX;
    @SerializableField(order = 5, type = SerializableByte.class)
    private byte pixelY;
}
