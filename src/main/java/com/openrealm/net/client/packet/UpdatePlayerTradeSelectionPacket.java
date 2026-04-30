package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.entity.NetInventorySelection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Streamable
@Data
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)18)
public class UpdatePlayerTradeSelectionPacket extends Packet {
	@SerializableField(order = 0, type = NetInventorySelection.class)
	private NetInventorySelection selection;
}
