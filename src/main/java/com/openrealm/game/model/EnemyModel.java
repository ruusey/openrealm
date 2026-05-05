package com.openrealm.game.model;

import java.util.List;

import com.openrealm.game.entity.item.Stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class EnemyModel extends SpriteModel {
    private int enemyId;
    private int size;
    private int attackId;
    private String name;
    private int xp;
    private Stats stats;
    private int health;
    private float maxSpeed;
    private float chaseRange;
    private float attackRange;
    private List<EnemyPhase> phases;
    /**
     * Status effect IDs (see {@link com.openrealm.game.contants.StatusEffectType})
     * that are applied permanently to this enemy on spawn. Effects added this way
     * never expire. Used e.g. for static, unkillable NPCs like the Vault Healer and
     * Nexus bosses which need {@code INVINCIBLE (6)} for their entire lifetime.
     */
    private List<Short> permanentEffects;
    /**
     * Weights for COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHICAL drops on this
     * enemy. Optional — when null/missing, {@link com.openrealm.game.model.LootRarityRoller}
     * derives a default table from the enemy's tier (high-tier enemies skew toward
     * higher-rarity drops). Sum is normalized at roll time so any positive weights work.
     */
    private List<Float> rarityWeights;
}
