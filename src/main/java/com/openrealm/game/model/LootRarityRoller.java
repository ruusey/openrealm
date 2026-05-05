package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Rarity;
import com.openrealm.net.realm.Realm;

/**
 * Rolls a Rarity and (optionally) a small list of AttributeModifier affixes for
 * a freshly-dropped equipment item. Drop chance per rarity is taken from the
 * enemy's {@code rarityWeights}; if absent, a tier-derived default table is used.
 */
public final class LootRarityRoller {
    private LootRarityRoller() {}

    private static final int NUM_STATS = 8;

    // Default per-tier rarity weights (COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHICAL).
    // Indexed by enemy tier band.
    private static final float[][] DEFAULT_WEIGHTS_BY_TIER = new float[][] {
            { 70f, 25f,  4f,  1f,    0f,   0f }, // tier 0–2
            { 50f, 30f, 15f,  4f,    1f,   0f }, // tier 3–5
            { 30f, 30f, 25f, 10f,    4f,   1f }, // tier 6–8
            { 15f, 25f, 30f, 20f,    8f,   2f }, // tier 9–11
            {  5f, 15f, 30f, 30f,   15f,   5f }, // tier 12+
    };

    private static int tierBand(int tier) {
        if (tier < 0) return 0;
        if (tier <= 2) return 0;
        if (tier <= 5) return 1;
        if (tier <= 8) return 2;
        if (tier <= 11) return 3;
        return 4;
    }

    /** Rolls a Rarity for an equipment drop given the enemy's tier and optional weights. */
    public static Rarity rollRarity(int enemyTier, List<Float> enemyWeights) {
        final float[] weights = (enemyWeights != null && enemyWeights.size() == Rarity.values().length)
                ? toArr(enemyWeights)
                : DEFAULT_WEIGHTS_BY_TIER[tierBand(enemyTier)];
        float sum = 0f;
        for (float w : weights) if (w > 0) sum += w;
        if (sum <= 0f) return Rarity.COMMON;
        float roll = Realm.RANDOM.nextFloat() * sum;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            roll -= weights[i];
            if (roll <= 0f) return Rarity.fromOrdinal(i);
        }
        return Rarity.COMMON;
    }

    /** Roll 0..2 attribute modifiers, with magnitudes scaled by rarity. */
    public static List<AttributeModifier> rollAttributeModifiers(Rarity rarity) {
        // Number of modifiers: COMMON 0..1, UNCOMMON 1, RARE 1, EPIC 1..2, LEGENDARY 2, MYTHICAL 2.
        final int count;
        switch (rarity) {
        case COMMON:    count = Realm.RANDOM.nextInt(2); break; // 0 or 1
        case UNCOMMON:
        case RARE:      count = 1; break;
        case EPIC:      count = 1 + Realm.RANDOM.nextInt(2); break; // 1 or 2
        case LEGENDARY:
        case MYTHICAL:  count = 2; break;
        default:        count = 0;
        }
        if (count == 0) return new ArrayList<>();

        // Magnitude band per rarity. Negative rolls are allowed (Diablo-style ±)
        // but biased positive at higher rarity so mythicals don't feel cursed.
        final int magCap = Math.max(1, rarity.ordinal()); // 1..5
        final float negChance;
        switch (rarity) {
        case COMMON:    negChance = 0.35f; break;
        case UNCOMMON:  negChance = 0.25f; break;
        case RARE:      negChance = 0.15f; break;
        case EPIC:      negChance = 0.10f; break;
        case LEGENDARY: negChance = 0.05f; break;
        case MYTHICAL:  negChance = 0.0f;  break;
        default:        negChance = 0.30f;
        }

        final List<AttributeModifier> out = new ArrayList<>(count);
        final boolean[] usedStats = new boolean[NUM_STATS];
        for (int i = 0; i < count; i++) {
            byte statId;
            int tries = 0;
            do {
                statId = (byte) Realm.RANDOM.nextInt(NUM_STATS);
                if (++tries > 16) break;
            } while (usedStats[statId]);
            usedStats[statId] = true;

            int mag = 1 + Realm.RANDOM.nextInt(magCap);
            // HP/MP scale much larger than the other stats; scale up so a +1 isn't lost.
            if (statId == 2 || statId == 3) mag *= 5;
            final boolean negative = Realm.RANDOM.nextFloat() < negChance;
            if (negative) mag = -mag;
            // Clamp to byte range
            if (mag > Byte.MAX_VALUE) mag = Byte.MAX_VALUE;
            if (mag < Byte.MIN_VALUE) mag = Byte.MIN_VALUE;
            out.add(new AttributeModifier(statId, (byte) mag));
        }
        return out;
    }

    /** Decorate an equipment-class item with rarity + modifiers. Returns the same instance. */
    public static GameItem decorateEquipment(GameItem instance, int enemyTier, List<Float> enemyWeights) {
        if (instance == null) return null;
        if (instance.getTargetSlot() < 0 || instance.getTargetSlot() > 3) return instance;
        if (instance.isStackable()) return instance;
        final Rarity r = rollRarity(enemyTier, enemyWeights);
        instance.setRarity((byte) r.ordinal());
        instance.setAttributeModifiers(rollAttributeModifiers(r));
        return instance;
    }

    private static float[] toArr(List<Float> ws) {
        final float[] out = new float[ws.size()];
        for (int i = 0; i < ws.size(); i++) {
            final Float f = ws.get(i);
            out[i] = f == null ? 0f : f;
        }
        return out;
    }
}
