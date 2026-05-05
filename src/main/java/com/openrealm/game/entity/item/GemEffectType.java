package com.openrealm.game.entity.item;

public enum GemEffectType {
    STAT_DELTA,        // +N to one of 8 stats (param1=statId, magnitude=delta)
    STAT_SCALE,        // ×% to one stat (param1=statId, magnitude=percent×100, e.g. 10 = +10%)
    PROJECTILE_COUNT,  // +N projectiles fired (magnitude=count)
    PROJECTILE_DAMAGE, // ×% damage scalar (magnitude=percent, e.g. 15 = +15%)
    ON_HIT_EFFECT,     // apply StatusEffectType (param1=statusEffectId, durationMs=ms)
    LIFESTEAL,         // % of damage healed (magnitude=percent, e.g. 5 = 5%)
    CRIT_CHANCE;       // % chance to crit (×2 damage) (magnitude=percent)

    public static GemEffectType fromOrdinal(int ord) {
        final GemEffectType[] vals = GemEffectType.values();
        if (ord < 0 || ord >= vals.length) return STAT_DELTA;
        return vals[ord];
    }
}
