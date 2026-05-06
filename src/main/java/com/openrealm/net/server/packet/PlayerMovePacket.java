package com.openrealm.net.server.packet;

import com.openrealm.game.entity.Player;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Player movement input packet.
 *
 * Carries a 2D unit-vector (vx, vy) describing the world-space direction the
 * client is asking the player to move. Replaces the previous 4-bit {@code dirFlags}
 * which could only express 8 directions and snapped diagonally — the camera
 * rotation feature needs continuous angles.
 *
 * Convention: (0, 0) = not moving. Otherwise the magnitude SHOULD be 1 (the
 * server scales by per-tick speed). Server tolerates non-unit input by treating
 * it as the requested velocity direction.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)1)
public class PlayerMovePacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long entityId;
	@SerializableField(order = 1, type = SerializableInt.class)
    private int seq;
	@SerializableField(order = 2, type = SerializableFloat.class)
    private float vx;
	@SerializableField(order = 3, type = SerializableFloat.class)
    private float vy;

    public static PlayerMovePacket from(Player player, int seq, float vx, float vy) throws Exception {
    	final PlayerMovePacket read = new PlayerMovePacket(player.getId(), seq, vx, vy);
        return read;
    }
}
