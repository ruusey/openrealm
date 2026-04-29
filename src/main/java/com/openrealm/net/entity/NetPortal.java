package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.entity.Portal;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetPortal extends SerializableFieldType<NetPortal> {
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableShort.class)
	private short portalId;
	@SerializableField(order = 2, type = SerializableLong.class)
	private long fromRealmId;
	@SerializableField(order = 3, type = SerializableLong.class)
	private long toRealmId;
	@SerializableField(order = 4, type = SerializableLong.class)
	private long expires;
	@SerializableField(order = 5, type = Vector2f.class)
	private Vector2f pos;
	
	/** Hand-rolled construction from Portal — bypasses ModelMapper reflection. */
	public static NetPortal fromPortal(Portal p) {
		final NetPortal n = new NetPortal();
		n.id = p.getId();
		n.portalId = (short) p.getPortalId();
		n.fromRealmId = p.getFromRealmId();
		n.toRealmId = p.getToRealmId();
		n.expires = p.getExpires();
		n.pos = p.getPos();
		return n;
	}

	public Portal asPortal() {
		Portal p = new Portal();
		p.setId(this.getId());
		p.setPortalId(this.getPortalId());
		p.setFromRealmId(this.getFromRealmId());
		p.setToRealmId(this.getToRealmId());
		p.setExpires(this.getExpires());
		p.setPos(this.getPos());
		
		return p;
		
	}

}
