package com.openrealm.net.entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Damage;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableBoolean;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Streamable
public class NetGameItem extends SerializableFieldType<NetGameItem> {
	@SerializableField(order = 0, type = SerializableInt.class)
	private int itemId;
	@SerializableField(order = 1, type = SerializableString.class)
	private String uid;
	@SerializableField(order = 2, type = SerializableString.class)
	private String name;
	@SerializableField(order = 3, type = SerializableString.class)
	private String description;
	@SerializableField(order = 4, type = NetStats.class)
	private NetStats stats;
	@SerializableField(order = 5, type = NetDamage.class)
	private NetDamage damage;
	@SerializableField(order = 6, type = NetEffect.class)
	private NetEffect effect;
	@SerializableField(order = 7, type = SerializableBoolean.class)
	private boolean consumable;
	@SerializableField(order = 8, type = SerializableByte.class)
	private byte tier;
	@SerializableField(order = 9, type = SerializableByte.class)
	private byte targetSlot;
	@SerializableField(order = 10, type = SerializableByte.class)
	private byte targetClass;
	@SerializableField(order = 11, type = SerializableByte.class)
	private byte fameBonus;
	@SerializableField(order = 12, type = SerializableBoolean.class)
	private boolean stackable;
	@SerializableField(order = 13, type = SerializableInt.class)
	private int maxStack;
	@SerializableField(order = 14, type = SerializableInt.class)
	private int stackCount;
	@SerializableField(order = 15, type = SerializableString.class)
	private String category;
	@SerializableField(order = 16, type = SerializableByte.class)
	private byte forgeStatId;
	@SerializableField(order = 17, type = SerializableByte.class)
	private byte forgeSlotId;
	private List<NetEnchantment> enchantments;
	// Appended fields (wire layout extended for rarity + modifiers + gem template).
	private byte rarity;
	private List<NetAttributeModifier> attributeModifiers;
	private byte gemEffectType;
	private byte gemParam1;
	private short gemMagnitude;
	private int gemDurationMs;

	private static final NetStats STATS_SERIALIZER = new NetStats();
	private static final NetDamage DAMAGE_SERIALIZER = new NetDamage();
	private static final NetEffect EFFECT_SERIALIZER = new NetEffect();
	private static final NetEnchantment ENCHANTMENT_SERIALIZER = new NetEnchantment();
	private static final NetAttributeModifier MODIFIER_SERIALIZER = new NetAttributeModifier();

	private static int writeString(String s, DataOutputStream stream) throws Exception {
		if (s == null) s = "";
		final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		stream.writeInt(bytes.length);
		stream.write(bytes);
		return 4 + bytes.length;
	}

	private static String readString(DataInputStream stream) throws Exception {
		final int len = stream.readInt();
		if (len <= 0) return "";
		final byte[] bytes = new byte[len];
		stream.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** Hand-coded write bypassing IOService reflection */
	@Override
	public int write(NetGameItem value, DataOutputStream stream) throws Exception {
		final NetGameItem v = (value == null ? new NetGameItem() : value);
		int written = 0;
		stream.writeInt(v.itemId); written += 4;
		written += writeString(v.uid, stream);
		written += writeString(v.name, stream);
		written += writeString(v.description, stream);
		written += STATS_SERIALIZER.write(v.stats, stream);
		written += DAMAGE_SERIALIZER.write(v.damage, stream);
		written += EFFECT_SERIALIZER.write(v.effect, stream);
		stream.writeBoolean(v.consumable); written += 1;
		stream.writeByte(v.tier); written += 1;
		stream.writeByte(v.targetSlot); written += 1;
		stream.writeByte(v.targetClass); written += 1;
		stream.writeByte(v.fameBonus); written += 1;
		stream.writeBoolean(v.stackable); written += 1;
		stream.writeInt(v.maxStack); written += 4;
		stream.writeInt(v.stackCount); written += 4;
		written += writeString(v.category, stream);
		stream.writeByte(v.forgeStatId); written += 1;
		stream.writeByte(v.forgeSlotId); written += 1;
		// Enchantment list: int count + N × NetEnchantment
		final List<NetEnchantment> ench = v.enchantments == null ? new ArrayList<>() : v.enchantments;
		stream.writeInt(ench.size()); written += 4;
		for (NetEnchantment e : ench) {
			written += ENCHANTMENT_SERIALIZER.write(e, stream);
		}
		// Appended block (always written): rarity, attribute modifiers, gem template fields
		stream.writeByte(v.rarity); written += 1;
		final List<NetAttributeModifier> mods = v.attributeModifiers == null ? new ArrayList<>() : v.attributeModifiers;
		stream.writeInt(mods.size()); written += 4;
		for (NetAttributeModifier m : mods) {
			written += MODIFIER_SERIALIZER.write(m, stream);
		}
		stream.writeByte(v.gemEffectType); written += 1;
		stream.writeByte(v.gemParam1); written += 1;
		stream.writeShort(v.gemMagnitude); written += 2;
		stream.writeInt(v.gemDurationMs); written += 4;
		return written;
	}

	/** Hand-coded read bypassing IOService reflection */
	@Override
	public NetGameItem read(DataInputStream stream) throws Exception {
		final NetGameItem item = new NetGameItem();
		item.itemId = stream.readInt();
		item.uid = readString(stream);
		item.name = readString(stream);
		item.description = readString(stream);
		item.stats = STATS_SERIALIZER.read(stream);
		item.damage = DAMAGE_SERIALIZER.read(stream);
		item.effect = EFFECT_SERIALIZER.read(stream);
		item.consumable = stream.readBoolean();
		item.tier = stream.readByte();
		item.targetSlot = stream.readByte();
		item.targetClass = stream.readByte();
		item.fameBonus = stream.readByte();
		item.stackable = stream.readBoolean();
		item.maxStack = stream.readInt();
		item.stackCount = stream.readInt();
		item.category = readString(stream);
		item.forgeStatId = stream.readByte();
		item.forgeSlotId = stream.readByte();
		final int enchCount = stream.readInt();
		item.enchantments = new ArrayList<>(Math.max(0, enchCount));
		for (int i = 0; i < enchCount; i++) {
			item.enchantments.add(ENCHANTMENT_SERIALIZER.read(stream));
		}
		// Appended block — rarity + modifiers + gem template
		item.rarity = stream.readByte();
		final int modCount = stream.readInt();
		item.attributeModifiers = new ArrayList<>(Math.max(0, modCount));
		for (int i = 0; i < modCount; i++) {
			item.attributeModifiers.add(MODIFIER_SERIALIZER.read(stream));
		}
		item.gemEffectType = stream.readByte();
		item.gemParam1 = stream.readByte();
		item.gemMagnitude = stream.readShort();
		item.gemDurationMs = stream.readInt();
		return item;
	}

	public GameItem asGameItem() {
		GameItem item = new GameItem();
		item.setItemId(this.itemId);
		item.setUid(this.uid);
		item.setName(this.name);
		item.setDescription(this.description);
		item.setStats(IOService.mapModel(this.stats, Stats.class));
		item.setDamage(IOService.mapModel(this.damage, Damage.class));
		item.setEffect(IOService.mapModel(this.stats, Effect.class));
		item.setConsumable(this.consumable);
		item.setTier(this.tier);
		item.setTargetSlot(this.targetSlot);
		item.setTargetClass(this.targetClass);
		item.setFameBonus(this.fameBonus);
		item.setStackable(this.stackable);
		item.setMaxStack(this.maxStack);
		item.setStackCount(this.stackCount);
		item.setCategory(this.category);
		item.setForgeStatId(this.forgeStatId);
		item.setForgeSlotId(this.forgeSlotId);
		item.setRarity(this.rarity);
		item.setGemEffectType(this.gemEffectType);
		item.setGemParam1(this.gemParam1);
		item.setGemMagnitude(this.gemMagnitude);
		item.setGemDurationMs(this.gemDurationMs);
		if (this.enchantments != null && !this.enchantments.isEmpty()) {
			final List<Enchantment> out = new ArrayList<>(this.enchantments.size());
			for (NetEnchantment ne : this.enchantments) {
				out.add(new Enchantment(ne.getStatId(), ne.getDeltaValue(), ne.getPixelX(), ne.getPixelY(),
						ne.getPixelColor(), ne.getEffectType(), ne.getParam1(), ne.getMagnitude(), ne.getDurationMs()));
			}
			item.setEnchantments(out);
		} else {
			item.setEnchantments(new ArrayList<>());
		}
		if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
			final List<AttributeModifier> out = new ArrayList<>(this.attributeModifiers.size());
			for (NetAttributeModifier nm : this.attributeModifiers) {
				out.add(new AttributeModifier(nm.getStatId(), nm.getDeltaValue()));
			}
			item.setAttributeModifiers(out);
		} else {
			item.setAttributeModifiers(new ArrayList<>());
		}
		return item;
	}

	/**
	 * Value equality based on itemId and uid. Two NetGameItems representing the
	 * same game item instance will be equal even if they are different objects.
	 */
	public boolean equals(NetGameItem other) {
		if (other == null) return false;
		if (this.itemId != other.itemId) return false;
		if (this.uid == null && other.uid == null) return true;
		if (this.uid == null || other.uid == null) return false;
		return this.uid.equals(other.uid);
	}

	public NetGameItem() {
		this.itemId = -1;
		this.uid = "";
		this.name = "";
		this.description = "";
		this.stats = new NetStats();
		this.damage = new NetDamage();
		this.effect = new NetEffect();
		this.consumable = false;
		this.tier = -2;
		this.targetSlot = -1;
		this.targetClass = -1;
		this.fameBonus = -1;
		this.stackable = false;
		this.maxStack = 1;
		this.stackCount = 1;
		this.category = "generic";
		this.forgeStatId = -1;
		this.forgeSlotId = -1;
		this.enchantments = new ArrayList<>();
		this.rarity = 0;
		this.attributeModifiers = new ArrayList<>();
		this.gemEffectType = -1;
		this.gemParam1 = 0;
		this.gemMagnitude = 0;
		this.gemDurationMs = 0;
	}
}
