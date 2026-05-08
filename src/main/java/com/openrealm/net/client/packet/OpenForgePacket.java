package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Server -> client acknowledgement that the player can open their Forge UI.
 * Sent in response to InteractTilePacket on a tile whose interactionType == "forge".
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte) 29)
public class OpenForgePacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
}
