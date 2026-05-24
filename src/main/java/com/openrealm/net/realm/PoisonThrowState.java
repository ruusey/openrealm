package com.openrealm.net.realm;

import java.time.Instant;

class PoisonThrowState {
    final long landTime;
    final long sourcePlayerId;
    final float landX;
    final float landY;
    final float radius;
    final int totalDamage;
    final long poisonDuration;
    final byte tier;

    PoisonThrowState(long delayMs, long sourcePlayerId, float landX, float landY,
                     float radius, int totalDamage, long poisonDuration, byte tier) {
        this.landTime = Instant.now().toEpochMilli() + delayMs;
        this.sourcePlayerId = sourcePlayerId;
        this.landX = landX;
        this.landY = landY;
        this.radius = radius;
        this.totalDamage = totalDamage;
        this.poisonDuration = poisonDuration;
        this.tier = tier;
    }

    boolean hasLanded() {
        return Instant.now().toEpochMilli() >= landTime;
    }
}
