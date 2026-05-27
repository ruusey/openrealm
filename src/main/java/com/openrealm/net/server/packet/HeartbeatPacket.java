package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte)5)
public class HeartbeatPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long timestamp;

    public static HeartbeatPacket from(long timestamp) throws Exception {
        return new HeartbeatPacket(timestamp);
    }
}
