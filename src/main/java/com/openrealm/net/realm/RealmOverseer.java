package com.openrealm.net.realm;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.openrealm.game.contants.GlobalConstants;
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
import com.openrealm.net.server.packet.TextPacket;

import lombok.extern.slf4j.Slf4j;

/** Realm ecosystem AI: population top-up, quest-kill announcements, realm events, taunts. */
@Slf4j
public class RealmOverseer {
    private static final int CHECK_INTERVAL_TICKS = 320;
    private static final float REPOPULATE_THRESHOLD = 0.5f;
    private static final float OVERPOPULATE_THRESHOLD = 1.5f;
    /** Hard cap per population check; refill drips in over a few cycles. */
    private static final int MAX_REFILL_PER_CHECK = 25;
    private static final int AMBIENT_INTERVAL_TICKS = 5760;
    private static final double EVENT_SPAWN_CHANCE = 0.25;

    private static final int EVENT_CHECK_INTERVAL_TICKS = 6400;
    private static final double REALM_EVENT_SPAWN_CHANCE = 0.15;
    private static final int MAX_CONCURRENT_EVENTS = 1;
    private static final int EVENT_MARKER_INTERVAL_TICKS = 192;

    private final Realm realm;
    private final RealmManagerServer mgr;
    private int tickCounter = 0;
    private int targetPopulation = 0;
    private long lastAnnouncement = 0;

    private final Set<Integer> questEnemyIds = new HashSet<>();
    private final Map<Long, Map<Long, Integer>> damageTracker = new ConcurrentHashMap<>();

    private static final String[] WELCOME_TAUNTS = {
        "Welcome, mortal. You are food for my minions!",
        "Another fool enters my domain...",
        "Your bones will decorate my halls!"
    };

    private static final String[] BOSS_KILL_TAUNTS = {
        "You dare slay my %s? You will pay for this!",
        "My %s has fallen... but more will come!",
        "The death of my %s only fuels my rage!"
    };

    private static final String[] EVENT_SPAWN_TAUNTS = {
        "I have summoned a %s to destroy you!",
        "A %s emerges from the shadows to avenge the fallen!",
        "Feel the wrath of my %s!"
    };

    private static final String[] MINION_WAVE_TAUNTS = {
        "My %s calls forth reinforcements!",
        "The %s summons its minions to defend it!",
        "More servants of the %s pour forth!"
    };

    private static final String[] AMBIENT_TAUNTS = {
        "I am always watching, mortal...",
        "My realms hunger for your blood.",
        "Tread carefully — every step deepens your debt to me.",
        "More creatures crawl from the shadows to greet you.",
        "Your screams will be music to my ears.",
        "I sense your weakness from afar.",
        "The ground itself remembers those who fell here.",
        "Rest if you must — I will not.",
        "Each kill only fuels my next wave."
    };

    public RealmOverseer(Realm realm, RealmManagerServer mgr) {
        this.realm = realm;
        this.mgr = mgr;
        initQuestEnemies();
        log.info("[OVERSEER] Created for realm={} mapId={} (initial enemies={})",
                realm.getRealmId(), realm.getMapId(), realm.getEnemies().size());
    }

    private void initQuestEnemies() {
        for (EnemyModel model : GameDataManager.ENEMIES.values()) {
            if (model.getHealth() >= 2000) {
                questEnemyIds.add(model.getEnemyId());
            }
        }
    }

    /** Called every server tick. */
    public void tick() {
        tickCounter++;

        processActiveEvents();

        if (tickCounter % CHECK_INTERVAL_TICKS == 0) {
            checkPopulation();
        }

        if (tickCounter % EVENT_CHECK_INTERVAL_TICKS == 0) {
            checkRealmEventSpawn();
        }

        // Ambient flavor only fires when at least one human is present.
        if (tickCounter % AMBIENT_INTERVAL_TICKS == 0 && hasHumanPlayer()) {
            broadcastTaunt(randomChoice(AMBIENT_TAUNTS));
        }
    }

    private boolean hasHumanPlayer() {
        for (var p : realm.getPlayers().values()) {
            if (p != null && !p.isHeadless() && !p.isBot()) return true;
        }
        return false;
    }

    private void checkPopulation() {
        if (targetPopulation == 0) {
            // Use the post-spawn snapshot — first check may already be depleted.
            final int initial = realm.getInitialEnemyCount();
            if (initial > 0) targetPopulation = initial;
            else targetPopulation = Math.max(100, realm.getEnemies().size());
            log.info("[OVERSEER] realm={} target population set to {}",
                    realm.getRealmId(), targetPopulation);
        }

        final int currentPop = realm.getEnemies().size();
        final int deficit = targetPopulation - currentPop;
        if (deficit > 0) {
            final int toSpawn = Math.min(deficit, MAX_REFILL_PER_CHECK);
            final int spawned = spawnReplacements(toSpawn);
            log.info("[OVERSEER] realm={} top-up: pop {}/{}, spawned {} (requested {})",
                    realm.getRealmId(), currentPop, targetPopulation, spawned, toSpawn);
        }
    }

    private int spawnReplacements(int count) {
        final TerrainGenerationParameters params = getTerrainParams();
        if (params == null || params.getEnemyGroups() == null) {
            log.warn("[OVERSEER] realm={} spawnReplacements aborted: no terrain params or enemy groups",
                    realm.getRealmId());
            return 0;
        }

        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();
        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (var group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }

        final List<EnemyModel> defaultList = enemiesByGroup.values().iterator().next();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Vector2f spawnPos = realm.getTileManager().getSafePosition();
            if (spawnPos == null) continue;

            List<EnemyModel> spawnList = defaultList;
            float diff = realm.getDifficulty();

            if (hasZones) {
                OverworldZone zone = realm.getTileManager().getZoneForPosition(spawnPos.x, spawnPos.y);
                if (zone != null) {
                    spawnList = enemiesByGroup.getOrDefault(zone.getEnemyGroupOrdinal(), defaultList);
                    diff = Math.max(1.0f, zone.getDifficulty());
                }
            }

            if (spawnList.isEmpty()) continue;
            EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));

            Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
            enemy.setPos(spawnPos);
            realm.addEnemy(enemy);
            spawned++;
        }
        return spawned;
    }

    public void onEnemyKilled(Enemy enemy, long killerPlayerId) {
        EnemyModel model = GameDataManager.ENEMIES.get(enemy.getEnemyId());
        if (model == null) return;

        for (ActiveRealmEvent evt : realm.getActiveRealmEvents()) {
            if (evt.bossEnemyId == enemy.getId() && !evt.completed) {
                completeRealmEvent(evt);
                return;
            }
        }

        if (questEnemyIds.contains(enemy.getEnemyId())) {
            broadcastTaunt(String.format(randomChoice(BOSS_KILL_TAUNTS), model.getName()));

            if (Realm.RANDOM.nextDouble() < EVENT_SPAWN_CHANCE) {
                spawnEventEncounter(model);
            }
        }
    }

    private void spawnEventEncounter(EnemyModel killedBoss) {
        List<EnemyModel> bossEnemies = new ArrayList<>();
        for (EnemyModel m : GameDataManager.ENEMIES.values()) {
            if (m.getHealth() >= 3000 && m.getEnemyId() != killedBoss.getEnemyId()) {
                bossEnemies.add(m);
            }
        }
        if (bossEnemies.isEmpty()) return;

        EnemyModel eventBoss = bossEnemies.get(Realm.RANDOM.nextInt(bossEnemies.size()));
        Vector2f spawnPos = realm.getTileManager().getSafePosition();
        if (spawnPos == null) return;

        float diff = realm.getZoneDifficulty(spawnPos.x, spawnPos.y) * 2.0f;
        Enemy boss = new Enemy(Realm.RANDOM.nextLong(), eventBoss.getEnemyId(),
            spawnPos.clone(), eventBoss.getSize(), eventBoss.getAttackId());
        boss.setDifficulty(diff);
        boss.setHealth((int) (boss.getHealth() * diff));
        boss.getStats().setHp((short) (boss.getStats().getHp() * diff));
        boss.setPos(spawnPos);
        realm.addEnemy(boss);

        broadcastTaunt(String.format(randomChoice(EVENT_SPAWN_TAUNTS), eventBoss.getName()));
        log.info("[OVERSEER] Spawned event encounter: {} at ({}, {})",
            eventBoss.getName(), spawnPos.x, spawnPos.y);
    }

    private void checkRealmEventSpawn() {
        if (GameDataManager.REALM_EVENTS == null || GameDataManager.REALM_EVENTS.isEmpty()) return;
        if (realm.getActiveRealmEvents().size() >= MAX_CONCURRENT_EVENTS) return;
        if (Realm.RANDOM.nextDouble() >= REALM_EVENT_SPAWN_CHANCE) return;

        List<RealmEventModel> candidates = new ArrayList<>(GameDataManager.REALM_EVENTS.values());
        RealmEventModel event = candidates.get(Realm.RANDOM.nextInt(candidates.size()));
        spawnRealmEvent(event);
    }

    /** Random natural-spawn path for a realm event. Returns false if no spawn fits. */
    public boolean spawnRealmEvent(RealmEventModel event) {
        return spawnRealmEvent(event, null);
    }

    /** Spawn at atPos (admin /event) bypassing zone/safety checks, or random when null. */
    public boolean spawnRealmEvent(RealmEventModel event, Vector2f atPos) {
        final TerrainGenerationParameters params = getTerrainParams();
        final boolean hasZones = params != null && params.getZones() != null && !params.getZones().isEmpty();
        final int tileSize = realm.getTileManager().getMapLayers().get(0).getTileSize();

        Vector2f spawnPos = null;
        int tileX = 0, tileY = 0;
        SetPieceModel setPiece = (event.getSetPieceId() >= 0 && GameDataManager.SETPIECES != null)
            ? GameDataManager.SETPIECES.get(event.getSetPieceId()) : null;
        int spWidth = setPiece != null ? setPiece.getWidth() : 1;
        int spHeight = setPiece != null ? setPiece.getHeight() : 1;

        if (atPos != null) {
            spawnPos = atPos.clone();
            tileX = (int) (atPos.x / tileSize) - spWidth / 2;
            tileY = (int) (atPos.y / tileSize) - spHeight / 2;
        } else {
            for (int attempt = 0; attempt < 200; attempt++) {
                Vector2f candidate = realm.getTileManager().getSafePosition();
                if (candidate == null) continue;

                if (hasZones && event.getAllowedZones() != null) {
                    OverworldZone zone = realm.getTileManager().getZoneForPosition(candidate.x, candidate.y);
                    if (zone == null || !event.getAllowedZones().contains(zone.getZoneId())) {
                        continue;
                    }
                }

                if (realm.getTileManager().isVoidTile(candidate, 0, 0)) continue;

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
            int[][][] saved = realm.saveTerrainAt(tileX, tileY, spWidth, spHeight);
            savedBase = saved[0];
            savedColl = saved[1];
            realm.stampSetPiece(setPiece, tileX, tileY, null);
        }

        EnemyModel bossModel = GameDataManager.ENEMIES.get(event.getBossEnemyId());
        if (bossModel == null) {
            log.error("[REALM_EVENT] Boss enemyId {} not found for event '{}'",
                event.getBossEnemyId(), event.getName());
            return false;
        }

        // Boss spawn point may be on a stamped collision tile — push to nearest open.
        final int searchRadius = Math.max(spWidth, spHeight) + 2;
        final Vector2f bossSafePos = findOpenSpawnNear(spawnPos, searchRadius);
        if (bossSafePos != null) spawnPos = bossSafePos;

        float eventMult = Math.max(1, event.getEventMultiplier());
        float diff = realm.getZoneDifficulty(spawnPos.x, spawnPos.y) * eventMult;
        Enemy boss = new Enemy(Realm.RANDOM.nextLong(), bossModel.getEnemyId(),
            spawnPos.clone(), bossModel.getSize(), bossModel.getAttackId());
        boss.setDifficulty(diff);
        boss.setHealth((int) (boss.getHealth() * diff));
        boss.getStats().setHp((short) (boss.getStats().getHp() * diff));
        boss.setPos(spawnPos);
        realm.addEnemy(boss);

        int waveCount = event.getMinionWaves() != null ? event.getMinionWaves().size() : 0;
        long durationMs = event.getDurationSeconds() * 1000L;
        ActiveRealmEvent activeEvent = new ActiveRealmEvent(
            event.getEventId(), boss.getId(), tileX, tileY,
            savedBase, savedColl, waveCount, durationMs);
        realm.getActiveRealmEvents().add(activeEvent);

        String zoneName = "realm";
        if (hasZones) {
            OverworldZone zone = realm.getTileManager().getZoneForPosition(spawnPos.x, spawnPos.y);
            if (zone != null) zoneName = zone.getDisplayName();
        }
        broadcastTaunt(String.format(event.getAnnounceMessage(), zoneName));
        broadcastEventMarker(activeEvent, boss, event);
        log.info("[REALM_EVENT] Spawned '{}' at tile ({}, {}), boss entityId={}, duration={}s",
            event.getName(), tileX, tileY, boss.getId(), event.getDurationSeconds());
        return true;
    }

    /** Send "ADD|eventId|bossId|x|y|name" marker to all human players. */
    private void broadcastEventMarker(ActiveRealmEvent evt, Enemy boss, RealmEventModel eventModel) {
        if (boss == null || eventModel == null) return;
        final float x = boss.getPos().x + boss.getSize() / 2f;
        final float y = boss.getPos().y + boss.getSize() / 2f;
        final String body = String.format("ADD|%d|%d|%.0f|%.0f|%s",
                evt.eventId, evt.bossEnemyId, x, y, eventModel.getName());
        for (var p : realm.getPlayers().values()) {
            if (p == null || p.isHeadless() || p.isBot()) continue;
            try {
                mgr.enqueueServerPacket(p, TextPacket.create("EVENT_MARKER", p.getName(), body));
            } catch (Exception ignored) {}
        }
    }

    private void broadcastEventMarkerRemove(long bossEnemyId) {
        final String body = "REMOVE|" + bossEnemyId;
        for (var p : realm.getPlayers().values()) {
            if (p == null || p.isHeadless() || p.isBot()) continue;
            try {
                mgr.enqueueServerPacket(p, TextPacket.create("EVENT_MARKER", p.getName(), body));
            } catch (Exception ignored) {}
        }
    }

    private void processActiveEvents() {
        if (realm.getActiveRealmEvents().isEmpty()) return;

        final boolean broadcastMarkers = (tickCounter % EVENT_MARKER_INTERVAL_TICKS == 0);

        final Iterator<ActiveRealmEvent> it = realm.getActiveRealmEvents().iterator();
        while (it.hasNext()) {
            ActiveRealmEvent evt = it.next();
            if (evt.completed) {
                it.remove();
                continue;
            }

            if (evt.isExpired()) {
                timeoutRealmEvent(evt);
                it.remove();
                continue;
            }

            Enemy boss = realm.getEnemies().get(evt.bossEnemyId);
            if (boss == null || boss.getDeath()) {
                completeRealmEvent(evt);
                it.remove();
                continue;
            }

            final RealmEventModel evtModel = GameDataManager.REALM_EVENTS.get(evt.eventId);
            if (broadcastMarkers && evtModel != null) {
                broadcastEventMarker(evt, boss, evtModel);
            }

            RealmEventModel eventModel = evtModel;
            if (eventModel == null || eventModel.getMinionWaves() == null) continue;

            float bossHpPercent = (float) boss.getHealth() / (float) (boss.getStats().getHp());
            for (int i = 0; i < eventModel.getMinionWaves().size(); i++) {
                if (evt.wavesTriggered[i]) continue;
                MinionWave wave = eventModel.getMinionWaves().get(i);
                if (bossHpPercent <= wave.getTriggerHpPercent()) {
                    evt.wavesTriggered[i] = true;
                    spawnMinionWave(evt, boss, wave, eventModel.getName());
                }
            }

            evt.minionIds.removeIf(id -> {
                Enemy m = realm.getEnemies().get(id);
                return m == null || m.getDeath();
            });
        }
    }

    /** Nearest open tile-center within radiusTiles (Chebyshev-ring search). Null if none. */
    private Vector2f findOpenSpawnNear(Vector2f target, int radiusTiles) {
        if (target == null) return null;
        final var tm = realm.getTileManager();
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

    private void spawnMinionWave(ActiveRealmEvent evt, Enemy boss, MinionWave wave, String eventName) {
        EnemyModel minionModel = GameDataManager.ENEMIES.get(wave.getEnemyId());
        if (minionModel == null) return;

        float angleStep = (float) (2 * Math.PI / Math.max(1, wave.getCount()));
        for (int i = 0; i < wave.getCount(); i++) {
            float angle = angleStep * i;
            float ox = (float) Math.cos(angle) * wave.getOffset();
            float oy = (float) Math.sin(angle) * wave.getOffset();
            Vector2f minionPos = new Vector2f(boss.getPos().x + ox, boss.getPos().y + oy);

            Vector2f safePos = findOpenSpawnNear(minionPos, 4);
            if (safePos != null) minionPos = safePos;

            float waveMult = Math.max(1, wave.getEventMultiplier());
            float minionDiff = realm.getZoneDifficulty(minionPos.x, minionPos.y) * waveMult;
            Enemy minion = new Enemy(Realm.RANDOM.nextLong(), minionModel.getEnemyId(),
                minionPos, minionModel.getSize(), minionModel.getAttackId());
            minion.setDifficulty(minionDiff);
            minion.setHealth((int) (minion.getHealth() * minionDiff));
            minion.getStats().setHp((short) (minion.getStats().getHp() * minionDiff));
            realm.addEnemy(minion);
            evt.minionIds.add(minion.getId());
        }

        broadcastTaunt(String.format(randomChoice(MINION_WAVE_TAUNTS), eventName));
        log.info("[REALM_EVENT] Spawned minion wave: {}x {} for event '{}'",
            wave.getCount(), minionModel.getName(), eventName);
    }

    private void completeRealmEvent(ActiveRealmEvent evt) {
        evt.completed = true;
        broadcastEventMarkerRemove(evt.bossEnemyId);
        RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.eventId);

        for (long minionId : evt.minionIds) {
            Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        if (evt.savedBase != null && evt.savedCollision != null) {
            realm.restoreTerrainAt(evt.tileX, evt.tileY, evt.savedBase, evt.savedCollision);
        }

        if (eventModel != null) {
            broadcastTaunt(eventModel.getDefeatMessage());
        }
        log.info("[REALM_EVENT] Completed event id={}", evt.eventId);
    }

    private void timeoutRealmEvent(ActiveRealmEvent evt) {
        evt.completed = true;
        broadcastEventMarkerRemove(evt.bossEnemyId);
        RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.eventId);

        Enemy boss = realm.getEnemies().get(evt.bossEnemyId);
        if (boss != null && !boss.getDeath()) {
            realm.getExpiredEnemies().add(evt.bossEnemyId);
            realm.removeEnemy(boss);
        }

        for (long minionId : evt.minionIds) {
            Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        if (evt.savedBase != null && evt.savedCollision != null) {
            realm.restoreTerrainAt(evt.tileX, evt.tileY, evt.savedBase, evt.savedCollision);
        }

        if (eventModel != null) {
            broadcastTaunt(eventModel.getTimeoutMessage());
        }
        log.info("[REALM_EVENT] Timed out event id={}", evt.eventId);
    }

    public void trackDamage(long enemyId, long playerId, int damage) {
        damageTracker.computeIfAbsent(enemyId, k -> new ConcurrentHashMap<>())
            .merge(playerId, damage, Integer::sum);
    }

    public long getTopDamageDealer(long enemyId) {
        Map<Long, Integer> dmgMap = damageTracker.get(enemyId);
        if (dmgMap == null || dmgMap.isEmpty()) return -1;
        return dmgMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(-1L);
    }

    public boolean qualifiesForLoot(long enemyId, long playerId) {
        Map<Long, Integer> dmgMap = damageTracker.get(enemyId);
        if (dmgMap == null) return true;
        int playerDmg = dmgMap.getOrDefault(playerId, 0);
        int totalDmg = dmgMap.values().stream().mapToInt(Integer::intValue).sum();
        if (totalDmg == 0) return true;
        return (float) playerDmg / totalDmg >= GlobalConstants.SOULBOUND_DAMAGE_THRESHOLD;
    }

    /** Player IDs whose damage share meets SOULBOUND_DAMAGE_THRESHOLD on this enemy. */
    public List<Long> getQualifyingPlayers(long enemyId) {
        List<Long> qualifyingPlayers = new ArrayList<>();
        Map<Long, Integer> dmgMap = damageTracker.get(enemyId);
        if (dmgMap == null || dmgMap.isEmpty()) {
            return qualifyingPlayers;
        }
        int totalDmg = dmgMap.values().stream().mapToInt(Integer::intValue).sum();
        if (totalDmg == 0) {
            return qualifyingPlayers;
        }
        for (Map.Entry<Long, Integer> entry : dmgMap.entrySet()) {
            float ratio = (float) entry.getValue() / totalDmg;
            if (ratio >= GlobalConstants.SOULBOUND_DAMAGE_THRESHOLD) {
                qualifyingPlayers.add(entry.getKey());
            }
        }
        return qualifyingPlayers;
    }

    public Map<Long, Integer> getDamageMap(long enemyId) {
        return damageTracker.get(enemyId);
    }

    public void clearDamageTracking(long enemyId) {
        damageTracker.remove(enemyId);
    }

    public void welcomePlayer(Player player) {
        String taunt = randomChoice(WELCOME_TAUNTS);
        try {
            mgr.enqueueServerPacket(player,
                TextPacket.create("Overseer", player.getName(), taunt));
        } catch (Exception e) {
            log.error("[OVERSEER] Failed to send welcome: {}", e.getMessage());
        }
    }

    private void broadcastTaunt(String message) {
        // 750ms throttle protects against bursts without swallowing distinct events.
        long now = Instant.now().toEpochMilli();
        if (now - lastAnnouncement < 750) return;
        lastAnnouncement = now;

        log.info("[OVERSEER] realm={} taunt: {}", realm.getRealmId(), message);
        int sent = 0;
        for (var player : realm.getPlayers().values()) {
            if (player == null || player.isHeadless() || player.isBot()) continue;
            try {
                mgr.enqueueServerPacket(player,
                    TextPacket.create("Overseer", player.getName(), message));
                sent++;
            } catch (Exception e) {
                log.warn("[OVERSEER] failed to send taunt to player {}: {}",
                        player.getId(), e.getMessage());
            }
        }
        if (sent == 0) {
            log.info("[OVERSEER] realm={} taunt skipped — no human players in realm",
                    realm.getRealmId());
        }
    }

    private String getPlayerName(long playerId) {
        var player = realm.getPlayers().get(playerId);
        return player != null ? player.getName() : "Unknown";
    }

    private TerrainGenerationParameters getTerrainParams() {
        if (GameDataManager.TERRAINS == null) return null;
        var mapModel = GameDataManager.MAPS.get(realm.getMapId());
        if (mapModel != null && mapModel.getTerrainId() >= 0) {
            return GameDataManager.TERRAINS.get(mapModel.getTerrainId());
        }
        return GameDataManager.TERRAINS.get(0);
    }

    private String randomChoice(String[] arr) {
        return arr[Realm.RANDOM.nextInt(arr.length)];
    }
}
