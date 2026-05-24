package com.openrealm.net.realm;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class ActiveRealmEvent {
    final int eventId;
    final long bossEnemyId;
    final long spawnTime;
    final long durationMs;
    final int tileX, tileY;
    final int[][] savedBase;
    final int[][] savedCollision;
    final Set<Long> minionIds = new HashSet<>();
    final boolean[] wavesTriggered;
    boolean completed;

    public ActiveRealmEvent(int eventId, long bossEnemyId, int tileX, int tileY,
                            int[][] savedBase, int[][] savedCollision, int waveCount, long durationMs) {
        this.eventId = eventId;
        this.bossEnemyId = bossEnemyId;
        this.spawnTime = Instant.now().toEpochMilli();
        this.durationMs = durationMs;
        this.tileX = tileX;
        this.tileY = tileY;
        this.savedBase = savedBase;
        this.savedCollision = savedCollision;
        this.wavesTriggered = new boolean[waveCount];
        this.completed = false;
    }

    public int getEventId() { return eventId; }
    public long getBossEnemyId() { return bossEnemyId; }
    public long getSpawnTime() { return spawnTime; }
    public long getDurationMs() { return durationMs; }
    public int getTileX() { return tileX; }
    public int getTileY() { return tileY; }
    public int[][] getSavedBase() { return savedBase; }
    public int[][] getSavedCollision() { return savedCollision; }
    public Set<Long> getMinionIds() { return minionIds; }
    public boolean[] getWavesTriggered() { return wavesTriggered; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean v) { this.completed = v; }

    public boolean isExpired() {
        return Instant.now().toEpochMilli() - spawnTime >= durationMs;
    }
}
