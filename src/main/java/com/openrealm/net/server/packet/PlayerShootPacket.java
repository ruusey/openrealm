package com.openrealm.net.server.packet;

import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
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

@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@AllArgsConstructor
@PacketId(packetId = (byte)6)
public class PlayerShootPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long projectileId;
	@SerializableField(order = 1, type = SerializableInt.class)
    private int projectileGroupId;
	@SerializableField(order = 2, type = SerializableFloat.class)
    private float destX;
	@SerializableField(order = 3, type = SerializableFloat.class)
    private float destY;
	@SerializableField(order = 4, type = SerializableFloat.class)
    private float srcX;
	@SerializableField(order = 5, type = SerializableFloat.class)
    private float srcY;

    public static PlayerShootPacket from(long newProjectileId, Player p, Vector2f dest) throws Exception {
    	final PlayerShootPacket data = new PlayerShootPacket();
    	data.projectileId = newProjectileId;
    	data.projectileGroupId = p.getWeaponId();
    	if (dest == null) {
    		data.destX = -1;
    		data.destY = -1;
        } else {
            data.destX = dest.x;
            data.destY = dest.y;
        }
    	
    	data.srcX = p.getPos().x;
    	data.srcY = p.getPos().y;
    	return data;
    }
}
