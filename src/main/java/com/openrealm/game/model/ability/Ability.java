package com.openrealm.game.model.ability;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A class-bound active ability. Loaded from {@code abilities.json}. Each class
 * references up to 4 of these via {@link AbilityTree#getActives()}.
 *
 * See combat-rework.md §3.1 for the full schema and §6 for kit examples.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ability {
    private int id;
    private String name;
    private String description;
    private String iconKey;
    /** Owning class id (cross-checked against AbilityTree at load). */
    private int classId;
    /** Designer hint: which hotbar slot (0..3) this is meant for. Q=0..R=3. */
    private int slotHint = 0;

    private int mpCost = 0;
    private long baseCooldownMs = 0L;
    /** 0 = instant. Otherwise the ability locks the caster for this duration. */
    private long baseCastMs = 0L;
    /** Movement-speed multiplier while casting (0..1). 0 = stand still. */
    private float castMovementSpeedMul = 0f;

    private List<AbilityEffect> effects = new ArrayList<>();
    private List<AbilityScaling> scalings = new ArrayList<>();
    /** Free-form designer tags: "physical","cc","aoe","melee", etc. */
    private List<String> tags = new ArrayList<>();

    /**
     * Sprite location, matching the convention used by every other data type
     * (items, enemies, tiles): a sheet filename + a row/col into it, plus
     * an optional per-cell size.
     */
    private String spriteKey;
    private int row;
    private int col;
    private int spriteSize;
    private int spriteHeight;

    /**
     * Phase 3: when > 0, server uses this as the bullet's base damage instead
     * of the legacy item's getInRange roll. Scalings with target=DAMAGE add
     * on top. Player STR is NOT auto-added (unlike legacy basics) — design
     * intent is to let the Ability fully control damage from data.
     */
    private int baseDamage;

    /**
     * Override for the AoE base radius (px) on {@code aoe_targeted} and
     * {@code aoe_ally} abilities. When > 0, replaces the hardcoded 96px
     * floor in RealmManagerServer's AoE resolver. RADIUS scalings still add
     * on top. Use this to make a melee-feel ability (Ground Pound, etc.)
     * actually short-range — without it, 96px is the smallest AoE the
     * system can produce.
     */
    private int baseRadius;

    /**
     * Phase 2D — maximum invested skill points for this ability. Defaults to 5
     * for non-ultimates; ultimate abilities (slot 3) typically declare 3 in
     * data. Server enforces the cap on {@code InvestSkillPointPacket}.
     */
    private int maxSkillPoints = 5;

    /**
     * Phase 2D — flat ms shaved off {@link #baseCooldownMs} per invested skill
     * point. e.g. 1000 means each point cuts 1s. Server caps effective CD at
     * 500ms so abilities can't drop to 0. 0 = this ability doesn't scale CD
     * with skill points.
     */
    private int cdReductionPerPointMs = 0;

    /** Convenience: nullsafe effects. */
    public List<AbilityEffect> effectList() {
        return this.effects == null ? new ArrayList<>() : this.effects;
    }

    /** Convenience: nullsafe scalings. */
    public List<AbilityScaling> scalingList() {
        return this.scalings == null ? new ArrayList<>() : this.scalings;
    }
}
