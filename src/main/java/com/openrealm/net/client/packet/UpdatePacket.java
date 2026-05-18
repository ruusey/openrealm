package com.openrealm.net.client.packet;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableByte;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableString;
import com.openrealm.net.entity.NetAttributeModifier;
import com.openrealm.net.entity.NetEnchantment;
import com.openrealm.net.entity.NetGameItem;
import com.openrealm.net.entity.NetStats;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Heavy update packet for inventory, stats, XP, and player name changes.
 * HP/MP and status effects are now sent via the lighter PlayerStatePacket.
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Streamable
@NoArgsConstructor
@PacketId(packetId = (byte)2)

public class UpdatePacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
	private long playerId;
	@SerializableField(order = 1, type = SerializableString.class)
	private String playerName;
	@SerializableField(order = 2, type = NetStats.class)
	private NetStats stats;
	@SerializableField(order = 3, type = SerializableInt.class)
	private int health;
	@SerializableField(order = 4, type = SerializableInt.class)
	private int mana;
	@SerializableField(order = 5, type = SerializableLong.class)
	private long experience;
	@SerializableField(order = 6, type = NetGameItem.class, isCollection = true)
	private NetGameItem[] inventory;
	@SerializableField(order = 7, type = SerializableByte.class)
	private byte hpPotions;
	@SerializableField(order = 8, type = SerializableByte.class)
	private byte mpPotions;
	// Cosmetic dye id (0 = none). Carried on UpdatePacket so a dye consumption
	// reflects on the dyer's renderer instantly. Other players pick up the
	// change via NetPlayer in LoadPacket on next re-load.
	@SerializableField(order = 9, type = SerializableInt.class)
	private int dyeId;
	// Phase 2D — skill point pool + per-slot invested counts. We send 4 bytes
	// (one per hotbar slot 0..3) and an int for the unspent pool. The client
	// uses these to render the orange "level pips" next to each ability cell
	// and to surface Skill Level X/Y inside the ability tooltip.
	@SerializableField(order = 10, type = SerializableInt.class)
	private int availableSkillPoints;
	@SerializableField(order = 11, type = SerializableByte.class)
	private byte investedSlot0;
	@SerializableField(order = 12, type = SerializableByte.class)
	private byte investedSlot1;
	@SerializableField(order = 13, type = SerializableByte.class)
	private byte investedSlot2;
	@SerializableField(order = 14, type = SerializableByte.class)
	private byte investedSlot3;

	public static final NetGameItem[] EMPTY_INVENTORY = new NetGameItem[0];

	/**
	 * Returns true if inventory differs between this and other packet.
	 */
	public boolean inventoryChanged(UpdatePacket other) {
		if (other == null) return true;
		if (this.inventory.length != other.getInventory().length) return true;
		for (int i = 0; i < this.inventory.length; i++) {
			if ((this.inventory[i] != null) && (other.getInventory()[i] != null)) {
				if (!this.inventory[i].equals(other.getInventory()[i])) return true;
			} else if (!((this.inventory[i] == null) && (other.getInventory()[i] == null))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns a lightweight copy with empty inventory.
	 * Used for other-player updates where clients don't need inventory.
	 */
	public UpdatePacket withoutInventory() {
		final UpdatePacket light = new UpdatePacket();
		light.setPlayerId(this.playerId);
		light.setPlayerName(this.playerName);
		light.setStats(this.stats);
		light.setHealth(this.health);
		light.setMana(this.mana);
		light.setExperience(this.experience);
		light.setInventory(EMPTY_INVENTORY);
		light.setHpPotions(this.hpPotions);
		light.setMpPotions(this.mpPotions);
		light.setDyeId(this.dyeId);
		return light;
	}

	/**
	 * Build a stripped UpdatePacket directly from a Player WITHOUT mapping
	 * the inventory. Used for the broadcast of nearby-other-player updates,
	 * which strips inventory before sending anyway. The old path went
	 * UpdatePacket.from(player) -> withoutInventory(), which paid for a full
	 * 20-slot inventory ModelMapper reflection round just to throw the
	 * result away. Skipping the inventory map saves ~50× per call.
	 */
	public static UpdatePacket fromPlayerWithoutInventory(Player player) {
		if (player == null) return null;
		final UpdatePacket light = new UpdatePacket();
		light.setPlayerId(player.getId());
		light.setPlayerName(player.getName());
		// getComputedStats() so the wire reflects active aura/buff modifiers
		// (PROTECTED +5 VIT, EMPOWERED +STR/DEX, BRACED x1.5 DEF, etc.). Raw
		// stats are server-side bookkeeping for level-up math; the client
		// renders what the player effectively HAS right now.
		light.setStats(NetStats.fromStats(player.getComputedStats()));
		light.setHealth(player.getHealth());
		light.setMana(player.getMana());
		light.setExperience(player.getExperience());
		// Include the 5 EQUIPMENT slots (0-4) so other clients can render
		// the player's gear in the hover tooltip / inspect panel. Backpack
		// (slots 5-20) is still stripped — that's what the bandwidth
		// optimization actually targeted, and there's no UI surface that
		// shows another player's backpack contents anyway.
		light.setInventory(toEquipmentOnly(player.getInventory()));
		light.setHpPotions((byte) player.getHpPotions());
		light.setMpPotions((byte) player.getMpPotions());
		light.setDyeId(player.getDyeId());
		return light;
	}

	/**
	 * Returns a 5-slot NetGameItem[] populated from the player's
	 * inventory[0..4] (the equipment slots — weapon/armor/gauntlets/boots/ring).
	 * Empty/null source slots map to null entries; out-of-bounds source returns
	 * an all-null 5-slot array.
	 */
	private static NetGameItem[] toEquipmentOnly(GameItem[] inventory) {
		final int n = Player.EQUIPMENT_SLOT_COUNT;
		final NetGameItem[] out = new NetGameItem[n];
		if (inventory == null) return out;
		for (int i = 0; i < n && i < inventory.length; i++) {
			out[i] = toNetGameItem(inventory[i]);
		}
		return out;
	}

	public static UpdatePacket from(Enemy enemy) throws Exception {
		if (enemy == null)
			return null;

		final UpdatePacket updatePacket = new UpdatePacket();
		updatePacket.setPlayerId(enemy.getId());
		updatePacket.setHealth(enemy.getHealth());
		updatePacket.setMana(enemy.getMana());
		updatePacket.setPlayerName("enemy[" + enemy.getId() + "]");
		updatePacket.setStats(NetStats.fromStats(enemy.getStats()));
		updatePacket.setInventory(EMPTY_INVENTORY);
		updatePacket.setExperience(0l);
		return updatePacket;
	}

	public static UpdatePacket from(Player player) throws Exception {
		if (player == null)
			return null;

		final UpdatePacket updatePacket = new UpdatePacket();
		updatePacket.setPlayerId(player.getId());
		updatePacket.setHealth(player.getHealth());
		updatePacket.setMana(player.getMana());
		updatePacket.setPlayerName(player.getName());
		// Use hand-rolled fromStats() instead of IOService.mapModel — same
		// reflection-avoidance reason as in LoadPacket.from().
		// getComputedStats() so active buffs/auras (EMPOWERED, PROTECTED,
		// BRACED, etc.) show up in the client's stat panel. Raw stats stay
		// server-side for level-up + persistence math.
		updatePacket.setStats(NetStats.fromStats(player.getComputedStats()));
		// Build inventory explicitly to guarantee enchantments + stack counts +
		// forge metadata are preserved (ModelMapper can drop nested generics).
		updatePacket.setInventory(toNetInventory(player.getInventory()));
		updatePacket.setExperience(player.getExperience());
		updatePacket.setHpPotions((byte) player.getHpPotions());
		updatePacket.setMpPotions((byte) player.getMpPotions());
		updatePacket.setDyeId(player.getDyeId());
		// Phase 2D — skill point pool + per-slot invested counts.
		updatePacket.setAvailableSkillPoints(player.getAvailableSkillPoints());
		updatePacket.setInvestedSlot0((byte) player.getSkillLevel(player.getHotbarId(0)));
		updatePacket.setInvestedSlot1((byte) player.getSkillLevel(player.getHotbarId(1)));
		updatePacket.setInvestedSlot2((byte) player.getSkillLevel(player.getHotbarId(2)));
		updatePacket.setInvestedSlot3((byte) player.getSkillLevel(player.getHotbarId(3)));
		return updatePacket;
	}

	private static NetGameItem[] toNetInventory(GameItem[] inventory) {
		if (inventory == null) return new NetGameItem[0];
		final NetGameItem[] out = new NetGameItem[inventory.length];
		for (int i = 0; i < inventory.length; i++) {
			out[i] = toNetGameItem(inventory[i]);
		}
		return out;
	}

	private static NetGameItem toNetGameItem(GameItem item) {
		if (item == null) return new NetGameItem();
		final NetGameItem net = IOService.mapModel(item, NetGameItem.class);
		net.setStackable(item.isStackable());
		net.setMaxStack(item.getMaxStack());
		net.setStackCount(item.getStackCount());
		net.setCategory(item.getCategory());
		net.setForgeStatId(item.getForgeStatId());
		net.setForgeSlotId(item.getForgeSlotId());
		net.setRarity(item.getRarity());
		net.setGemEffectType(item.getGemEffectType());
		net.setGemParam1(item.getGemParam1());
		net.setGemMagnitude(item.getGemMagnitude());
		net.setGemDurationMs(item.getGemDurationMs());
		final List<NetEnchantment> ench;
		if (item.getEnchantments() != null && !item.getEnchantments().isEmpty()) {
			ench = new ArrayList<>(item.getEnchantments().size());
			for (Enchantment e : item.getEnchantments()) {
				ench.add(new NetEnchantment(e.getStatId(), e.getDeltaValue(), e.getPixelX(), e.getPixelY(),
						e.getPixelColor(), e.getEffectType(), e.getParam1(), e.getMagnitude(), e.getDurationMs()));
			}
		} else {
			ench = new ArrayList<>();
		}
		net.setEnchantments(ench);
		final List<NetAttributeModifier> mods;
		if (item.getAttributeModifiers() != null && !item.getAttributeModifiers().isEmpty()) {
			mods = new ArrayList<>(item.getAttributeModifiers().size());
			for (AttributeModifier m : item.getAttributeModifiers()) {
				mods.add(new NetAttributeModifier(m.getStatId(), m.getDeltaValue()));
			}
		} else {
			mods = new ArrayList<>();
		}
		net.setAttributeModifiers(mods);
		return net;
	}

	/**
	 * Compare UpdatePacket fields (inventory, stats, XP, name).
	 * HP/MP are included for backward compat but the primary delta
	 * is inventory + stats + experience.
	 */
	public boolean equals(UpdatePacket other, boolean thinMatch) {
		if(other==null) return false;
		// HP/MP are handled by PlayerStatePacket — don't compare them here
		boolean basic = (this.playerId == other.getPlayerId()) && this.playerName.equals(other.getPlayerName());

		boolean stats = this.stats.equals(other.getStats());
		if (thinMatch)
			stats = true;
		boolean inv = true;
		for (int i = 0; i < this.inventory.length; i++) {
			if ((this.inventory[i] != null) && (other.getInventory()[i] != null)) {
				if (this.inventory[i].equals(other.getInventory()[i])) {
					continue;
				}
				inv = false;
				break;
			}
			if ((this.inventory[i] == null) && (other.getInventory()[i] == null)) {
				continue;
			}
			inv = false;
			break;
		}
		if (thinMatch)
			inv = true;

		boolean expEqual = this.experience == other.getExperience();
		boolean potionsEqual = this.hpPotions == other.getHpPotions()
				&& this.mpPotions == other.getMpPotions();
		// Include dyeId so a dye consumption is detected as a change and the
		// updated cosmetic is broadcast to nearby viewers.
		boolean dyeEqual = this.dyeId == other.getDyeId();
		boolean result = basic && stats && inv && expEqual && potionsEqual && dyeEqual;

		return result;
	}

	public boolean equals(UpdatePacket other) {
		return this.equals(other, false);
	}

}
