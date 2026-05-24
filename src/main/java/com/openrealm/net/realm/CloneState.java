package com.openrealm.net.realm;

import java.time.Instant;

class CloneState {
    final long clonePlayerId;
    final long sourcePlayerId;
    final long spawnTime;
    final long durationMs;
    final float dx;
    final float dy;

    CloneState(long clonePlayerId, long sourcePlayerId, float dx, float dy, long durationMs) {
        this.clonePlayerId = clonePlayerId;
        this.sourcePlayerId = sourcePlayerId;
        this.spawnTime = Instant.now().toEpochMilli();
        this.durationMs = durationMs;
        this.dx = dx;
        this.dy = dy;
    }

    boolean isExpired() {
        return Instant.now().toEpochMilli() - spawnTime >= durationMs;
    }
}
