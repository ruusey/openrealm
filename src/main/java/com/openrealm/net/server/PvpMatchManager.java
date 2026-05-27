package com.openrealm.net.server;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.MapModel;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.CommandHandler;
import com.openrealm.util.WorkerThread;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PvpMatchManager {

    public static final int PVP_ARENA_MAP_ID = 34;
    /** Basic melee minion. Pirate_0 from enemies.json — 100 HP, modest projectile attack. */
    public static final int MINION_BASIC_ENEMY_ID = 6;
    /** Stronger caster minion. Purple_Mage_0 — 600 HP, faster projectile. */
    public static final int MINION_STRONG_ENEMY_ID = 8;
    public static final int MINION_BASIC_SIZE = 32;
    public static final int MINION_STRONG_SIZE = 32;
    /** Tick the match-state worker also detects empty arenas and tears them down,
     *  so a desync where players exit by means other than match-end doesn't leak realms. */
    public static final long EMPTY_ARENA_GRACE_MS = 5_000L;
    public static final long CHALLENGE_TTL_MS = 30_000L;
    public static final long WAVE_INTERVAL_MS = 30_000L;
    public static final long FIRST_WAVE_DELAY_MS = 10_000L;
    /** Per-lane composition: 4× basic followed by 1× stronger (LoL-style melee + caster column). */
    public static final int BASIC_PER_LANE = 4;
    public static final int STRONG_PER_LANE = 1;
    public static final int LANES_PER_TEAM = 3;
    public static final long MATCH_START_INVULN_MS = 3_000L;
    public static final byte TEAM_A = 1;
    public static final byte TEAM_B = 2;

    public static RealmManagerServer mgr;
    public static boolean shutdown = false;

    // challengerId -> [targetId, expiresAtMs]
    private static final Map<Long, long[]> pendingChallenges = new ConcurrentHashMap<>();
    // realmId -> PvpMatch
    private static final Map<Long, PvpMatch> activeMatches = new ConcurrentHashMap<>();
    // playerId -> PvpMatch
    private static final Map<Long, PvpMatch> playerMatches = new ConcurrentHashMap<>();

    public static final class PvpMatch {
        public final long realmId;
        public final long playerAId;
        public final long playerBId;
        public final long startedAtMs;
        public volatile long lastWaveAtMs;
        public volatile byte winnerTeam;

        public PvpMatch(long realmId, long playerAId, long playerBId, long startedAtMs) {
            this.realmId = realmId;
            this.playerAId = playerAId;
            this.playerBId = playerBId;
            this.startedAtMs = startedAtMs;
            this.lastWaveAtMs = startedAtMs;
            this.winnerTeam = 0;
        }
    }

    public static PvpMatch getMatchByRealm(long realmId) { return activeMatches.get(realmId); }
    public static PvpMatch getMatchByPlayer(long playerId) { return playerMatches.get(playerId); }
    public static boolean isInMatch(long playerId) { return playerMatches.containsKey(playerId); }

    /** True when both players are on the same team in the same active match. */
    public static boolean sameTeam(long playerAId, long playerBId) {
        if (playerAId == playerBId) return true;
        final PvpMatch m = playerMatches.get(playerAId);
        if (m == null) return false;
        final PvpMatch m2 = playerMatches.get(playerBId);
        return m == m2;
    }

    /** Background loop: expire stale challenges + spawn minion waves on the cadence. */
    public static void runExpiryAndWaveCheck() {
        final Runnable check = () -> {
            while (!shutdown) {
                try {
                    final long now = Instant.now().toEpochMilli();
                    final Iterator<Map.Entry<Long, long[]>> it = pendingChallenges.entrySet().iterator();
                    while (it.hasNext()) {
                        final Map.Entry<Long, long[]> e = it.next();
                        if (now > e.getValue()[1]) it.remove();
                    }
                    for (final PvpMatch match : activeMatches.values()) {
                        if (match.winnerTeam != 0) continue;
                        // Empty-arena reaper: if no human players remain in the arena, tear it down.
                        // Skip during the start-of-match grace window so we don't reap before LoadPacket
                        // round-trips finish.
                        if (now - match.startedAtMs > EMPTY_ARENA_GRACE_MS) {
                            final Realm arena = mgr.getRealms().get(match.realmId);
                            if (arena == null || arena.getPlayers().isEmpty()) {
                                log.info("[PVP] Reaping empty arena realmId={} (no players left)", match.realmId);
                                match.winnerTeam = -1; // sentinel "no winner / reaped"
                                endMatch(match);
                                continue;
                            }
                        }
                        if (now - match.startedAtMs < FIRST_WAVE_DELAY_MS) continue;
                        if (now - match.lastWaveAtMs >= WAVE_INTERVAL_MS) {
                            spawnMinionWave(match);
                            match.lastWaveAtMs = now;
                        }
                    }
                    Thread.sleep(500);
                } catch (Exception ex) {
                    log.error("[PVP] Tick failed. Reason: {}", ex.getMessage());
                }
            }
        };
        WorkerThread.submitAndForkRun(check);
    }

    @CommandHandler(value = "pvp", description = "Challenge a player to a PvP battle")
    public static void invokeChallenge(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() != 1) {
            throw new IllegalArgumentException("Usage: /pvp {PLAYER_NAME}");
        }
        if (isInMatch(target.getId())) throw new Exception("You are already in a PvP match");

        final Player opponent = mgr.findPlayerByName(message.getArgs().get(0));
        if (opponent == null) throw new IllegalArgumentException("Unable to find player " + message.getArgs().get(0));
        if (opponent.getId() == target.getId()) throw new IllegalArgumentException("You cannot challenge yourself!");
        if (isInMatch(opponent.getId())) throw new Exception(opponent.getName() + " is already in a PvP match");

        pendingChallenges.put(target.getId(),
                new long[] { opponent.getId(), Instant.now().toEpochMilli() + CHALLENGE_TTL_MS });
        mgr.enqueueServerPacket(opponent, TextPacket.create(target.getName(), opponent.getName(),
                target.getName() + " challenges you to a PvP battle! /pvpaccept or /pvpdecline (30s)"));
        mgr.enqueueServerPacket(target, TextPacket.create("SYSTEM", target.getName(),
                "PvP challenge sent to " + opponent.getName()));
        log.info("[PVP] {} challenged {}", target.getName(), opponent.getName());
    }

    @CommandHandler(value = "pvpaccept", description = "Accept a pending PvP challenge")
    public static void acceptChallenge(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        final long now = Instant.now().toEpochMilli();
        Long challengerId = null;
        for (final Map.Entry<Long, long[]> e : pendingChallenges.entrySet()) {
            if (e.getValue()[0] == target.getId() && now <= e.getValue()[1]) {
                challengerId = e.getKey();
                break;
            }
        }
        if (challengerId == null) throw new Exception("No pending PvP challenges to accept");

        final Player challenger = mgr.getPlayerById(challengerId);
        pendingChallenges.remove(challengerId);
        if (challenger == null) throw new Exception("Challenger is no longer online");

        startMatch(challenger, target);
    }

    @CommandHandler(value = "pvpdecline", description = "Decline a pending PvP challenge")
    public static void declineChallenge(RealmManagerServer mgr, Player target, ServerCommandMessage message) {
        Long challengerId = null;
        for (final Map.Entry<Long, long[]> e : pendingChallenges.entrySet()) {
            if (e.getValue()[0] == target.getId()) {
                challengerId = e.getKey();
                break;
            }
        }
        if (challengerId == null) return;
        pendingChallenges.remove(challengerId);
        final Player challenger = mgr.getPlayerById(challengerId);
        if (challenger != null) {
            mgr.enqueueServerPacket(challenger, TextPacket.create("SYSTEM", challenger.getName(),
                    target.getName() + " declined your PvP challenge"));
        }
        mgr.enqueueServerPacket(target, TextPacket.create("SYSTEM", target.getName(), "Challenge declined"));
    }

    @CommandHandler(value = "pvpforfeit", description = "Forfeit the current PvP match")
    public static void forfeitMatch(RealmManagerServer mgr, Player target, ServerCommandMessage message) {
        final PvpMatch match = playerMatches.get(target.getId());
        if (match == null || match.winnerTeam != 0) return;
        match.winnerTeam = (target.getPvpTeamId() == TEAM_A) ? TEAM_B : TEAM_A;
        endMatch(match);
    }

    private static void startMatch(Player playerA, Player playerB) {
        try {
            mgr.acquireRealmLock();
            try {
                final MapModel mapModel = GameDataManager.MAPS.get(PVP_ARENA_MAP_ID);
                if (mapModel == null) {
                    mgr.enqueueServerPacket(playerA, TextPacket.create("SYSTEM", playerA.getName(),
                            "PvP arena map " + PVP_ARENA_MAP_ID + " is not loaded on the server"));
                    return;
                }
                final Realm arena = new Realm(true, PVP_ARENA_MAP_ID);

                final Realm fromRealmA = mgr.findPlayerRealm(playerA.getId());
                final Realm fromRealmB = mgr.findPlayerRealm(playerB.getId());
                if (fromRealmA != null) fromRealmA.removePlayer(playerA);
                if (fromRealmB != null) fromRealmB.removePlayer(playerB);

                final Vector2f spawnA = pickSpawn(mapModel.getTeamASpawnPoints(), mapModel);
                final Vector2f spawnB = pickSpawn(mapModel.getTeamBSpawnPoints(), mapModel);
                log.info("[PVP] startMatch resolved spawns — teamA={} (from {} pts), teamB={} (from {} pts), pvpFlag={}, lanesA={}, lanesB={}",
                        spawnA, mapModel.getTeamASpawnPoints() == null ? "null" : mapModel.getTeamASpawnPoints().size(),
                        spawnB, mapModel.getTeamBSpawnPoints() == null ? "null" : mapModel.getTeamBSpawnPoints().size(),
                        mapModel.isPvp(),
                        mapModel.getPvpLanesTeamA() == null ? "null" : mapModel.getPvpLanesTeamA().size(),
                        mapModel.getPvpLanesTeamB() == null ? "null" : mapModel.getPvpLanesTeamB().size());

                preparePlayerForMatch(playerA, spawnA, TEAM_A);
                preparePlayerForMatch(playerB, spawnB, TEAM_B);

                mgr.addRealm(arena);
                arena.addPlayer(playerA);
                arena.addPlayer(playerB);

                mgr.clearPlayerState(playerA.getId());
                mgr.clearPlayerState(playerB.getId());
                mgr.invalidateRealmLoadState(arena);
                ServerGameLogic.sendImmediateLoadMap(mgr, arena, playerA);
                ServerGameLogic.sendImmediateLoadMap(mgr, arena, playerB);
                ServerGameLogic.onPlayerJoin(mgr, arena, playerA);
                ServerGameLogic.onPlayerJoin(mgr, arena, playerB);

                playerA.addEffect(StatusEffectType.INVINCIBLE, MATCH_START_INVULN_MS);
                playerB.addEffect(StatusEffectType.INVINCIBLE, MATCH_START_INVULN_MS);

                final long now = Instant.now().toEpochMilli();
                final PvpMatch match = new PvpMatch(arena.getRealmId(), playerA.getId(), playerB.getId(), now);
                activeMatches.put(arena.getRealmId(), match);
                playerMatches.put(playerA.getId(), match);
                playerMatches.put(playerB.getId(), match);

                final String startMsg = "PvP match started! Damage scaled 1/10, friendly fire off. First minion wave in 10s.";
                mgr.enqueueServerPacket(playerA, TextPacket.create("SYSTEM", playerA.getName(), startMsg));
                mgr.enqueueServerPacket(playerB, TextPacket.create("SYSTEM", playerB.getName(), startMsg));
                log.info("[PVP] Match started realmId={} A={}({}) vs B={}({})",
                        arena.getRealmId(), playerA.getName(), playerA.getId(),
                        playerB.getName(), playerB.getId());
            } finally {
                mgr.releaseRealmLock();
            }
        } catch (Exception e) {
            log.error("[PVP] Failed to start match. Reason: {}", e.getMessage(), e);
        }
    }

    private static void preparePlayerForMatch(Player player, Vector2f spawn, byte team) {
        player.setPos(spawn);
        player.setPvpTeamId(team);
        player.setTeleported(true);
        player.setHealth(player.getStats().getHp());
        player.setMana(player.getStats().getMp());
    }

    private static Vector2f pickSpawn(List<float[]> spawns, MapModel mapModel) {
        if (spawns == null || spawns.isEmpty()) return mapModel.getCenter();
        final float[] sp = spawns.get(Realm.RANDOM.nextInt(spawns.size()));
        return new Vector2f(sp[0], sp[1]);
    }

    private static void spawnMinionWave(PvpMatch match) {
        try {
            final Realm arena = mgr.getRealms().get(match.realmId);
            if (arena == null) return;
            final MapModel mapModel = GameDataManager.MAPS.get(arena.getMapId());
            if (mapModel == null) return;
            spawnLaneMinions(arena, mapModel, TEAM_A);
            spawnLaneMinions(arena, mapModel, TEAM_B);
            log.info("[PVP] Spawned minion wave for matchId={}", match.realmId);
        } catch (Exception e) {
            log.error("[PVP] Failed to spawn minion wave. Reason: {}", e.getMessage(), e);
        }
    }

    /** Spawns one full wave for {@code teamId}: 3 lanes × (4 basic + 1 strong) = 15 minions.
     *  Each minion gets {@code minionLaneId} (1=top, 2=mid, 3=bottom) so PvpMinionAi can look
     *  up the correct waypoint list. Spawn positions are jittered around lane[0] so the column
     *  doesn't stack on a single tile. */
    private static void spawnLaneMinions(Realm arena, MapModel mapModel, byte teamId) {
        final List<List<float[]>> lanes = (teamId == TEAM_A)
                ? mapModel.getPvpLanesTeamA() : mapModel.getPvpLanesTeamB();
        if (lanes == null) {
            log.warn("[PVP] spawnLaneMinions team={} has NULL lanes — maps.json missing pvpLanesTeam{} field "
                    + "(or server cached an older copy). No minions will spawn until restart with new data.",
                    teamId, teamId == TEAM_A ? "A" : "B");
            return;
        }
        final int totalPerLane = BASIC_PER_LANE + STRONG_PER_LANE;
        int spawned = 0;
        long firstId = 0L;
        float firstX = 0f, firstY = 0f;
        for (int laneIdx = 0; laneIdx < Math.min(LANES_PER_TEAM, lanes.size()); laneIdx++) {
            final List<float[]> waypoints = lanes.get(laneIdx);
            if (waypoints == null || waypoints.isEmpty()) continue;
            final float[] startWp = waypoints.get(0);
            for (int i = 0; i < totalPerLane; i++) {
                final boolean strong = i >= BASIC_PER_LANE;
                final int enemyId = strong ? MINION_STRONG_ENEMY_ID : MINION_BASIC_ENEMY_ID;
                final int size = strong ? MINION_STRONG_SIZE : MINION_BASIC_SIZE;
                final float angle = (i / (float) totalPerLane) * 2f * (float) Math.PI;
                final float radius = 32f;
                final Vector2f pos = new Vector2f(
                        startWp[0] + (float) Math.cos(angle) * radius,
                        startWp[1] + (float) Math.sin(angle) * radius);
                // weaponId MUST equal the model's attackId — fireLegacyAttack looks up
                // PROJECTILE_GROUPS by weaponId, so passing -1 here means the minion silently
                // never fires (was the original "minions don't attack opposing players" bug).
                final int attackId = (com.openrealm.game.data.GameDataManager.ENEMIES.get(enemyId) != null)
                        ? com.openrealm.game.data.GameDataManager.ENEMIES.get(enemyId).getAttackId() : -1;
                final Enemy minion = new Enemy(Realm.RANDOM.nextLong(), enemyId, pos, size, attackId);
                minion.setTeamId(teamId);
                minion.setMinionLaneId((byte) (laneIdx + 1));
                minion.setMinionLaneIdx(0);
                arena.addEnemy(minion);
                if (spawned == 0) { firstId = minion.getId(); firstX = pos.x; firstY = pos.y; }
                spawned++;
            }
        }
        log.info("[PVP] spawnLaneMinions team={} added {} minions (firstId={} pos=({},{}))",
                teamId, spawned, firstId, firstX, firstY);
    }

    /** ServerCombatHelper calls this in place of mgr.playerDeath when a player's HP hits 0
     *  inside a PvP realm. No-permadeath: the loser's team is recorded and the match is closed. */
    public static void onPlayerHpZero(Player loser) {
        final PvpMatch match = playerMatches.get(loser.getId());
        if (match == null || match.winnerTeam != 0) return;
        match.winnerTeam = (loser.getPvpTeamId() == TEAM_A) ? TEAM_B : TEAM_A;
        endMatch(match);
    }

    private static void endMatch(PvpMatch match) {
        try {
            mgr.acquireRealmLock();
            try {
                final Realm arena = mgr.getRealms().get(match.realmId);
                final Player a = mgr.getPlayerById(match.playerAId);
                final Player b = mgr.getPlayerById(match.playerBId);

                playerMatches.remove(match.playerAId);
                playerMatches.remove(match.playerBId);
                activeMatches.remove(match.realmId);

                final String winnerName = (match.winnerTeam == TEAM_A && a != null) ? a.getName()
                        : (match.winnerTeam == TEAM_B && b != null) ? b.getName() : "(unknown)";
                final String endMsg = "PvP match ended. Winner: " + winnerName;

                final Realm nexus = mgr.getTopRealm();
                returnPlayerToNexus(a, arena, nexus, endMsg);
                returnPlayerToNexus(b, arena, nexus, endMsg);

                if (arena != null) {
                    arena.setShutdown(true);
                    mgr.getRealms().remove(arena.getRealmId());
                }
                log.info("[PVP] Match ended realmId={} winnerTeam={}", match.realmId, match.winnerTeam);
            } finally {
                mgr.releaseRealmLock();
            }
        } catch (Exception e) {
            log.error("[PVP] Failed to end match. Reason: {}", e.getMessage(), e);
        }
    }

    private static void returnPlayerToNexus(Player player, Realm arena, Realm nexus, String endMsg) {
        if (player == null || nexus == null) return;
        player.setPvpTeamId((byte) 0);
        player.setHealth(player.getStats().getHp());
        player.setMana(player.getStats().getMp());
        if (arena != null) arena.removePlayer(player);
        final MapModel nexusModel = GameDataManager.MAPS.get(nexus.getMapId());
        player.setPos(nexusModel != null ? nexusModel.getCenter() : new Vector2f(16 * 32, 16 * 32));
        player.setTeleported(true);
        nexus.addPlayer(player);
        mgr.clearPlayerState(player.getId());
        mgr.invalidateRealmLoadState(nexus);
        ServerGameLogic.sendImmediateLoadMap(mgr, nexus, player);
        ServerGameLogic.onPlayerJoin(mgr, nexus, player);
        mgr.enqueueServerPacket(player, TextPacket.create("SYSTEM", player.getName(), endMsg));
    }

    /** Forfeits an active match on disconnect. Invoked from RealmManagerServer.disconnectPlayer. */
    public static void handlePlayerDisconnect(long playerId) {
        final PvpMatch match = playerMatches.get(playerId);
        if (match == null || match.winnerTeam != 0) return;
        if (match.playerAId == playerId) match.winnerTeam = TEAM_B;
        else if (match.playerBId == playerId) match.winnerTeam = TEAM_A;
        endMatch(match);
    }
}
