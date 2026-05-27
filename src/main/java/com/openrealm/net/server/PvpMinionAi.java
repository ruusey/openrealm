package com.openrealm.net.server;

import java.util.List;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.MapModel;
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
        if (classification == PROX_DORMANT) {
            enemy.setDx(0);
            enemy.setDy(0);
            return;
        }

        if ((enemy.getId() & (aiTickDivisor - 1)) == aiTick) {
            updateAi(realm, enemy, mgr, time);
        }

        final boolean visible = (classification == PROX_VISIBLE);
        if (visible || (enemy.getId() & (moveFarDivisor - 1)) == moveFarTick) {
            enemy.tickMove(realm);
        }
        enemy.removeExpiredEffects();
    }

    private static void updateAi(final Realm realm, final Enemy enemy, final RealmManagerServer mgr,
            final double time) {
        final Player target = findOpposingPlayerInChaseRange(realm, enemy, mgr);
        if (target != null) {
            enemy.updateAgainstTarget(realm, target, mgr, time);
            return;
        }
        // No opposing player nearby — walk down the lane.
        walkLane(realm, enemy);
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
