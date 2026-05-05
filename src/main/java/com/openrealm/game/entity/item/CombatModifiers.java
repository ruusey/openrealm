package com.openrealm.game.entity.item;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Aggregated combat-time modifiers from gem enchantments on a single equipped
 * item (or set of items). Computed once per shot at the firing site so the
 * tight projectile-spawn loop doesn't re-walk the enchant list.
 */
@Data
public class CombatModifiers {
    /** Extra projectiles fired in addition to the weapon's group. */
    private int extraProjectiles = 0;
    /** Damage scalar in percent (added to 100). e.g. 15 means ×1.15 damage. */
    private int damagePct = 0;
    /** Crit chance in percent (0..100). */
    private int critChancePct = 0;
    /** Lifesteal in percent of dealt damage. */
    private int lifestealPct = 0;
    /** On-hit effects to append to every fired bullet from this source. */
    private final List<OnHitEffect> onHitEffects = new ArrayList<>();

    public boolean hasAny() {
        return extraProjectiles > 0 || damagePct != 0 || critChancePct > 0
                || lifestealPct > 0 || !onHitEffects.isEmpty();
    }

    @Data
    public static final class OnHitEffect {
        private final byte effectId;
        private final int durationMs;
    }

    /**
     * Walk the enchantments on a single GameItem and accumulate combat-time
     * modifiers. Stat-modifying enchantments (STAT_DELTA, STAT_SCALE) are
     * NOT included here — those are handled by Player.getComputedStats().
     */
    public static CombatModifiers fromItem(GameItem item) {
        final CombatModifiers cm = new CombatModifiers();
        if (item == null || item.getEnchantments() == null) return cm;
        for (Enchantment e : item.getEnchantments()) {
            switch (e.getEffectType()) {
            case 2: // PROJECTILE_COUNT
                cm.extraProjectiles += e.getMagnitude();
                break;
            case 3: // PROJECTILE_DAMAGE
                cm.damagePct += e.getMagnitude();
                break;
            case 4: // ON_HIT_EFFECT
                cm.onHitEffects.add(new OnHitEffect(e.getParam1(), e.getDurationMs()));
                break;
            case 5: // LIFESTEAL
                cm.lifestealPct += e.getMagnitude();
                break;
            case 6: // CRIT_CHANCE
                cm.critChancePct += e.getMagnitude();
                break;
            default:
                // STAT_DELTA / STAT_SCALE handled at stat-computation, not here.
                break;
            }
        }
        return cm;
    }
}
