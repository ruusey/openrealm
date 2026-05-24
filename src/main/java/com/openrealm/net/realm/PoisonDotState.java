package com.openrealm.net.realm;

import java.time.Instant;

class PoisonDotState {
    final long enemyId;
    final int totalDamage;
    final long duration;
    final long startTime;
    final long sourcePlayerId;
    int damageApplied;
    long lastTickTime;

    PoisonDotState(long enemyId, int totalDamage, long duration, long sourcePlayerId) {
        this.enemyId = enemyId;
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
