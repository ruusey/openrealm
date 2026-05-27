package com.openrealm.net.server.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableLong;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)13)
public class UsePortalPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long portalId;
	@SerializableField(order = 1, type = SerializableLong.class)
    private long fromRealmId;
	@SerializableField(order = 2, type = SerializableByte.class)
    private byte toVault;
	@SerializableField(order = 3, type = SerializableByte.class)
    private byte toNexus;

    public static UsePortalPacket from(long portalId, long fromRealmId) throws Exception {
    	final UsePortalPacket packet = new UsePortalPacket(portalId, fromRealmId, (byte)-1, (byte)-1);
        return packet;
    }

    public static UsePortalPacket toNexus(long fromRealmId) throws Exception {
    	final UsePortalPacket packet = new UsePortalPacket(-1, fromRealmId, (byte)-1, (byte)1);
        return packet;
    }

    public static UsePortalPacket toVault(long fromRealmId) throws Exception {
    	final UsePortalPacket packet = new UsePortalPacket(-1, fromRealmId, (byte)1, (byte)-1);
        return packet;
    }
    
    public boolean isToNexus() {
        return this.toNexus != (byte) -1;
    }

    public boolean isToVault() {
        return this.toVault != (byte) -1;
    }
}
