package com.openrealm.net.server.packet;

import java.util.Arrays;
import java.util.List;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableBoolean;
import com.openrealm.net.core.nettypes.SerializableByte;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte)12)
public class MoveItemPacket extends Packet {
	// Phase 1B (combat rework) shifted equipment from 4 to 5 slots. All
	// downstream index regions follow: backpack 5..20, ground loot 21..28,
	// potion-storage slots at 29/30. Native + web clients must mirror these
	// exactly — they're serialized as raw bytes over the wire.
	private static final List<Integer> EQUIPMENT_IDX = Arrays.asList(0, 1, 2, 3, 4);
	private static final List<Integer> INV_IDX1 = Arrays.asList(5, 6, 7, 8, 9, 10, 11, 12);
	private static final List<Integer> INV_IDX2 = Arrays.asList(13, 14, 15, 16, 17, 18, 19, 20);
	private static final List<Integer> GROUND_LOOT_IDX = Arrays.asList(21, 22, 23, 24, 25, 26, 27, 28);
	public static final int HP_POTION_SLOT = 29;
	public static final int MP_POTION_SLOT = 30;

	@SerializableField(order = 0, type = SerializableByte.class)
	private byte targetSlotIndex;
	@SerializableField(order = 1, type = SerializableByte.class)
	private byte fromSlotIndex;
	@SerializableField(order = 2, type = SerializableBoolean.class)
	private boolean drop;
	@SerializableField(order = 3, type = SerializableBoolean.class)
	private boolean consume;

	public static MoveItemPacket from(byte targetSlot, byte fromSlot, boolean drop, boolean consume)
			throws Exception {
		MoveItemPacket packet = new MoveItemPacket(targetSlot, fromSlot, drop, consume);
		return packet;
	}

	public static boolean isInv1(int index) {
		return INV_IDX1.contains(index);
	}

	public static boolean isInv2(int index) {
		return INV_IDX2.contains(index);
	}

	public static boolean isInventory(int index) {
		return INV_IDX1.contains(index) || INV_IDX2.contains(index);
	}

	public static boolean isEquipment(int index) {
		return EQUIPMENT_IDX.contains(index);
	}

	public static boolean isGroundLoot(int index) {
		return GROUND_LOOT_IDX.contains(index);
	}

	/** First ground-loot slot index. Subtract from a fromSlot to get loot-array index. */
	public static int groundLootBase() {
		return GROUND_LOOT_IDX.get(0);
	}
}
