package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.realm.Realm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LootTableModel {
    private Integer enemyId;
    private Map<String, Float> drops;
    private Map<String, Float> portalDrops;

    // Shard itemIds 800..807 (vit, wis, hp, mp, att, def, spd, dex)
    private static final int SHARD_ITEM_BASE = 800;
    // Essence itemIds 816..819 (weapon, ability, armor, ring)
    private static final int ESSENCE_ITEM_BASE = 816;

    public List<GameItem> getLootDrop() {
        final List<GameItem> itemsToDrop = new ArrayList<>();
        // Look up enemy tier + rarity weights (may be null if no enemy model on file).
        final EnemyModel enemyModel = (this.enemyId != null) ? GameDataManager.ENEMIES.get(this.enemyId) : null;
        final int enemyTier = (enemyModel != null) ? Math.max(0, (int) enemyModel.getXp() / 100) : 0;
        // EnemyModel doesn't carry an explicit power tier — derive a coarse band
        // from its XP (≈ scales with difficulty); falls back to 0 for unknown.
        final java.util.List<Float> enemyWeights = enemyModel != null ? enemyModel.getRarityWeights() : null;

        for (final Map.Entry<String, Float> entry : this.drops.entrySet()) {
            if (Realm.RANDOM.nextFloat() >= entry.getValue()) continue;
            final String key = entry.getKey();
            final String[] parts = key.split(":");
            final String prefix = parts.length > 0 ? parts[0].toLowerCase() : "";

            if ("group".equals(prefix)) {
                final LootGroupModel lootGroup = GameDataManager.LOOT_GROUPS.get(this.getLootGroupId(key));
                if (lootGroup == null) continue;
                final int itemFromGroup = lootGroup.getPotentialDrops()
                        .get(Realm.RANDOM.nextInt(lootGroup.getPotentialDrops().size()));
                itemsToDrop.add(decorateEquipment(GameDataManager.GAME_ITEMS.get(itemFromGroup), enemyTier, enemyWeights));
            } else if ("shard".equals(prefix) || "shardany".equals(prefix)) {
                final int statId = "shardany".equals(prefix)
                        ? Realm.RANDOM.nextInt(8)
                        : safeParseInt(parts, 1, 0);
                final String range = "shardany".equals(prefix)
                        ? (parts.length > 1 ? parts[1] : "1-1")
                        : (parts.length > 2 ? parts[2] : "1-1");
                final GameItem shard = newStackedDrop(SHARD_ITEM_BASE + Math.max(0, Math.min(7, statId)), rollRange(range));
                if (shard != null) itemsToDrop.add(shard);
            } else if ("essence".equals(prefix) || "essenceany".equals(prefix)) {
                final int slotId = "essenceany".equals(prefix)
                        ? Realm.RANDOM.nextInt(4)
                        : safeParseInt(parts, 1, 0);
                final String range = "essenceany".equals(prefix)
                        ? (parts.length > 1 ? parts[1] : "1-1")
                        : (parts.length > 2 ? parts[2] : "1-1");
                final GameItem essence = newStackedDrop(ESSENCE_ITEM_BASE + Math.max(0, Math.min(3, slotId)), rollRange(range));
                if (essence != null) itemsToDrop.add(essence);
            } else {
                // Treat any other prefix as a direct itemId reference (existing "item:N" style)
                final GameItem item = GameDataManager.GAME_ITEMS.get(this.getLootGroupId(key));
                if (item != null) itemsToDrop.add(decorateEquipment(item, enemyTier, enemyWeights));
            }
        }
        return itemsToDrop;
    }

    /**
     * Take a template item and (if it's equipment) clone + roll rarity and
     * attribute modifiers onto the fresh instance. Stackables and non-equipment
     * pass through unchanged so essence/shard piles don't get stamped with a rarity.
     */
    private static GameItem decorateEquipment(GameItem template, int enemyTier, java.util.List<Float> enemyWeights) {
        if (template == null) return null;
        if (template.isStackable()) return template;
        if (template.getTargetSlot() < 0 || template.getTargetSlot() > 3) return template;
        final GameItem inst = template.clone();
        inst.setUid(java.util.UUID.randomUUID().toString());
        return LootRarityRoller.decorateEquipment(inst, enemyTier, enemyWeights);
    }

    private static int safeParseInt(String[] parts, int idx, int fallback) {
        if (parts == null || idx >= parts.length) return fallback;
        try { return Integer.parseInt(parts[idx]); } catch (Exception e) { return fallback; }
    }

    /** Roll an inclusive range "min-max" (or a single integer "5"). */
    private static int rollRange(String range) {
        if (range == null || range.isEmpty()) return 1;
        final int dash = range.indexOf('-');
        if (dash < 0) {
            try { return Math.max(1, Integer.parseInt(range)); } catch (Exception e) { return 1; }
        }
        try {
            final int min = Math.max(1, Integer.parseInt(range.substring(0, dash)));
            final int max = Math.max(min, Integer.parseInt(range.substring(dash + 1)));
            return min + Realm.RANDOM.nextInt(max - min + 1);
        } catch (Exception e) {
            return 1;
        }
    }

    /** Build a fresh stackable drop instance with rolled quantity, capped to maxStack. */
    private static GameItem newStackedDrop(int itemId, int qty) {
        final GameItem template = GameDataManager.GAME_ITEMS.get(itemId);
        if (template == null) return null;
        final GameItem stack = template.clone();
        stack.setUid(java.util.UUID.randomUUID().toString());
        final int max = stack.getMaxStack() > 0 ? stack.getMaxStack() : 1;
        stack.setStackCount(Math.max(1, Math.min(max, qty)));
        return stack;
    }

    public List<Integer> getPortalDrop() {
        if (this.portalDrops == null || this.portalDrops.isEmpty()) return Collections.emptyList();
        List<Integer> result = new ArrayList<>();
        for (final Map.Entry<String, Float> entry : this.portalDrops.entrySet()) {
            if (Realm.RANDOM.nextFloat() < entry.getValue()) {
                result.add(Integer.parseInt(entry.getKey()));
            }
        }
        return result;
    }

    public List<GameItem> getLootGroup(String groupKey) throws Exception {
        String[] split = groupKey.split(":");
        if ((split.length != 2) || !split[0].equalsIgnoreCase("group"))
            throw new Exception("This loot table key entry is not a loot group!");
        LootGroupModel model = GameDataManager.LOOT_GROUPS.get(Integer.parseInt(split[1]));
        final List<GameItem> results = new ArrayList<>();
        for (int i : model.getPotentialDrops()) {
            results.add(GameDataManager.GAME_ITEMS.get(i));
        }
        return results;
    }

    private boolean isLootGroup(String key) {
        String[] split = key.split(":");
        if ((split.length != 2) || !split[0].equalsIgnoreCase("group"))
            return false;
        return true;
    }

    private int getLootGroupId(String key) {
        return Integer.parseInt(key.split(":")[1]);
    }

    private List<GameItem> getGuarunteedDrops() {
        List<GameItem> res = new ArrayList<>();
        for (Entry<String, Float> drop : this.drops.entrySet()) {
            if (drop.getValue() == 1.0d) {
                if (this.isLootGroup(drop.getKey())) {
                    final LootGroupModel lootGroup = GameDataManager.LOOT_GROUPS
                            .get(this.getLootGroupId(drop.getKey()));
                    final int itemFromGroup = lootGroup.getPotentialDrops()
                            .get(Realm.RANDOM.nextInt(lootGroup.getPotentialDrops().size()));
                    res.add(GameDataManager.GAME_ITEMS.get(itemFromGroup));
                } else {
                    res.add(GameDataManager.GAME_ITEMS.get(this.getLootGroupId(drop.getKey())));
                }
            }
        }
        return res;
    }

    // private List<GameItem> getPossibleGameItems() {
    // return this.drops.keySet().stream().filter(key -> !this.isLootGroup(key))
    // .map(key -> Integer.parseInt(key.split(":")[1])).map(itemId ->
    // GameDataManager.GAME_ITEMS.get(itemId))
    // .collect(Collectors.toList());
    // }
}
