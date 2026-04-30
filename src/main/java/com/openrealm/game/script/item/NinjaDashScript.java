package com.openrealm.game.script.item;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Damage;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Ninja Dash ability — moves the ninja toward the cursor (3–5 tiles, tier
 * scaled), grants brief INVINCIBLE while the dash resolves, and damages
 * every enemy whose center is within 2 tiles of the dash line segment
 * ("fury of blades").
 *
 * Handles the Shuriken family (298–304, T0–T6).
 *
 * The dash is collision-aware: it walks the line in 1/4-tile increments
 * and stops at the last walkable position, so the ninja never phases
 * through walls or void tiles even though the visual is instantaneous.
 */
public class NinjaDashScript extends UseableItemScriptBase {

    private static final int MIN_ID = 298;
    private static final int MAX_ID = 304;

    // Distance scales linearly with tier: T0 = 3 tiles, T6 = 5 tiles.
    private static final float MIN_DASH_TILES = 3.0f;
    private static final float MAX_DASH_TILES = 5.0f;
    // Blade-fury hit radius around the dash line segment. 3 tiles (was
    // 2) so the vortex of blades reliably catches enemies the ninja
    // *passes near* — not just the ones directly on the dash line.
    private static final float BLADE_RADIUS_TILES = 3.0f;
    // INVINCIBLE window. Slightly longer than the visual dash duration so
    // the brief gap between teleport and client interpolation is covered.
    private static final long INVINCIBLE_DURATION_MS = 400L;
    // Visual trail lingers after the dash so the vortex of blades has
    // time to swirl through ~2-3 full rotations on the client. At 0.020
    // rad/ms orbit speed (renderer case 13) that's ~850ms for a satisfying
    // multi-pass sweep before the blades fade.
    private static final short TRAIL_DURATION_MS = 850;
    // Sub-tile step granularity for the collision walk along the dash line.
    private static final int STEPS_PER_TILE = 4;

    public NinjaDashScript(final RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return itemId >= MIN_ID && itemId <= MAX_ID;
    }

    @Override
    public int getTargetItemId() {
        return MIN_ID;
    }

    @Override
    public void invokeUseItem(final Realm targetRealm, final Player player, final GameItem item) {
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem) {
        invokeItemAbility(targetRealm, player, abilityItem,
                player.getPos().clone(player.getSize() / 2, player.getSize() / 2));
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem,
                                   final Vector2f cursorPos) {
        final byte tier = (byte) Math.max(0, abilityItem.getItemId() - MIN_ID);
        final float tileSize = GlobalConstants.BASE_TILE_SIZE;
        final float dashTiles = MIN_DASH_TILES + (tier / 6.0f) * (MAX_DASH_TILES - MIN_DASH_TILES);
        final float maxDashPx = dashTiles * tileSize;

        final Vector2f start = player.getPos().clone();
        final Vector2f playerCenter = start.clone(player.getSize() / 2f, player.getSize() / 2f);

        // Direction from player to cursor. Bail out if the cursor is on top
        // of the player — there's no defined dash direction.
        float dirX = cursorPos.x - playerCenter.x;
        float dirY = cursorPos.y - playerCenter.y;
        final float cursorDist = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (cursorDist < 1.0f) return;
        dirX /= cursorDist;
        dirY /= cursorDist;

        // Cap dash length by either the tier-scaled max or the cursor distance.
        final float dashDist = Math.min(maxDashPx, cursorDist);

        // Walk the dash in sub-tile steps. Stop at the last walkable
        // position (collision tile or void tile) so the ninja never lands
        // inside a wall.
        final int totalSteps = Math.max(1, (int) Math.ceil((dashDist / tileSize) * STEPS_PER_TILE));
        final float stepLen = dashDist / totalSteps;
        final float stepX = dirX * stepLen;
        final float stepY = dirY * stepLen;

        Vector2f curr = start.clone();
        for (int i = 0; i < totalSteps; i++) {
            final Vector2f next = curr.clone(stepX, stepY);
            // Use the entity's full hitbox so the dash respects the same
            // boundaries as normal player movement. isVoidTile is sampled
            // at the entity center to match the in-tick movement check.
            if (targetRealm.getTileManager().collidesAtPosition(next, player.getSize())) break;
            if (targetRealm.getTileManager().isVoidTile(
                    next.clone(player.getSize() / 2f, player.getSize() / 2f), 0, 0)) break;
            curr = next;
        }
        final Vector2f end = curr;
        final Vector2f endCenter = end.clone(player.getSize() / 2f, player.getSize() / 2f);

        // Apply invulnerability for the dash window. Stacks like any other
        // INVINCIBLE source — if the ninja was already INVINCIBLE (e.g.
        // godmode), this is a no-op against an existing longer timer.
        player.addEffect(StatusEffectType.INVINCIBLE, INVINCIBLE_DURATION_MS);

        // Blade fury — damage every enemy within BLADE_RADIUS_TILES of the
        // dash line segment. Uses point-to-segment distance so a long dash
        // hits everything along the path, not just at the endpoints.
        final float bladeRadius = BLADE_RADIUS_TILES * tileSize;
        final float bladeRadiusSq = bladeRadius * bladeRadius;
        final Damage dmgRange = abilityItem.getDamage();
        final short rolledBase = (dmgRange != null) ? dmgRange.getInRange() : (short) 100;
        final int rawDamage = rolledBase + player.getComputedStats().getAtt();

        for (final Enemy enemy : targetRealm.getEnemies().values()) {
            if (enemy == null || enemy.getDeath()) continue;
            if (enemy.hasEffect(StatusEffectType.STASIS)) continue;
            // Skip stationary INVINCIBLE NPCs (healers, decoys) — they
            // shouldn't take damage from incidental dash sweeps.
            if (enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;

            final float ecx = enemy.getPos().x + enemy.getSize() / 2f;
            final float ecy = enemy.getPos().y + enemy.getSize() / 2f;
            final float distSq = pointToSegmentDistSq(ecx, ecy,
                    playerCenter.x, playerCenter.y, endCenter.x, endCenter.y);
            if (distSq > bladeRadiusSq) continue;

            final int floorDmg = (int) Math.max(rawDamage - enemy.getStats().getDef(), rawDamage * 0.15);
            final short dmg = (short) Math.max(1, floorDmg);
            enemy.setHealth(enemy.getHealth() - dmg);
            this.mgr.broadcastTextEffect(EntityType.ENEMY, enemy, TextEffect.DAMAGE, "-" + dmg);
            if (enemy.getDeath()) {
                this.mgr.enemyDeath(targetRealm, enemy);
            }
        }

        // Move the ninja to the dash endpoint. setPos triggers the standard
        // PosAck reconciliation on the client; the dash trail visual masks
        // the snap.
        player.setPos(end);

        // Broadcast the dash trail. Tier tints the trail colour so a T6
        // dash visibly outshines a T0.
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
                CreateEffectPacket.EFFECT_NINJA_DASH,
                playerCenter.x, playerCenter.y, endCenter.x, endCenter.y,
                TRAIL_DURATION_MS, tier));
    }

    /** Squared distance from point (px,py) to the line segment (ax,ay)-(bx,by). */
    private static float pointToSegmentDistSq(float px, float py, float ax, float ay, float bx, float by) {
        final float vx = bx - ax;
        final float vy = by - ay;
        final float wx = px - ax;
        final float wy = py - ay;
        final float lenSq = vx * vx + vy * vy;
        if (lenSq < 1e-6f) {
            // Degenerate segment — distance to the start point.
            return wx * wx + wy * wy;
        }
        float t = (vx * wx + vy * wy) / lenSq;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        final float cx = ax + vx * t;
        final float cy = ay + vy * t;
        final float dx = px - cx;
        final float dy = py - cy;
        return dx * dx + dy * dy;
    }
}
