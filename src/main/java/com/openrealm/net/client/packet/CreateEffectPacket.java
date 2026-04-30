package com.openrealm.net.client.packet;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableFloat;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Server-to-client packet instructing the client to render a visual particle effect.
 * Effect types:
 *   0 = HEAL_RADIUS (shimmering green particles, expanding ring)
 *   1 = VAMPIRISM (inward-sucking purple/red particles)
 *   2 = STASIS_FIELD (frozen blue/white ring with crystalline particles)
 *   3 = CHAIN_LIGHTNING (electric arc from posX,posY to targetPosX,targetPosY)
 *   4 = CURSE_RADIUS (dark swirling particles)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@Builder
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)21)
public class CreateEffectPacket extends Packet {
	@SerializableField(order = 0, type = SerializableShort.class)
	private short effectType;
	@SerializableField(order = 1, type = SerializableFloat.class)
	private float posX;
	@SerializableField(order = 2, type = SerializableFloat.class)
	private float posY;
	@SerializableField(order = 3, type = SerializableFloat.class)
	private float radius;
	@SerializableField(order = 4, type = SerializableShort.class)
	private short duration;
	@SerializableField(order = 5, type = SerializableFloat.class)
	private float targetPosX;
	@SerializableField(order = 6, type = SerializableFloat.class)
	private float targetPosY;
	// Item tier (0–6 for tiered loot, 0 for non-tiered visuals). Client uses
	// this to recolor the effect — higher tiers get hotter / rarer hues so
	// players can read the power level of a teammate's ability at a glance.
	@SerializableField(order = 7, type = SerializableByte.class)
	private byte tier;

	// Visual effect type constants
	public static final short EFFECT_HEAL_RADIUS = 0;
	public static final short EFFECT_VAMPIRISM = 1;
	public static final short EFFECT_STASIS_FIELD = 2;
	public static final short EFFECT_CHAIN_LIGHTNING = 3;
	public static final short EFFECT_CURSE_RADIUS = 4;
	public static final short EFFECT_POISON_SPLASH = 5;
	public static final short EFFECT_TRAP_THROW = 6;
	public static final short EFFECT_TRAP_PLACED = 7;
	public static final short EFFECT_TRAP_TRIGGER = 8;
	// Class-ability cast visuals (played at the caster's position on use).
	public static final short EFFECT_SMOKE_POOF = 9;       // Rogue cloak
	public static final short EFFECT_WIZARD_BURST = 10;    // Wizard spell
	public static final short EFFECT_KNIGHT_SHOCKWAVE = 11;// Knight shield
	public static final short EFFECT_WARRIOR_BUFF = 12;    // Warrior helm
	public static final short EFFECT_NINJA_DASH = 13;      // Ninja dash trail
	public static final short EFFECT_PALADIN_SEAL = 14;    // Paladin holy cross

	public static CreateEffectPacket aoeEffect(short type, float x, float y, float radius, short duration) {
		return aoeEffect(type, x, y, radius, duration, (byte) 0);
	}

	public static CreateEffectPacket aoeEffect(short type, float x, float y, float radius, short duration, byte tier) {
		return CreateEffectPacket.builder()
			.effectType(type).posX(x).posY(y).radius(radius)
			.duration(duration).targetPosX(0).targetPosY(0).tier(tier).build();
	}

	public static CreateEffectPacket lineEffect(short type, float fromX, float fromY, float toX, float toY, short duration) {
		return lineEffect(type, fromX, fromY, toX, toY, duration, (byte) 0);
	}

	public static CreateEffectPacket lineEffect(short type, float fromX, float fromY, float toX, float toY, short duration, byte tier) {
		return CreateEffectPacket.builder()
			.effectType(type).posX(fromX).posY(fromY).radius(0)
			.duration(duration).targetPosX(toX).targetPosY(toY).tier(tier).build();
	}
}
