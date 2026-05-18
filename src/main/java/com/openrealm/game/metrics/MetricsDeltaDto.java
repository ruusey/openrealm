package com.openrealm.game.metrics;

import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire-format mirror of {@link MetricsDelta}. Identical shape; the
 * runtime delta uses primitive long fields with public access for the
 * fast counter increments, this DTO version exists separately so the
 * HTTP serialization story is explicit (and so future schema tweaks
 * on the wire don't accidentally couple to the in-process counter
 * struct).
 *
 * Map keys go over the wire as String — Jackson serializes integer-
 * keyed maps as objects-of-string-keys anyway, and the data service
 * stores them under string keys to match Mongo's BSON object key
 * type. The data service parses the string keys back when it needs to
 * reason about them numerically.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricsDeltaDto {
    // Combat
    public long projectilesFired;
    public long projectilesHit;
    public long damageDealtTotal;
    public long damageTakenTotal;
    public long deaths;
    public long killsTotal;
    public long bossKills;
    public Map<String, Long> killsByEnemyId;

    // Items
    public long hpPotionsDrank;
    public long mpPotionsDrank;
    public long itemsPickedUp;
    public Map<String, Long> itemsConsumedByItemId;

    // Progression
    public long xpEarned;
    public long xpFromKills;
    public long skillPointsSpent;

    // Abilities
    public Map<String, Long> castsStartedByAbility;
    public Map<String, Long> castsCompletedByAbility;

    // Social
    public long tradesCompleted;
    public Map<String, Long> tradePartners;
    public long chatMessagesSent;

    /** Stringify a delta for the wire — every map key becomes a String,
     *  scalars copy 1:1. The data service uses the same field names so
     *  Jackson reflection on both sides is symmetric. */
    public static MetricsDeltaDto from(MetricsDelta d) {
        if (d == null) return null;
        final MetricsDeltaDto out = new MetricsDeltaDto();
        out.projectilesFired   = d.projectilesFired;
        out.projectilesHit     = d.projectilesHit;
        out.damageDealtTotal   = d.damageDealtTotal;
        out.damageTakenTotal   = d.damageTakenTotal;
        out.deaths             = d.deaths;
        out.killsTotal         = d.killsTotal;
        out.bossKills          = d.bossKills;
        out.killsByEnemyId     = stringifyShort(d.killsByEnemyId);
        out.hpPotionsDrank     = d.hpPotionsDrank;
        out.mpPotionsDrank     = d.mpPotionsDrank;
        out.itemsPickedUp      = d.itemsPickedUp;
        out.itemsConsumedByItemId = stringifyInt(d.itemsConsumedByItemId);
        out.xpEarned           = d.xpEarned;
        out.xpFromKills        = d.xpFromKills;
        out.skillPointsSpent   = d.skillPointsSpent;
        out.castsStartedByAbility   = stringifyInt(d.castsStartedByAbility);
        out.castsCompletedByAbility = stringifyInt(d.castsCompletedByAbility);
        out.tradesCompleted    = d.tradesCompleted;
        out.tradePartners      = d.tradePartners;  // already String-keyed
        out.chatMessagesSent   = d.chatMessagesSent;
        return out;
    }

    private static Map<String, Long> stringifyShort(Map<Short, Long> in) {
        if (in == null || in.isEmpty()) return null;
        final HashMap<String, Long> out = new HashMap<>(in.size());
        for (Map.Entry<Short, Long> e : in.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
    private static Map<String, Long> stringifyInt(Map<Integer, Long> in) {
        if (in == null || in.isEmpty()) return null;
        final HashMap<String, Long> out = new HashMap<>(in.size());
        for (Map.Entry<Integer, Long> e : in.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
