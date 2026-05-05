package com.openrealm.net.realm;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.MinionWave;
import com.openrealm.game.model.OverworldZone;
import com.openrealm.game.model.RealmEventModel;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.net.server.packet.TextPacket;

import lombok.extern.slf4j.Slf4j;

/**
 * Overseer AI that manages a realm's ecosystem:
 * - Monitors enemy populations per zone
 * - Spawns replacement enemies when population drops
 * - Tracks quest/boss enemies and announces kills
 * - Spawns event encounters when bosses die
 * - Manages realm events (global boss encounters with terrain + minion waves)
 * - Broadcasts taunts and announcements
 */
@Slf4j
public class RealmOverseer {
    // Population top-up cadence. Was 640 ticks (~10s) which made the realm
    // feel empty after a single sweep — players cleared an area and waited
    // 10s with nothing to fight. 320 ticks (~5s) gives a noticeable
    // refresh rate without hammering CPU.
    private static final int CHECK_INTERVAL_TICKS = 320;
    private static final float REPOPULATE_THRESHOLD = 0.5f;
    private static final float OVERPOPULATE_THRESHOLD = 1.5f;
    /** Hard cap on enemies spawned per population check, so a freshly
     *  cleared zone doesn't get carpet-bombed in one tick — the refill
     *  drips in over a few cycles, ~5 enemies/sec sustained. */
    private static final int MAX_REFILL_PER_CHECK = 25;
    /** Every N ticks (~90s @ 64 TPS) the overseer drops an ambient line
     *  in chat so players know it's watching. Without this the overseer
     *  is silent unless a boss/event fires, which can be many minutes. */
    private static final int AMBIENT_INTERVAL_TICKS = 5760;
    private static final double EVENT_SPAWN_CHANCE = 0.25;

    // Realm events
    private static final int EVENT_CHECK_INTERVAL_TICKS = 6400; // ~100 seconds at 64 TPS
    private static final double REALM_EVENT_SPAWN_CHANCE = 0.15; // 15% chance per check
    private static final int MAX_CONCURRENT_EVENTS = 1;

    private final Realm realm;
    private final RealmManagerServer mgr;
    private int tickCounter = 0;
    private int targetPopulation = 0;
    private long lastAnnouncement = 0;

    private final Set<Integer> questEnemyIds = new HashSet<>();
    private final Map<Long, Map<Long, Integer>> damageTracker = new ConcurrentHashMap<>();

    // Taunts
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

    // Periodic ambient lines so the overseer feels present even during
    // long stretches between boss events.
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

    /**
     * Called every server tick. Performs periodic ecosystem management.
     */
    public void tick() {
        tickCounter++;

        // Process active realm events every tick (minion wave checks, timeout)
        processActiveEvents();

        if (tickCounter % CHECK_INTERVAL_TICKS == 0) {
            checkPopulation();
        }

        // Periodically roll for new realm event
        if (tickCounter % EVENT_CHECK_INTERVAL_TICKS == 0) {
            checkRealmEventSpawn();
        }

        // Ambient flavor — only fires when there's at least one human
        // player in the realm. Ensures the overseer is visibly active in
        // chat even between major events.
        if (tickCounter % AMBIENT_INTERVAL_TICKS == 0 && hasHumanPlayer()) {
            broadcastTaunt(randomChoice(AMBIENT_TAUNTS));
        }
    }

    /** Returns true if at least one non-bot, non-headless player is in
     *  this realm. Skips ambient broadcasts on a realm full of bots. */
    private boolean hasHumanPlayer() {
        for (var p : realm.getPlayers().values()) {
            if (p != null && !p.isHeadless() && !p.isBot()) return true;
        }
        return false;
    }

    private void checkPopulation() {
        if (targetPopulation == 0) {
            // Snapshot the realm's initial population on the first check.
            // initialEnemyCount is captured by Realm during spawnRandomEnemies,
            // so it reflects the post-spawn target rather than whatever the
            // pop happens to be at the first check (which could already be
            // depleted by players in the 5s grace window).
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
            // Promoted from debug to info — the user reported "no respawn
            // happening" and the debug logs were invisible in journalctl.
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

        // Check if this kill completes an active realm event
        for (Realm.ActiveRealmEvent evt : realm.getActiveRealmEvents()) {
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

    // ========== REALM EVENT SYSTEM ==========

    /**
     * Periodically roll for a new realm event to spawn.
     */
    private void checkRealmEventSpawn() {
        if (GameDataManager.REALM_EVENTS == null || GameDataManager.REALM_EVENTS.isEmpty()) return;
        if (realm.getActiveRealmEvents().size() >= MAX_CONCURRENT_EVENTS) return;
        if (Realm.RANDOM.nextDouble() >= REALM_EVENT_SPAWN_CHANCE) return;

        // Pick a random event
        List<RealmEventModel> candidates = new ArrayList<>(GameDataManager.REALM_EVENTS.values());
        RealmEventModel event = candidates.get(Realm.RANDOM.nextInt(candidates.size()));
        spawnRealmEvent(event);
    }

    /**
     * Spawn a realm event: place setpiece terrain, spawn boss + initial minions, announce.
     * Random natural-spawn path: searches for an allowed-zone, non-void tile.
     * Returns true on success, false if no spawn could be placed.
     */
    public boolean spawnRealmEvent(RealmEventModel event) {
        return spawnRealmEvent(event, null);
    }

    /**
     * Spawn a realm event at the given world position (e.g. an admin's
     * /event command should drop the encounter on top of the player). When
     * atPos is non-null, all zone/safety checks are bypassed — setpieces
     * are allowed to terraform the map freely; obstacles get overwritten.
     * When atPos is null, the legacy random search is used.
     */
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
            // Forced spawn (admin /event): place the setpiece centered on
            // the requested point. No zone or void checks — setpieces are
            // allowed to fully terraform whatever's underneath.
            spawnPos = atPos.clone();
            tileX = (int) (atPos.x / tileSize) - spWidth / 2;
            tileY = (int) (atPos.y / tileSize) - spHeight / 2;
        } else {
            for (int attempt = 0; attempt < 200; attempt++) {
                Vector2f candidate = realm.getTileManager().getSafePosition();
                if (candidate == null) continue;

                // Zone check
                if (hasZones && event.getAllowedZones() != null) {
                    OverworldZone zone = realm.getTileManager().getZoneForPosition(candidate.x, candidate.y);
                    if (zone == null || !event.getAllowedZones().contains(zone.getZoneId())) {
                        continue;
                    }
                }

                // Check tile isn't void
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

        // Save existing terrain and stamp setpiece
        int[][] savedBase = null;
        int[][] savedColl = null;
        if (setPiece != null) {
            int[][][] saved = realm.saveTerrainAt(tileX, tileY, spWidth, spHeight);
            savedBase = saved[0];
            savedColl = saved[1];
            realm.stampSetPiece(setPiece, tileX, tileY, null);
        }

        // Spawn the boss
        EnemyModel bossModel = GameDataManager.ENEMIES.get(event.getBossEnemyId());
        if (bossModel == null) {
            log.error("[REALM_EVENT] Boss enemyId {} not found for event '{}'",
                event.getBossEnemyId(), event.getName());
            return false;
        }

        // Setpieces terraform but the boss-spawn point may still land on a
        // collision tile (e.g. a pillar in the center of the stamped art).
        // Push to the nearest open tile within a radius covering the whole
        // setpiece so the boss is never stuck on spawn.
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

        // Create the active event state
        int waveCount = event.getMinionWaves() != null ? event.getMinionWaves().size() : 0;
        long durationMs = event.getDurationSeconds() * 1000L;
        Realm.ActiveRealmEvent activeEvent = new Realm.ActiveRealmEvent(
            event.getEventId(), boss.getId(), tileX, tileY,
            savedBase, savedColl, waveCount, durationMs);
        realm.getActiveRealmEvents().add(activeEvent);

        // Announce
        String zoneName = "realm";
        if (hasZones) {
            OverworldZone zone = realm.getTileManager().getZoneForPosition(spawnPos.x, spawnPos.y);
            if (zone != null) zoneName = zone.getDisplayName();
        }
        broadcastTaunt(String.format(event.getAnnounceMessage(), zoneName));
        // Immediate marker broadcast so the pin appears on the minimap
        // before the next 3s periodic re-broadcast.
        broadcastEventMarker(activeEvent, boss, event);
        log.info("[REALM_EVENT] Spawned '{}' at tile ({}, {}), boss entityId={}, duration={}s",
            event.getName(), tileX, tileY, boss.getId(), event.getDurationSeconds());
        return true;
    }

    // Re-broadcast event markers every 192 ticks (~3s @ 64 TPS) so the
    // minimap stays populated even for newly-joined players. Lower than
    // EVENT_CHECK_INTERVAL_TICKS so markers appear quickly after spawn.
    private static final int EVENT_MARKER_INTERVAL_TICKS = 192;

    /**
     * Broadcast a marker for one active event so clients can pin it on
     * the minimap. Sent as a TextPacket with sender "EVENT_MARKER" —
     * the client filters this from chat and parses the body into an
     * {eventId, x, y, name} record. REMOVE messages clear the pin.
     */
    private void broadcastEventMarker(Realm.ActiveRealmEvent evt, Enemy boss, RealmEventModel eventModel) {
        if (boss == null || eventModel == null) return;
        // Format: "ADD|eventId|bossId|x|y|name"
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

    /**
     * Process all active realm events each tick:
     * - Check minion wave HP thresholds
     * - Check timeout
     * - Periodically re-broadcast event-marker pins so the minimap UI
     *   stays in sync (also covers players who joined mid-event).
     */
    private void processActiveEvents() {
        if (realm.getActiveRealmEvents().isEmpty()) return;

        final boolean broadcastMarkers = (tickCounter % EVENT_MARKER_INTERVAL_TICKS == 0);

        final Iterator<Realm.ActiveRealmEvent> it = realm.getActiveRealmEvents().iterator();
        while (it.hasNext()) {
            Realm.ActiveRealmEvent evt = it.next();
            if (evt.completed) {
                it.remove();
                continue;
            }

            // Check timeout
            if (evt.isExpired()) {
                timeoutRealmEvent(evt);
                it.remove();
                continue;
            }

            // Check boss still alive
            Enemy boss = realm.getEnemies().get(evt.bossEnemyId);
            if (boss == null || boss.getDeath()) {
                completeRealmEvent(evt);
                it.remove();
                continue;
            }

            // Re-broadcast minimap marker every few seconds so the pin
            // stays present and tracks the boss's current position.
            final RealmEventModel evtModel = GameDataManager.REALM_EVENTS.get(evt.eventId);
            if (broadcastMarkers && evtModel != null) {
                broadcastEventMarker(evt, boss, evtModel);
            }

            // Check minion wave HP thresholds
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

            // Clean up dead minions from tracking set
            evt.minionIds.removeIf(id -> {
                Enemy m = realm.getEnemies().get(id);
                return m == null || m.getDeath();
            });
        }
    }

    /**
     * Spawn a wave of minions around the boss.
     */
    /**
     * Return the nearest tile-center inside {@code radiusTiles} of
     * {@code target} that is neither a collision tile nor a void tile.
     * Searches in expanding rings (Chebyshev distance) so the closest
     * candidate wins. Returns the original target if it's already open,
     * or null if no open tile exists within the search radius.
     */
    private Vector2f findOpenSpawnNear(Vector2f target, int radiusTiles) {
        if (target == null) return null;
        final var tm = realm.getTileManager();
        final int tileSize = tm.getMapLayers().get(0).getTileSize();
        final int baseTx = (int) (target.x / tileSize);
        final int baseTy = (int) (target.y / tileSize);
        for (int r = 0; r <= radiusTiles; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    // Only the ring at distance r — skip interior cells
                    // already tested in earlier passes.
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

    private void spawnMinionWave(Realm.ActiveRealmEvent evt, Enemy boss, MinionWave wave, String eventName) {
        EnemyModel minionModel = GameDataManager.ENEMIES.get(wave.getEnemyId());
        if (minionModel == null) return;

        float angleStep = (float) (2 * Math.PI / Math.max(1, wave.getCount()));
        for (int i = 0; i < wave.getCount(); i++) {
            float angle = angleStep * i;
            float ox = (float) Math.cos(angle) * wave.getOffset();
            float oy = (float) Math.sin(angle) * wave.getOffset();
            Vector2f minionPos = new Vector2f(boss.getPos().x + ox, boss.getPos().y + oy);

            // Push out of collision tiles so a minion never spawns wedged
            // inside a wall / pillar / decoration. Search radius is small —
            // we just need the nearest free neighbor.
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

    /**
     * Complete a realm event (boss killed): restore terrain, cleanup minions, announce.
     */
    private void completeRealmEvent(Realm.ActiveRealmEvent evt) {
        evt.completed = true;
        broadcastEventMarkerRemove(evt.bossEnemyId);
        RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.eventId);

        // Remove surviving minions
        for (long minionId : evt.minionIds) {
            Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        // Restore terrain
        if (evt.savedBase != null && evt.savedCollision != null) {
            realm.restoreTerrainAt(evt.tileX, evt.tileY, evt.savedBase, evt.savedCollision);
        }

        if (eventModel != null) {
            broadcastTaunt(eventModel.getDefeatMessage());
        }
        log.info("[REALM_EVENT] Completed event id={}", evt.eventId);
    }

    /**
     * Timeout a realm event: despawn boss + minions, restore terrain, announce.
     */
    private void timeoutRealmEvent(Realm.ActiveRealmEvent evt) {
        evt.completed = true;
        broadcastEventMarkerRemove(evt.bossEnemyId);
        RealmEventModel eventModel = GameDataManager.REALM_EVENTS.get(evt.eventId);

        // Remove boss
        Enemy boss = realm.getEnemies().get(evt.bossEnemyId);
        if (boss != null && !boss.getDeath()) {
            realm.getExpiredEnemies().add(evt.bossEnemyId);
            realm.removeEnemy(boss);
        }

        // Remove minions
        for (long minionId : evt.minionIds) {
            Enemy minion = realm.getEnemies().get(minionId);
            if (minion != null && !minion.getDeath()) {
                realm.getExpiredEnemies().add(minionId);
                realm.removeEnemy(minion);
            }
        }

        // Restore terrain
        if (evt.savedBase != null && evt.savedCollision != null) {
            realm.restoreTerrainAt(evt.tileX, evt.tileY, evt.savedBase, evt.savedCollision);
        }

        if (eventModel != null) {
            broadcastTaunt(eventModel.getTimeoutMessage());
        }
        log.info("[REALM_EVENT] Timed out event id={}", evt.eventId);
    }

    // ========== DAMAGE TRACKING ==========

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

    /**
     * Returns a list of all player IDs who dealt at least the soulbound damage
     * threshold percentage to this enemy. Used to determine which players
     * qualify for soulbound loot drops.
     */
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

    /**
     * Returns the damage map for an enemy, used for loot roll calculations.
     */
    public Map<Long, Integer> getDamageMap(long enemyId) {
        return damageTracker.get(enemyId);
    }

    public void clearDamageTracking(long enemyId) {
        damageTracker.remove(enemyId);
    }

    // ========== MESSAGING ==========

    public void welcomePlayer(com.openrealm.game.entity.Player player) {
        String taunt = randomChoice(WELCOME_TAUNTS);
        try {
            mgr.enqueueServerPacket(player,
                TextPacket.create("Overseer", player.getName(), taunt));
        } catch (Exception e) {
            log.error("[OVERSEER] Failed to send welcome: {}", e.getMessage());
        }
    }

    private void broadcastTaunt(String message) {
        // 750ms throttle (was 3s) — protects against bursts of identical
        // taunts from rapid-fire boss kills, but no longer eats every
        // overseer message after a single boss-down event for several
        // seconds. Player-visible messages were going missing because
        // multiple events fired within the old 3s window.
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

    // ========== UTILITIES ==========

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
