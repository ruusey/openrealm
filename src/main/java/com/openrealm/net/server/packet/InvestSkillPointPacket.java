package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Client → server: spend one skill point on the ability bound to hotbar
 * {@code slot}. Server validates the player has at least one available point
 * and the ability is not already at its per-ability cap, persists the new
 * level, and echoes the updated pool + per-slot invested counts back via
 * {@code UpdatePacket}.
 *
 * Locked-once-spent — no refund packet.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte) 40)
public class InvestSkillPointPacket extends Packet {
    /** Hotbar slot (0..3) the player wants to invest in. Server resolves the
     *  ability id from the player's current binding. */
    @SerializableField(order = 0, type = SerializableByte.class)
    private byte slot;
}
