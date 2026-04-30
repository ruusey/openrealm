package com.openrealm.game.entity.item;

import java.time.Instant;
import java.util.UUID;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.realm.Realm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LootContainer {

    private long lootContainerId;
    private LootTier tier;
    private String uid;
    private GameItem[] items;
    private Vector2f pos;

    private long spawnedTime;

    private boolean contentsChanged;

    // Soulbound loot: -1 means public (anyone can see/pickup),
    // otherwise only the player with this ID can see/interact with this bag
    @Builder.Default
    private long soulboundPlayerId = -1;
    public LootContainer(LootTier tier, Vector2f pos) {
        this.tier = tier;
        this.uid = UUID.randomUUID().toString();
        this.items = new GameItem[8];
        this.pos = pos;
        this.items[0] = GameDataManager.GAME_ITEMS.get(Realm.RANDOM.nextInt(8));
        for (int i = 1; i < (Realm.RANDOM.nextInt(7) + 1); i++) {
            this.items[i] = GameDataManager.GAME_ITEMS.get(Realm.RANDOM.nextInt(152) + 1);
        }
        this.spawnedTime = System.currentTimeMillis();
        this.tier = this.determineTier();
    }

    public boolean getContentsChanged() {
        return this.contentsChanged;
    }

    public LootContainer(LootTier tier, Vector2f pos, GameItem loot) {
        this.tier = tier;
        this.pos = pos;
        this.uid = UUID.randomUUID().toString();
        this.items = new GameItem[8];
        this.items[0] = loot;
        this.spawnedTime = Instant.now().toEpochMilli();
        this.tier = this.determineTier();
    }

    public LootContainer(LootTier tier, Vector2f pos, GameItem[] loot) {
        this.tier = tier;
        this.pos = pos;
        this.uid = UUID.randomUUID().toString();
        this.items = new GameItem[8];
        int slot = 0;
        for (GameItem item : loot) {
            if (item != null && slot < 8) {
                this.items[slot++] = item;
            }
        }
        this.spawnedTime = Instant.now().toEpochMilli();
        this.tier = this.determineTier();
    }

    public boolean isExpired() {
        return (Instant.now().toEpochMilli() - this.spawnedTime) > 45000;
    }

    public boolean isEmpty() {
        for (GameItem item : this.items) {
            if (item != null)
                return false;
        }
        return true;
    }

    public boolean hasUntieredItem() {
        if (this.tier.equals(LootTier.CHEST) || this.tier.equals(LootTier.GRAVE))
            return false;
        for (GameItem item : this.items) {
            if ((item != null) && (item.getTier() == (byte) -1))
                return true;
        }
        return false;
    }

    /**
     * Determines bag tier based on item contents.
     * CYAN bag: top 4 weapon tiers, top 2 ability tiers, top 4 armor tiers
     * PURPLE bag: all other tiered equipment
     * WHITE bag: untiered items (tier == -1)
     * BROWN bag: consumables only (always public, never soulbound)
     */
    public LootTier determineTier() {
        if (this.tier.equals(LootTier.CHEST) || this.tier.equals(LootTier.GRAVE)
                || this.tier.equals(LootTier.BOOSTED))
            return this.tier;

        boolean hasUntiered = false;
        boolean hasCyanTier = false;
        boolean hasPurpleTier = false;
        boolean hasPotion = false;
        boolean hasAnyItem = false;

        for (GameItem item : this.items) {
            if (item == null) continue;
            hasAnyItem = true;
            byte t = item.getTier();
            byte slot = item.getTargetSlot();
            final String cat = item.getCategory();
            final boolean isForgeMaterial = "crystal".equals(cat)
                    || "essence".equals(cat)
                    || "shard".equals(cat);
            if (item.isConsumable()) {
                hasPotion = true;
            } else if (isForgeMaterial) {
                hasPurpleTier = true;
            } else if (t == (byte) -1) {
                hasUntiered = true;
            } else if (isCyanTierItem(slot, t)) {
                hasCyanTier = true;
            } else if (t >= 0) {
                hasPurpleTier = true;
            }
        }

        if (!hasAnyItem) return LootTier.BROWN;
        if (hasUntiered) return LootTier.WHITE;
        if (hasCyanTier) return LootTier.CYAN;
        if (hasPurpleTier) return LootTier.PURPLE;
        if (hasPotion) return LootTier.BLUE;
        return LootTier.BROWN;
    }

    /**
     * Checks if an item qualifies for CYAN bag based on slot and tier.
     * Weapons (slot 0): top 4 tiers (>= CYAN_BAG_MIN_WEAPON_TIER)
     * Abilities (slot 1): top 2 tiers (>= CYAN_BAG_MIN_ABILITY_TIER)
     * Armors (slot 2): top 4 tiers (>= CYAN_BAG_MIN_ARMOR_TIER)
     * Rings (slot 3) and others: follow the old logic (tier >= 8)
     */
    private boolean isCyanTierItem(byte slot, byte tier) {
        if (tier < 0) return false; // untiered items don't count here
        switch (slot) {
            case 0: // Weapon
                return tier >= GlobalConstants.CYAN_BAG_MIN_WEAPON_TIER;
            case 1: // Ability
                return tier >= GlobalConstants.CYAN_BAG_MIN_ABILITY_TIER;
            case 2: // Armor
                return tier >= GlobalConstants.CYAN_BAG_MIN_ARMOR_TIER;
            default: // Rings (slot 3) and other items: tier >= 8
                return tier >= 8;
        }
    }

    /**
     * Returns true if this bag is public (anyone can see/interact).
     */
    public boolean isPublicLoot() {
        return this.soulboundPlayerId == -1;
    }

    /**
     * Returns true if the given player can see and interact with this bag.
     */
    public boolean isVisibleToPlayer(long playerId) {
        return this.soulboundPlayerId == -1 || this.soulboundPlayerId == playerId;
    }

    public void setItems(GameItem[] items) {
        this.items = items;
        this.contentsChanged = true;
    }

    public void repackItems() {
        GameItem[] packed = new GameItem[8];
        int slot = 0;
        for (GameItem item : this.items) {
            if (item != null && slot < 8) {
                packed[slot++] = item;
            }
        }
        this.items = packed;
        this.contentsChanged = true;
    }

    public void setItem(int idx, GameItem replacement) {
        this.items[idx] = replacement;
        this.contentsChanged = true;
    }

    public int getFirstNullIdx() {
        int idx = -1;
        for (int i = 0; i < this.items.length; i++) {
            if (this.items[i] == null) {
                idx = i;
                return idx;
            }
        }
        return idx;
    }

    public int getNonEmptySlotCount() {
        int count = 0;
        for (GameItem s : this.getItems()) {
            if (s != null) {
                count++;
            }
        }
        return count;
    }

    public boolean equals(LootContainer other) {
    	final boolean basic = (this.lootContainerId == other.getLootContainerId()) && this.pos.equals(other.getPos());
    	final boolean tierMatch = this.getTier().equals(other.getTier());
    	boolean loot = true;
        for (int i = 0; i < 8; i++) {
            final GameItem a = this.items[i];
            final GameItem b = other.getItems()[i];
            if (a == null && b == null) continue;
            if (a == null || b == null || !a.equals(b)) {
                loot = false;
                break;
            }
        }
        return basic && loot && tierMatch;
    }
}
