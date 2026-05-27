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

/**
 * Client -> server. Request to split a stackable item in the player's
 * inventory: source slot keeps ceil(N/2), the floor(N/2) remainder is
 * placed into the first empty backpack slot. Server rejects if the
 * source isn't stackable, has stackCount < 2, or there's no empty slot
 * to drop the split into.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte) 37)
public class SplitStackPacket extends Packet {
    @SerializableField(order = 0, type = SerializableInt.class)
    private int fromSlot;
}
