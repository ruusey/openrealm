package com.openrealm.net.realm;

import java.time.Instant;

import com.openrealm.game.contants.StatusEffectType;

/**
 * A damage-over-time effect ticking on an enemy — poison or bleed. The damage
 * mechanism is identical for both; the {@code status} distinguishes them so a
 * source refreshes its own DoT (rather than stacking a new one every hit) and so
 * the matching status icon stays in sync.
 */
class DotState {
    final long enemyId;
    final StatusEffectType status;
    final int totalDamage;
    final long duration;
    final long startTime;
    final long sourcePlayerId;
    int damageApplied;
    long lastTickTime;

    DotState(long enemyId, StatusEffectType status, int totalDamage, long duration, long sourcePlayerId) {
        this.enemyId = enemyId;
        this.status = status;
        this.totalDamage = totalDamage;
        this.duration = duration;
        this.startTime = Instant.now().toEpochMilli();
        this.sourcePlayerId = sourcePlayerId;
        this.damageApplied = 0;
        this.lastTickTime = this.startTime;
    }

    boolean isExpired() {
        return Instant.now().toEpochMilli() - startTime >= duration;
    }
}
