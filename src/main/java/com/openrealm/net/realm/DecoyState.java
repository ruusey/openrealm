package com.openrealm.net.realm;

import java.time.Instant;

class DecoyState {
    final long enemyId;
    final long sourcePlayerId;
    final long spawnTime;
    final long durationMs;
    final float dx;
    final float dy;
    final float maxTravelDistSq;
    final float originX;
    final float originY;
    boolean stopped;

    DecoyState(long enemyId, long sourcePlayerId, float originX, float originY,
               float dx, float dy, float maxTravelDist, long durationMs) {
        this.enemyId = enemyId;
        this.sourcePlayerId = sourcePlayerId;
        this.spawnTime = Instant.now().toEpochMilli();
        this.durationMs = durationMs;
        this.dx = dx;
        this.dy = dy;
        this.maxTravelDistSq = maxTravelDist * maxTravelDist;
        this.originX = originX;
        this.originY = originY;
        this.stopped = false;
    }

    boolean isExpired() {
        return Instant.now().toEpochMilli() - spawnTime >= durationMs;
    }
}
