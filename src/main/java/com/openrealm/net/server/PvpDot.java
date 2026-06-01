package com.openrealm.net.server;

import java.time.Instant;

import com.openrealm.game.contants.StatusEffectType;

/**
 * A damage-over-time effect ticking on a player inside a PvP realm (poison or
 * bleed). Owned entirely by {@link PvpEffectsManager}; mirrors the enemy-side
 * {@code DotState} but targets a player.
 */
class PvpDot {
    final long targetPlayerId;
    final long sourcePlayerId;
    final StatusEffectType status;
    final int totalDamage;
    final long durationMs;
    final long startMs;
    int damageApplied;
    long lastTickMs;

    PvpDot(long targetPlayerId, long sourcePlayerId, StatusEffectType status,
            int totalDamage, long durationMs) {
        this.targetPlayerId = targetPlayerId;
        this.sourcePlayerId = sourcePlayerId;
        this.status = status;
        this.totalDamage = totalDamage;
        this.durationMs = durationMs;
        this.startMs = Instant.now().toEpochMilli();
        this.damageApplied = 0;
        this.lastTickMs = this.startMs;
    }

    boolean isExpired(long now) {
        return now - this.startMs >= this.durationMs;
    }

    /** Damage for one tick, clamped so the running total never exceeds totalDamage. */
    int nextTickDamage(long tickIntervalMs) {
        final int totalTicks = (int) Math.max(1, this.durationMs / tickIntervalMs);
        int tick = Math.max(1, this.totalDamage / totalTicks);
        if (this.damageApplied + tick > this.totalDamage) {
            tick = this.totalDamage - this.damageApplied;
        }
        if (tick > 0) this.damageApplied += tick;
        return tick;
    }
}
