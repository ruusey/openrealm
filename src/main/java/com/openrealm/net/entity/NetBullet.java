package com.openrealm.net.entity;

import java.util.Arrays;
import java.util.List;

import com.openrealm.game.entity.Bullet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.*;


import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetBullet extends SerializableFieldType<NetBullet> {
	
	@SerializableField(order = 0, type = SerializableLong.class)
	private long id;
	@SerializableField(order = 1, type = SerializableInt.class)
	private int projectileId;
	@SerializableField(order = 2, type = SerializableShort.class)
	private short size;
	@SerializableField(order = 3, type = Vector2f.class)
	private Vector2f pos;
	@SerializableField(order = 4, type = SerializableFloat.class)
	private float dX;
	@SerializableField(order = 5, type = SerializableFloat.class)
	private float dY;
	@SerializableField(order = 6, type = SerializableFloat.class)
	private float angle;
	@SerializableField(order = 7, type = SerializableFloat.class)
	private float magnitude;
	@SerializableField(order = 8, type = SerializableFloat.class)
	private float range;
	@SerializableField(order = 9, type = SerializableShort.class)
	private short damage;
	@SerializableField(order = 10, type = SerializableShort.class, isCollection=true)
	private Short[] flags;
	@SerializableField(order = 11, type = SerializableBoolean.class)
	private boolean invert;
	@SerializableField(order = 12, type = SerializableLong.class)
	private long timeStep;
	@SerializableField(order = 13, type = SerializableShort.class)
	private short amplitude;
	@SerializableField(order = 14, type = SerializableShort.class)
	private short frequency;
	@SerializableField(order = 15, type = SerializableLong.class)
	private long createdTime;
	@SerializableField(order = 16, type = SerializableFloat.class)
	private float orbitCenterX;
	@SerializableField(order = 17, type = SerializableFloat.class)
	private float orbitCenterY;
	@SerializableField(order = 18, type = SerializableFloat.class)
	private float orbitRadius;
	@SerializableField(order = 19, type = SerializableFloat.class)
	private float orbitPhase;

	/**
	 * Hand-rolled construction from a server-side Bullet — bypasses
	 * ModelMapper reflection. ModelMapper.map() walks all 20 fields via
	 * reflection per call; with ~200 visible bullets × 11 viewers × 32Hz
	 * that's 70K reflective maps/sec, which was eating significant CPU
	 * during ability spam and contributing to the TPS drop.
	 *
	 * Direct field copy is 10-100× faster than reflection-based mapping.
	 */
	public static NetBullet fromBullet(Bullet b) {
		final NetBullet n = new NetBullet();
		n.id = b.getId();
		n.projectileId = b.getProjectileId();
		n.size = (short) b.getSize();
		n.pos = b.getPos();
		n.dX = b.getDx();
		n.dY = b.getDy();
		n.angle = b.getAngle();
		n.magnitude = b.getMagnitude();
		n.range = b.getRange();
		n.damage = b.getDamage();
		final List<Short> bf = b.getFlags();
		if (bf != null && !bf.isEmpty()) {
			n.flags = bf.toArray(new Short[0]);
		} else {
			n.flags = new Short[0];
		}
		n.invert = b.isInvert();
		n.timeStep = b.getTimeStep();
		n.amplitude = b.getAmplitude();
		n.frequency = b.getFrequency();
		n.createdTime = b.getCreatedTime();
		n.orbitCenterX = b.getOrbitCenterX();
		n.orbitCenterY = b.getOrbitCenterY();
		n.orbitRadius = b.getOrbitRadius();
		n.orbitPhase = b.getOrbitPhase();
		return n;
	}

	public Bullet asBullet() {
		final Bullet bullet = new Bullet();
		bullet.setId(this.id);
		bullet.setProjectileId(this.projectileId);
		bullet.setSize(this.size);
		bullet.setPos(this.pos);
		bullet.setDx(this.dX);
		bullet.setDy(this.dY);
		bullet.setAngle(this.angle);
		bullet.setMagnitude(this.magnitude);
		bullet.setRange(this.range);
		bullet.setDamage(this.damage);
		bullet.setFlags(Arrays.asList(this.flags));
		bullet.setInvert(this.invert);
		bullet.setTimeStep(this.timeStep);
		bullet.setAmplitude(this.amplitude);
		bullet.setFrequency(this.frequency);
		bullet.setCreatedTime(this.createdTime);
		return bullet;
	}
}
