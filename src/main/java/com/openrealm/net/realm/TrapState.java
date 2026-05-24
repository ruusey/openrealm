package com.openrealm.net.realm;

import java.time.Instant;

class TrapState {
    // 500ms gap between throw landing and trap arming so enemies standing on
    // the landing tile can't be hit by both visuals on the same tick.
    static final long ARM_TIME_MS = 500;

    final long placeTime;
    final long armReadyTime;
    final long expireTime;
    final long sourcePlayerId;
    final float x, y;
    final float triggerRadius;
    final short effectId;
    final long effectDuration;
    final int damage;
    final byte tier;
    boolean armed = false;
    boolean triggered = false;

    TrapState(long throwDelayMs, long sourcePlayerId, float x, float y,
              float triggerRadius, short effectId, long effectDuration, int damage,
              long lifetimeMs, byte tier) {
        this.placeTime = Instant.now().toEpochMilli() + throwDelayMs;
        this.armReadyTime = this.placeTime + ARM_TIME_MS;
        this.expireTime = this.placeTime + lifetimeMs;
        this.sourcePlayerId = sourcePlayerId;
        this.x = x;
        this.y = y;
        this.triggerRadius = triggerRadius;
        this.effectId = effectId;
        this.effectDuration = effectDuration;
        this.damage = damage;
        this.tier = tier;
    }

    boolean hasLanded() { return Instant.now().toEpochMilli() >= placeTime; }
    boolean isArmed()   { return Instant.now().toEpochMilli() >= armReadyTime; }
    boolean isExpired() { return Instant.now().toEpochMilli() >= expireTime; }
}
