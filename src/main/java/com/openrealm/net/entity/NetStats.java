package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import com.openrealm.game.entity.item.Stats;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@Streamable
@AllArgsConstructor
public class NetStats extends SerializableFieldType<NetStats> {
	@SerializableField(order = 0, type = SerializableInt.class)
	private int hp;
	@SerializableField(order = 1, type = SerializableShort.class)
	private short mp;
	@SerializableField(order = 2, type = SerializableShort.class)
	private short def;
	@SerializableField(order = 3, type = SerializableShort.class)
	private short str;
	@SerializableField(order = 4, type = SerializableShort.class)
	private short spd;
	@SerializableField(order = 5, type = SerializableShort.class)
	private short dex;
	@SerializableField(order = 6, type = SerializableShort.class)
	private short vit;
	@SerializableField(order = 7, type = SerializableShort.class)
	private short wis;

	public NetStats() {
		this.hp = 0;
		this.mp = 0;
		this.def = 0;
		this.str = 0;
		this.spd = 0;
		this.dex = 0;
		this.vit = 0;
		this.wis = 0;
	}

	/** Hand-coded write: 18 bytes (1 int + 7 shorts), bypasses reflection */
	@Override
	public int write(NetStats value, DataOutputStream stream) throws Exception {
		final NetStats v = value == null ? new NetStats() : value;
		stream.writeInt(v.hp);
		stream.writeShort(v.mp);
		stream.writeShort(v.def);
		stream.writeShort(v.str);
		stream.writeShort(v.spd);
		stream.writeShort(v.dex);
		stream.writeShort(v.vit);
		stream.writeShort(v.wis);
		return 18;
	}

	/** Hand-coded read: 18 bytes, bypasses reflection */
	@Override
	public NetStats read(DataInputStream stream) throws Exception {
		return new NetStats(
			stream.readInt(),
			stream.readShort(), stream.readShort(),
			stream.readShort(), stream.readShort(),
			stream.readShort(), stream.readShort(),
			stream.readShort()
		);
	}

	public static NetStats fromStats(Stats stats) {
		if (stats == null) return new NetStats();
		return new NetStats(
			stats.getHp(), (short) stats.getMp(),
			(short) stats.getDef(), (short) stats.getStr(),
			(short) stats.getSpd(), (short) stats.getDex(),
			(short) stats.getVit(), (short) stats.getWis()
		);
	}

	public Stats asStats() {
		return Stats.builder().hp(this.hp).mp(this.mp).def(this.def).str(this.str).spd(this.spd).dex(this.dex)
				.vit(this.vit).wis(this.wis).build();
	}

}
