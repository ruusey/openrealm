package com.openrealm.game.model.ability;

import com.openrealm.game.contants.ScalingCurve;
import com.openrealm.game.contants.ScalingTarget;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stat→effect contribution on an {@link Ability} or {@link PassiveAbility}.
 *
 * Wire format example:
 * <pre>
 *   { "stat": "STR", "coeff": 0.8, "target": "DAMAGE" }
 *   { "stat": "VIT", "coeff": 10.0, "target": "STATUS_DURATION_MS", "effectIndex": 1 }
 *   { "stat": "DEF", "coeff": 0.001, "target": "PROC_CHANCE", "cap": 0.25 }
 * </pre>
 *
 * {@code stat} is the 3-letter name (HP/MP/DEF/STR/SPD/DEX/VIT/WIS) — resolved
 * to the {@code Stats} index at apply-time. {@code curve} defaults to LINEAR.
 * {@code effectIndex} is only meaningful when the target refers to a specific
 * effect within the ability's {@code effects[]} list (e.g. STATUS_DURATION_MS).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbilityScaling {
    /** Stat name: "HP" "MP" "DEF" "STR" "SPD" "DEX" "VIT" "WIS". */
    private String stat;
    private float coeff;
    /** Resolved at load-time from {@code targetName}; UNKNOWN if not recognized. */
    private String target;
    /** Index into the parent's effects[] when the target needs one; -1 if not. */
    private int effectIndex = -1;
    /** Optional ceiling on the contribution. {@code <= 0} means no cap. */
    private float cap = 0f;
    /** "LINEAR" | "DIMINISHING" | "THRESHOLD". Defaults to LINEAR. */
    private String curve;

    /** Resolved at apply-time; do not serialize. */
    public ScalingTarget targetEnum() {
        return ScalingTarget.parse(this.target);
    }

    public ScalingCurve curveEnum() {
        return ScalingCurve.parse(this.curve);
    }

    /**
     * Numeric stat-index lookup matching the Stats POJO order
     * (0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX). Returns -1 for unknown.
     *
     * Phase 2D — index 8 is a synthetic "SKILL_POINTS" input that the apply
     * site resolves to the player's invested level for the parent Ability.
     * Use {@code "stat": "SKILL_POINTS"} in scalings to make a contribution
     * scale per invested point instead of a real stat.
     */
    public int statIndex() {
        if (this.stat == null) return -1;
        switch (this.stat.trim().toUpperCase()) {
            case "VIT": return 0;
            case "WIS": return 1;
            case "HP":  return 2;
            case "MP":  return 3;
            case "STR": return 4;
            case "DEF": return 5;
            case "SPD": return 6;
            case "DEX": return 7;
            case "SKILL_POINTS":
            case "SKILLPOINTS":
            case "SP":  return 8;
            default:    return -1;
        }
    }

    /** True if this scaling reads from invested skill points instead of a stat. */
    public boolean isSkillPointScaling() {
        return statIndex() == 8;
    }
}
