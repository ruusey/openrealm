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

/**
 * Client -> server. Move/swap/merge an item between the player's inventory
 * (side=0) and their potion-storage container[0] (side=1). Server validates
 * whitelist + bounds + stack semantics, then broadcasts both an inventory
 * UpdatePacket and a PotionStorageUpdatePacket back to the player.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte) 36)
public class PotionStorageMovePacket extends Packet {
    public static final byte SIDE_INV = (byte) 0;
    public static final byte SIDE_STORAGE = (byte) 1;

    @SerializableField(order = 0, type = SerializableByte.class)
    private byte fromSide;
    @SerializableField(order = 1, type = SerializableInt.class)
    private int fromIdx;
    @SerializableField(order = 2, type = SerializableByte.class)
    private byte toSide;
    @SerializableField(order = 3, type = SerializableInt.class)
    private int toIdx;
}
