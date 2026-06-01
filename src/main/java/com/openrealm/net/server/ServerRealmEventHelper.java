package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.MinionWave;
import com.openrealm.game.model.OverworldZone;
import com.openrealm.game.model.RealmEventModel;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.tile.TileManager;
import com.openrealm.net.realm.ActiveRealmEvent;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.TextPacket;

import lombok.extern.slf4j.Slf4j;

/**
 * Realm events: boss encounters with set-piece terrain, HP-gated minion waves,
 * and client beacons. State (the active-event list) lives on each {@link Realm};
 * this helper drives spawning, per-tick processing, and completion.
 */
@Slf4j
public final class ServerRealmEventHelper {

    private static final int EVENT_CHECK_INTERVAL_TICKS = 6400;
    private static final double REALM_EVENT_SPAWN_CHANCE = 0.15;
    private static final int MAX_CONCURRENT_EVENTS = 1;
    private static final int EVENT_MARKER_INTERVAL_TICKS = 192;

    /** Called every server tick from RealmManagerServer.tickGlobal(). */
    public static void tick(final RealmManagerServer mgr, final long tickCounter) {
        final boolean broadcastMarkers = (tickCounter % EVENT_MARKER_INTERVAL_TICKS == 0);
        final boolean checkSpawn = (tickCounter % EVENT_CHECK_INTERVAL_TICKS == 0);
        for (final Realm realm : mgr.getRealms().values()) {
            if (!realm.getActiveRealmEvents().isEmpty()) {
                processActiveEvents(mgr, realm, broadcastMarkers);
            }
            if (checkSpawn && isEventEligible(realm)) {
                checkRealmEventSpawn(mgr, realm);
            }
        }
    }

    /** When an enemy dies, complete any active event whose boss it was. */
    public static void handleEnemyKilled(final RealmManagerServer mgr, final Realm realm, final Enemy enemy) {
        if (realm.getActiveRealmEvents().isEmpty()) return;
        for (final ActiveRealmEvent evt : realm.getActiveRealmEvents()) {
            if (evt.getBossEnemyId() == enemy.getId() && !evt.isCompleted()) {
                completeRealmEvent(mgr, realm, evt);
                return;
            }
        }
    }

    private static boolean isEventEligible(final Realm realm) {
        final TerrainGenerationParameters params = getTerrainParams(realm);
        if (params == null || params.getZones() == null || params.getZones().isEmpty()) return false;
        return hasHumanPlayer(realm);
    }

    private static boolean hasHumanPlayer(final Realm realm) {
        for (final Player p : realm.getPlayers().values()) {
            if (p != null && !p.isHeadless() && !p.isBot()) return true;
        }
        return false;
    }

    private static void checkRealmEventSpawn(final RealmManagerServer mgr, final Realm realm) {
        if (GameDataManager.REALM_EVENTS == null || GameDataManager.REALM_EVENTS.isEmpty()) return;
        if (realm.getActiveRealmEvents().size() >= MAX_CONCURRENT_EVENTS) return;
        if (Realm.RANDOM.nextDouble() >= REALM_EVENT_SPAWN_CHANCE) return;

        final List<RealmEventModel> candidates = new ArrayList<>(GameDataManager.REALM_EVENTS.values());
        final RealmEventModel event = candidates.get(Realm.RANDOM.nextInt(candidates.size()));
        spawnRealmEvent(mgr, realm, event, null);
    }

    /** Spawn at atPos (admin /event) bypassing zone/safety checks, or random when null. */
    public static boolean spawnRealmEvent(final RealmManagerServer mgr, final Realm realm,
            final RealmEventModel event, final Vector2f atPos) {
        final TerrainGenerationParameters params = getTerrainParams(realm);
        final boolean hasZones = params != null && params.getZones() != null && !params.getZones().isEmpty();
        final TileManager tm = realm.getTileManager();
        final int tileSize = tm.getMapLayers().get(0).getTileSize();

        final SetPieceModel setPiece = (event.getSetPieceId() >= 0 && GameDataManager.SETPIECES != null)
                ? GameDataManager.SETPIECES.get(event.getSetPieceId()) : null;
        final int spWidth = setPiece != null ? setPiece.getWidth() : 1;
        final int spHeight = setPiece != null ? setPiece.getHeight() : 1;

        Vector2f spawnPos = null;
        int tileX = 0, tileY = 0;
        if (atPos != null) {
            spawnPos = atPos.clone();
            tileX = (int) (atPos.x / tileSize) - spWidth / 2;
            tileY = (int) (atPos.y / tileSize) - spHeight / 2;
        } else {
            for (int attempt = 0; attempt < 200; attempt++) {
                final Vector2f candidate = tm.getSafePosition();
                if (candidate == null) continue;

                if (hasZones && event.getAllowedZones() != null) {
                    final OverworldZone zone = tm.getZoneForPosition(candidate.x, candidate.y);
                    if (zone == null || !event.getAllowedZones().contains(zone.getZoneId())) {
                        continue;
                    }
                }

                if (tm.isVoidTile(candidate, 0, 0)) continue;

                spawnPos = candidate;
                tileX = (int) (candidate.x / tileSize) - spWidth / 2;
                tileY = (int) (candidate.y / tileSize) - spHeight / 2;
                break;
            }

            if (spawnPos == null) {
                log.warn("[REALM_EVENT] Failed to find valid spawn position for event '{}'", event.getName());
                return false;
            }
        }

        int[][] savedBase = null;
        int[][] savedColl = null;
        if (setPiece != null) {
            final int[][][] saved = realm.saveTerrainAt(tileX, tileY, spWidth, spHeight);
            savedBase = saved[0];
            savedColl = saved[1];
            realm.stampSetPiece(setPiece, tileX, tileY, null);
        }

        final EnemyModel bossModel = GameDataManager.ENEMIES.get(event.getBossEnemyId());
        if (bossModel == null) {
            log.error("[REALM_EVENT] Boss enemyId {} not found for event '{}'",
                    event.getBossEnemyId(), event.getName());
            return false;
        }

        // Boss spawn point may be on a stamped collision tile — push to nearest open.
        final int searchRadius = Math.max(spWidth, spHeight) + 2;
        final Vector2f bossSafePos = findOpenSpawnNear(realm, spawnPos, searchRadius);
        if (bossSafePos != null) spawnPos = bossSafePos;

        final float eventMult = Math.max(1, event.getEventMultiplier());
        final float diff = realm.getZoneDifficulty(spawnPos.x, spawnPos.y) * eventMult;
        final Enemy boss = new Enemy(Realm.RANDOM.nextLong(), bossModel.getEnemyId(),
                spawnPos.clone(), bossModel.getSize(), bossModel.getAttackId());
        boss.setDifficulty(diff);
        boss.setHealth((int) (boss.getHealth() * diff));
        boss.getStats().setHp((short) (boss.getStats().getHp() * diff));
        boss.setPos(spawnPos);
        realm.addEnemy(boss);

        final int waveCount = event.getMinionWaves() != null ? event.getMinionWaves().size() : 0;
        final long durationMs = event.getDurationSeconds() * 1000L;
        final ActiveRealmEvent activeEvent = new ActiveRealmEvent(
                event.getEventId(), boss.getId(), tileX, tileY,
                savedBase, savedColl, waveCount, durationMs);
        realm.getActiveRealmEvents().add(activeEvent);

        String zoneName = "realm";
        if (hasZones) {
            final OverworldZone zone = tm.getZoneForPosition(spawnPos.x, spawnPos.y);
            if (zone != null) zoneName = zone.getDisplayName();
        }
        broadcastEventMessage(mgr, realm, String.format(event.getAnnounceMessage(), zoneName));
        broadcastEventMarker(mgr, realm, activeEvent, boss, event);
        log.info("[REALM_EVENT] Spawned '{}' at tile ({}, {}), boss entityId={}, duration={}s",
                event.getName(), tileX, tileY, boss.getId(), event.getDurationSeconds());
        return true;
    }

    private static void processActiveEvents(final RealmManagerServer mgr, final Realm realm,
            final boolean broadcastMarkers) {
        final Iterator<ActiveRealmEvent> it = realm.getActiveRealmEvents().iterator();
        while (it.hasNext()) {
            final ActiveRealmEvent evt = it.next();
            if (evt.isCompleted()) {
                it.remove();
                continue;
            }

            if (evt.isExpired()) {
                timeoutRealmEvent(mgr, realm, evt);
                it.remove();
                continue;
            }

            final Enemy boss = realm.getEnemies().get(evt.getBossEnemyId());
            if (boss == null || boss.getDeath()) {
                completeRealmEvent(mgr, realm, evt);
                it.remove();
                continue;
            }

            final RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.getEventId());
            if (broadcastMarkers && eventModel != null) {
                broadcastEventMarker(mgr, realm, evt, boss, eventModel);
            }

            if (eventModel == null || eventModel.getMinionWaves() == null) continue;

            final float bossHpPercent = (float) boss.getHealth() / (float) (boss.getStats().getHp());
            for (int i = 0; i < eventModel.getMinionWaves().size(); i++) {
                if (evt.getWavesTriggered()[i]) continue;
                final MinionWave wave = eventModel.getMinionWaves().get(i);
                if (bossHpPercent <= wave.getTriggerHpPercent()) {
                    evt.getWavesTriggered()[i] = true;
                    spawnMinionWave(realm, evt, boss, wave);
                }
            }

            evt.getMinionIds().removeIf(id -> {
                final Enemy m = realm.getEnemies().get(id);
                return m == null || m.getDeath();
            });
        }
    }

    private static void spawnMinionWave(final Realm realm, final ActiveRealmEvent evt,
            final Enemy boss, final MinionWave wave) {
        final EnemyModel minionModel = GameDataManager.ENEMIES.get(wave.getEnemyId());
        if (minionModel == null) return;

        final float angleStep = (float) (2 * Math.PI / Math.max(1, wave.getCount()));
        for (int i = 0; i < wave.getCount(); i++) {
            final float angle = angleStep * i;
            final float ox = (float) Math.cos(angle) * wave.getOffset();
            final float oy = (float) Math.sin(angle) * wave.getOffset();
            Vector2f minionPos = new Vector2f(boss.getPos().x + ox, boss.getPos().y + oy);

            final Vector2f safePos = findOpenSpawnNear(realm, minionPos, 4);
            if (safePos != null) minionPos = safePos;

            final float waveMult = Math.max(1, wave.getEventMultiplier());
            final float minionDiff = realm.getZoneDifficulty(minionPos.x, minionPos.y) * waveMult;
            final Enemy minion = new Enemy(Realm.RANDOM.nextLong(), minionModel.getEnemyId(),
                    minionPos, minionModel.getSize(), minionModel.getAttackId());
            minion.setDifficulty(minionDiff);
            minion.setHealth((int) (minion.getHealth() * minionDiff));
            minion.getStats().setHp((short) (minion.getStats().getHp() * minionDiff));
            realm.addEnemy(minion);
            evt.getMinionIds().add(minion.getId());
        }
        log.info("[REALM_EVENT] Spawned minion wave: {}x {}", wave.getCount(), minionModel.getName());
    }

    private static void completeRealmEvent(final RealmManagerServer mgr, final Realm realm,
            final ActiveRealmEvent evt) {
        evt.setCompleted(true);
        broadcastEventMarkerRemove(mgr, realm, evt.getBossEnemyId());
        final RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.getEventId());

        for (final long minionId : evt.getMinionIds()) {
            final Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        if (evt.getSavedBase() != null && evt.getSavedCollision() != null) {
            realm.restoreTerrainAt(evt.getTileX(), evt.getTileY(), evt.getSavedBase(), evt.getSavedCollision());
        }

        if (eventModel != null) {
            broadcastEventMessage(mgr, realm, eventModel.getDefeatMessage());
        }
        log.info("[REALM_EVENT] Completed event id={}", evt.getEventId());
    }

    private static void timeoutRealmEvent(final RealmManagerServer mgr, final Realm realm,
            final ActiveRealmEvent evt) {
        evt.setCompleted(true);
        broadcastEventMarkerRemove(mgr, realm, evt.getBossEnemyId());
        final RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.getEventId());

        final Enemy boss = realm.getEnemies().get(evt.getBossEnemyId());
        if (boss != null && !boss.getDeath()) {
            realm.getExpiredEnemies().add(evt.getBossEnemyId());
            realm.removeEnemy(boss);
        }

        for (final long minionId : evt.getMinionIds()) {
            final Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        if (evt.getSavedBase() != null && evt.getSavedCollision() != null) {
            realm.restoreTerrainAt(evt.getTileX(), evt.getTileY(), evt.getSavedBase(), evt.getSavedCollision());
        }

        if (eventModel != null) {
            broadcastEventMessage(mgr, realm, eventModel.getTimeoutMessage());
        }
        log.info("[REALM_EVENT] Timed out event id={}", evt.getEventId());
    }

    /** Send "ADD|eventId|bossId|x|y|name" marker to all human players. */
    private static void broadcastEventMarker(final RealmManagerServer mgr, final Realm realm,
            final ActiveRealmEvent evt, final Enemy boss, final RealmEventModel eventModel) {
        if (boss == null || eventModel == null) return;
        final float x = boss.getPos().x + boss.getSize() / 2f;
        final float y = boss.getPos().y + boss.getSize() / 2f;
        final String body = String.format("ADD|%d|%d|%.0f|%.0f|%s",
                evt.getEventId(), evt.getBossEnemyId(), x, y, eventModel.getName());
        for (final Player p : realm.getPlayers().values()) {
            if (p == null || p.isHeadless() || p.isBot()) continue;
            try {
                mgr.enqueueServerPacket(p, TextPacket.create("EVENT_MARKER", p.getName(), body));
            } catch (Exception ignored) {}
        }
    }

    private static void broadcastEventMarkerRemove(final RealmManagerServer mgr, final Realm realm,
            final long bossEnemyId) {
        final String body = "REMOVE|" + bossEnemyId;
        for (final Player p : realm.getPlayers().values()) {
            if (p == null || p.isHeadless() || p.isBot()) continue;
            try {
                mgr.enqueueServerPacket(p, TextPacket.create("EVENT_MARKER", p.getName(), body));
            } catch (Exception ignored) {}
        }
    }

    private static void broadcastEventMessage(final RealmManagerServer mgr, final Realm realm,
            final String message) {
        if (message == null || message.isEmpty()) return;
        for (final Player player : realm.getPlayers().values()) {
            if (player == null || player.isHeadless() || player.isBot()) continue;
            try {
                mgr.enqueueServerPacket(player, TextPacket.create("EVENT", player.getName(), message));
            } catch (Exception ignored) {}
        }
    }

    /** Nearest open tile-center within radiusTiles (Chebyshev-ring search). Null if none. */
    private static Vector2f findOpenSpawnNear(final Realm realm, final Vector2f target, final int radiusTiles) {
        if (target == null) return null;
        final TileManager tm = realm.getTileManager();
        final int tileSize = tm.getMapLayers().get(0).getTileSize();
        final int baseTx = (int) (target.x / tileSize);
        final int baseTy = (int) (target.y / tileSize);
        for (int r = 0; r <= radiusTiles; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    final float cx = (baseTx + dx) * tileSize + tileSize * 0.5f;
                    final float cy = (baseTy + dy) * tileSize + tileSize * 0.5f;
                    final Vector2f candidate = new Vector2f(cx, cy);
                    if (tm.isCollisionTile(candidate)) continue;
                    if (tm.isVoidTile(candidate, 0, 0)) continue;
                    return candidate;
                }
            }
        }
        return null;
    }

    private static TerrainGenerationParameters getTerrainParams(final Realm realm) {
        final TileManager tm = realm.getTileManager();
        return tm != null ? tm.getTerrainParams() : null;
    }

    private ServerRealmEventHelper() {}
}
