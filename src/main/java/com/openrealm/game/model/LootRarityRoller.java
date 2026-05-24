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
 *
 * 2026-05-18: rarity scale changed to MUNDANE..LEGENDARY (was COMMON..MYTHICAL).
 * Weight tables and modifier bands rebalanced for the new low end.
 */
public final class LootRarityRoller {
    private static final int NUM_STATS = 8;

    // Default per-tier rarity weights, indexed by Rarity ordinal:
    // MUNDANE, COMMON, UNCOMMON, RARE, EPIC, LEGENDARY.
    private static final float[][] DEFAULT_WEIGHTS_BY_TIER = new float[][] {
            { 60f, 30f, 8f,  2f,   0f,  0f }, // tier 0–2  — mostly mundane
            { 35f, 40f, 18f, 5f,   2f,  0f }, // tier 3–5
            { 20f, 30f, 28f, 15f,  5f,  2f }, // tier 6–8
            { 10f, 20f, 30f, 25f, 11f,  4f }, // tier 9–11
            {  3f, 12f, 25f, 30f, 20f, 10f }, // tier 12+
    };

    private LootRarityRoller() {}

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
        if (sum <= 0f) return Rarity.MUNDANE;
        float roll = Realm.RANDOM.nextFloat() * sum;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            roll -= weights[i];
            if (roll <= 0f) return Rarity.fromOrdinal(i);
        }
        return Rarity.MUNDANE;
    }

    /**
     * Net-neutral prefix roller per the new design doc — Phase 1 placeholder.
     * Picks two distinct stats and assigns one +X / one -X (same magnitude),
     * so the total contribution sums to zero. Tuning pass comes later.
     *
     * Mundane items roll no prefix. Higher rarities push the magnitude up.
     */
    public static List<AttributeModifier> rollAttributeModifiers(Rarity rarity) {
        if (rarity == Rarity.MUNDANE) return new ArrayList<>();

        final int magCap;
        switch (rarity) {
        case COMMON:    magCap = 1; break;
        case UNCOMMON:  magCap = 2; break;
        case RARE:      magCap = 3; break;
        case EPIC:      magCap = 4; break;
        case LEGENDARY: magCap = 5; break;
        default:        magCap = 1;
        }

        final byte statPos = (byte) Realm.RANDOM.nextInt(NUM_STATS);
        byte statNeg;
        int safety = 0;
        do {
            statNeg = (byte) Realm.RANDOM.nextInt(NUM_STATS);
            if (++safety > 16) break;
        } while (statNeg == statPos);

        int mag = 1 + Realm.RANDOM.nextInt(magCap);
        // HP/MP stats scale much larger than the other stats; scale up so the
        // visible prefix actually means something on those stats.
        int magPos = mag;
        int magNeg = mag;
        if (statPos == 2 || statPos == 3) magPos *= 5;
        if (statNeg == 2 || statNeg == 3) magNeg *= 5;
        if (magPos > Byte.MAX_VALUE) magPos = Byte.MAX_VALUE;
        if (magNeg > Byte.MAX_VALUE) magNeg = Byte.MAX_VALUE;

        final List<AttributeModifier> out = new ArrayList<>(2);
        out.add(new AttributeModifier(statPos, (byte) magPos));
        out.add(new AttributeModifier(statNeg, (byte) -magNeg));
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
