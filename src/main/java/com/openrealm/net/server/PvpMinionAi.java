package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

import lombok.extern.slf4j.Slf4j;

/**
 * Replaces the PvE enemy AI for team-affiliated minions inside a PvP realm.
 * Two behaviors:
 *   1) When an opposing-team player is within chase range, defer to the enemy's
 *      existing AI ({@link Enemy#updateAgainstTarget}) so phase movement and
 *      attacks still come from the EnemyModel JSON — minions fight like their
 *      Pirate / Purple_Mage source enemies, just team-gated.
 *   2) When no opposing player is in range, walk toward the next lane waypoint.
 *
 * Minion-vs-minion combat is NOT implemented here (the enemy attack code
 * fundamentally targets a Player). Two opposing waves clipping through each
 * other on the same lane is the acceptable MVP behavior — players + the
 * friendly-fire-aware bullet path do the actual fighting.
 *
 * The proximity-classification + tickMove + removeExpiredEffects scaffolding
 * is replicated here intentionally — the user's design principle is that ALL
 * PvP enemy ticks route through this class, so PvE tickAwakeEnemy stays clean.
 */
@Slf4j
public final class PvpMinionAi {

    /** How close (px) the minion needs to be to a waypoint before advancing to the next. */
    private static final float WAYPOINT_REACHED_DISTANCE = 48f;
    /** Speed (px/tick) for lane walking when no opposing target is in range. */
    private static final float LANE_WALK_SPEED = 1.0f;

    // Mirror of the proximity constants used by RealmManagerServer.tickAwakeEnemy — kept here
    // so the PvP path doesn't need package-private access to the PvE tick scaffolding.
    public static final int PROX_DORMANT = 0;
    public static final int PROX_VISIBLE = 2;

    private PvpMinionAi() {}

    public static void tick(final Realm realm, final Enemy enemy, final RealmManagerServer mgr,
            final double time, final int aiTick, final int moveFarTick, final int classification,
            final int aiTickDivisor, final int moveFarDivisor) {
        // PvP arenas have at most ~30 minions per match — the PvE proximity gate isn't a perf
        // win here and breaks "minions walk lanes even when no player is nearby". Always tick.
        if ((enemy.getId() & (aiTickDivisor - 1)) == aiTick) {
            updateAi(realm, enemy, mgr, time);
        }
        enemy.tickMove(realm);
        enemy.removeExpiredEffects();
    }

    private static void updateAi(final Realm realm, final Enemy enemy, final RealmManagerServer mgr,
            final double time) {
        // Priority 1: opposing player. Players are higher-value targets so we always
        // engage them over minions when one is in range.
        final Player playerTarget = findOpposingPlayerInChaseRange(realm, enemy, mgr);
        if (playerTarget != null) {
            enemy.updateAgainstTarget(realm, playerTarget, mgr, time);
            return;
        }
        // Priority 2: opposing minion. Mirrors the enemy AI's chase + fire pattern but
        // targets an Enemy instead of a Player — this is what lets two opposing waves
        // meeting in the middle of a lane actually engage each other in a stalemate
        // instead of clipping past each other.
        final Enemy minionTarget = findOpposingMinionInChaseRange(realm, enemy);
        if (minionTarget != null) {
            chaseEnemyTarget(enemy, minionTarget);
            fireAtEnemyTarget(realm, enemy, minionTarget, mgr);
            return;
        }
        // Priority 3: no targets in range — keep marching down the lane.
        walkLane(realm, enemy);
    }

    /** Closest opposing-team minion within {@code chaseRange}, or null. Mirrors
     *  {@link #findOpposingPlayerInChaseRange} but iterates the realm's enemies map. */
    private static Enemy findOpposingMinionInChaseRange(final Realm realm, final Enemy enemy) {
        final byte myTeam = enemy.getTeamId();
        if (myTeam == 0) return null;
        final float range = enemy.getChaseRange();
        final float rangeSq = range * range;
        Enemy best = null;
        float bestSq = rangeSq;
        for (final Enemy e : realm.getEnemies().values()) {
            if (e == null || e.getId() == enemy.getId()) continue;
            if (e.getDeath()) continue;
            if (e.getTeamId() == 0 || e.getTeamId() == myTeam) continue;
            final float dx = e.getPos().x - enemy.getPos().x;
            final float dy = e.getPos().y - enemy.getPos().y;
            final float distSq = dx * dx + dy * dy;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = e;
            }
        }
        return best;
    }

    /** Sets dx/dy toward {@code target} unless the minion is already in attack range
     *  (in which case it stops moving and just fires — same behavior as PvE chase()). */
    private static void chaseEnemyTarget(final Enemy attacker, final Enemy target) {
        final float dist = attacker.getPos().distanceTo(target.getPos());
        if (dist < attacker.getAttackRange()) {
            attacker.setDx(0); attacker.setDy(0);
            return;
        }
        final float dx = target.getPos().x - attacker.getPos().x;
        final float dy = target.getPos().y - attacker.getPos().y;
        final float invDist = 1f / Math.max(0.001f, dist);
        final float velX = dx * invDist * LANE_WALK_SPEED;
        final float velY = dy * invDist * LANE_WALK_SPEED;
        attacker.setDx(velX);
        attacker.setDy(velY);
        attacker.setLeft(velX < -0.05f);
        attacker.setRight(velX > 0.05f);
        attacker.setUp(velY < -0.05f);
        attacker.setDown(velY > 0.05f);
    }

    /** Fires the attacker's projectile group at {@code target} if cooldown ready and in
     *  range. Cooldown formula mirrors Enemy.processAttacks legacy path so minion fire-rate
     *  matches PvE behavior. Bullets are marked isEnemy=true so they go through the same
     *  collision passes (processBulletVsMinionHits + processPlayerHit-with-team-check). */
    private static void fireAtEnemyTarget(final Realm realm, final Enemy attacker,
            final Enemy target, final RealmManagerServer mgr) {
        final int dex = Math.max(1, (int) ((6.5 * (attacker.getModel().getStats().getDex() + 17.3)) / 75));
        final long now = System.currentTimeMillis();
        if (now - attacker.getLastShotTick() < (1000L / dex)) return;
        if (attacker.getPos().distanceTo(target.getPos()) > attacker.getAttackRange()) return;
        final int attackId = attacker.getWeaponId();
        if (attackId < 0) return;
        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(attackId);
        if (group == null) return;

        final Vector2f source = attacker.getPos().clone(attacker.getSize() / 2f, attacker.getSize() / 2f);
        final Vector2f dest = target.getPos().clone(target.getSize() / 2f, target.getSize() / 2f);
        final float baseAngle = Bullet.getAngle(source, dest);
        attacker.setLastShotTick(now);
        attacker.setAttack(true);
        for (final Projectile p : group.getProjectiles()) {
            final float angle = baseAngle + Float.parseFloat(p.getAngle());
            mgr.addProjectile(realm.getRealmId(), 0L, 0L,
                    group.getProjectileGroupId(), p.getProjectileId(),
                    source.clone(), angle, p.getSize(), p.getMagnitude(), p.getRange(),
                    p.getDamage(), true, p.getFlags(),
                    p.getAmplitude(), p.getFrequency(), attacker.getId());
        }
    }

    /** Closest opposing-team player within {@code chaseRange}, or null if no such player exists. */
    private static Player findOpposingPlayerInChaseRange(final Realm realm, final Enemy enemy,
            final RealmManagerServer mgr) {
        final byte myTeam = enemy.getTeamId();
        if (myTeam == 0) return null;
        final float range = enemy.getChaseRange();
        final float rangeSq = range * range;
        Player best = null;
        float bestSq = rangeSq;
        for (final Player p : realm.getPlayers().values()) {
            if (p == null || p.isHiddenFromOthers()) continue;
            if (p.getPvpTeamId() == 0 || p.getPvpTeamId() == myTeam) continue;
            final float dx = p.getPos().x - enemy.getPos().x;
            final float dy = p.getPos().y - enemy.getPos().y;
            final float distSq = dx * dx + dy * dy;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = p;
            }
        }
        return best;
    }

    /**
     * Expands the awake-enemy candidate set for the realm tick. In a PvP arena ALL minions
     * tick every frame regardless of player proximity — the PvE proximity gate would freeze
     * minions that walked far from any player (their lane endpoint, mostly). Called from
     * RealmManagerServer.tickRealmEnemies via a single dispatch line; PvE realms short-circuit.
     */
    public static void expandTickCandidates(final Realm realm, final Set<Long> candidates) {
        if (!realm.isPvp()) return;
        candidates.addAll(realm.getEnemies().keySet());
    }

    /**
     * Per-realm PvP-only pass: minion-fired bullets (isEnemy=true) damage opposing-team
     * minions in addition to opposing players. The PvE bullet collision pass is per-player
     * and only does enemy→player + player→enemy — minion vs minion isn't covered there.
     *
     * This pass is INTENTIONALLY isolated in PvP code. The only PvE call site is a single
     * dispatch line: {@code if (realm.isPvp()) PvpMinionAi.processBulletVsMinionHits(realm, mgr); }
     * — no PvE collision code is modified.
     */
    public static void processBulletVsMinionHits(final Realm realm, final RealmManagerServer mgr) {
        if (!realm.isPvp()) return;
        final Map<Long, Bullet> bullets = realm.getBullets();
        if (bullets.isEmpty()) return;
        final Map<Long, Enemy> enemies = realm.getEnemies();
        if (enemies.isEmpty()) return;

        // Snapshot to a list so we can safely consume bullets mid-iteration without ConcurrentModificationException.
        for (final Bullet b : new ArrayList<>(bullets.values())) {
            if (!b.isEnemy() || b.isPlayerHit() || b.getSrcEntityId() == 0L) continue;
            final Enemy srcMinion = enemies.get(b.getSrcEntityId());
            if (srcMinion == null) continue;
            final byte myTeam = srcMinion.getTeamId();
            if (myTeam == 0) continue;

            for (final Enemy target : enemies.values()) {
                if (target.getId() == srcMinion.getId()) continue;
                if (target.getDeath()) continue;
                if (target.getTeamId() == 0 || target.getTeamId() == myTeam) continue;
                if (realm.hasHitEnemy(b.getId(), target.getId())) continue;
                if (!RealmManagerServer.circleHit(b, target)) continue;

                final short dmg = (short) Math.max(1, b.getDamage());
                target.setHealth(target.getHealth() - dmg);
                realm.hitEnemy(b.getId(), target.getId());

                final float impactX = b.getPos().x + b.getSize() * 0.5f;
                final float impactY = b.getPos().y + b.getSize() * 0.5f;
                mgr.broadcastTextEffect(realm, EntityType.ENEMY, target, TextEffect.DAMAGE,
                        "-" + dmg, impactX, impactY);
                mgr.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
                        CreateEffectPacket.EFFECT_WIZARD_BURST,
                        impactX, impactY, 20f, (short) 220, (byte) 1));

                b.setPlayerHit(true);
                realm.getExpiredBullets().add(b.getId());
                realm.removeBullet(b);
                if (target.getDeath()) {
                    mgr.enemyDeath(realm, target);
                }
                break;
            }
        }
    }

    private static void walkLane(final Realm realm, final Enemy enemy) {
        final MapModel mapModel = GameDataManager.MAPS.get(realm.getMapId());
        if (mapModel == null) { enemy.setDx(0); enemy.setDy(0); return; }
        final List<List<float[]>> lanes = (enemy.getTeamId() == PvpMatchManager.TEAM_A)
                ? mapModel.getPvpLanesTeamA() : mapModel.getPvpLanesTeamB();
        if (lanes == null || enemy.getMinionLaneId() <= 0 || enemy.getMinionLaneId() > lanes.size()) {
            enemy.setDx(0); enemy.setDy(0); return;
        }
        final List<float[]> waypoints = lanes.get(enemy.getMinionLaneId() - 1);
        if (waypoints == null || waypoints.isEmpty()) {
            enemy.setDx(0); enemy.setDy(0); return;
        }
        int idx = enemy.getMinionLaneIdx();
        // Stuck-skip: if the minion has been wedged for a while approaching this waypoint,
        // jump ahead to the next one. The lane geometry guarantees the next waypoint is in
        // a different direction, so this routes around corner obstacles that the per-tick
        // nudge in Enemy.tickMove couldn't clear on its own.
        if (enemy.getStuckTicks() > 12 && idx < waypoints.size() - 1) {
            enemy.setStuckTicks(0);
            enemy.setMinionLaneIdx(idx + 1);
            idx = idx + 1;
        }
        if (idx >= waypoints.size()) {
            // Reached enemy spawn — hold position; player will engage. Could despawn here.
            enemy.setDx(0); enemy.setDy(0); return;
        }
        final float[] wp = waypoints.get(idx);
        final float wpx = wp[0];
        final float wpy = wp[1];
        final float ex = enemy.getPos().x + enemy.getSize() / 2f;
        final float ey = enemy.getPos().y + enemy.getSize() / 2f;
        final float dx = wpx - ex;
        final float dy = wpy - ey;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < WAYPOINT_REACHED_DISTANCE) {
            enemy.setMinionLaneIdx(idx + 1);
            // Re-recurse to pick up next waypoint on the same tick — usually a small step.
            walkLane(realm, enemy);
            return;
        }
        final float invDist = 1f / Math.max(0.001f, dist);
        final float velX = dx * invDist * LANE_WALK_SPEED;
        final float velY = dy * invDist * LANE_WALK_SPEED;
        enemy.setDx(velX);
        enemy.setDy(velY);
        enemy.setLeft(velX < -0.05f);
        enemy.setRight(velX > 0.05f);
        enemy.setUp(velY < -0.05f);
        enemy.setDown(velY > 0.05f);
        enemy.setAttack(false);
    }
}
