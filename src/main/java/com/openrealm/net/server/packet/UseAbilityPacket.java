package com.openrealm.net.server.packet;

import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableFloat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)11)
public class UseAbilityPacket extends Packet {
	@SerializableField(order = 0, type = SerializableFloat.class)
	private float posX;
	@SerializableField(order = 1, type = SerializableFloat.class)
	private float posY;
	@SerializableField(order = 2, type = SerializableByte.class)
	private byte abilityIndex;

	public static UseAbilityPacket from(Player player, Vector2f pos) throws Exception {
		return new UseAbilityPacket(pos.x, pos.y, (byte) 0);
	}

	public static UseAbilityPacket from(Player player, Vector2f pos, int abilityIndex) throws Exception {
		return new UseAbilityPacket(pos.x, pos.y, (byte) abilityIndex);
	}
}
