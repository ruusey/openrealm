package com.openrealm.game.script.item;

import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Trickster Prism ability (items 279-285, T0-T6).
 * Teleports the player to the cursor position and spawns a decoy at
 * the player's original location. The decoy walks in the direction
 * the player was last facing at the player's current movement speed,
 * for ~5 tiles, then stands still until the effect duration expires.
 *
 * Decoys are tracked per-tick on the Realm (same pattern as poison
 * throws) so no threads are blocked.
 */
public class TricksterPrismScript extends UseableItemScriptBase {

    private static final int MIN_ID = 279;
    private static final int MAX_ID = 285;
    private static final int DECOY_ENEMY_ID = 68;
    private static final float DECOY_TRAVEL_DIST = 160f; // ~5 tiles (32 px/tile)

    public TricksterPrismScript(RealmManagerServer mgr) {
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
    public void invokeUseItem(Realm targetRealm, Player player, GameItem item) {
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem) {
        invokeItemAbility(targetRealm, player, abilityItem,
                player.getPos().clone(player.getSize() / 2, player.getSize() / 2));
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem, Vector2f targetPos) {
        if (targetPos == null) return;

        // Capture the player's current position (decoy spawn point) before teleporting
        final float oldX = player.getPos().x;
        final float oldY = player.getPos().y;

        // Teleport the player to the cursor (same check as core TELEPORT logic)
        if (!targetRealm.getTileManager().isCollisionTile(targetPos)) {
            player.setPos(targetPos);
        }

        // Use the same speed formula as the client (PlayState line 343-349):
        // tiles/sec = 4 + 5.6 * (spd / 75), then px/tick = tiles/sec * 32 / 64
        // Server ticks at 64 Hz vs client's 60 Hz, so divide by 64.
        float spdStat = player.getComputedStats().getSpd();
        float tilesPerSec = 4.0f + 5.6f * (spdStat / 75.0f);
        float pxPerTick = tilesPerSec * 32.0f / 64.0f;

        // Determine decoy walk direction from the player's last movement
        float dx = 0f;
        float dy = 0f;
        boolean movingRight = player.isRight();
        boolean movingLeft  = player.isLeft();
        boolean movingDown  = player.isDown();
        boolean movingUp    = player.isUp();

        if (movingRight)     dx =  pxPerTick;
        else if (movingLeft) dx = -pxPerTick;
        if (movingDown)      dy =  pxPerTick;
        else if (movingUp)   dy = -pxPerTick;

        // Default to facing right if the player was stationary
        if (dx == 0f && dy == 0f) {
            dx = pxPerTick;
            movingRight = true;
        }

        // Normalise diagonal speed (same as client: spd * sqrt(2) / 2)
        if (dx != 0f && dy != 0f) {
            float diagSpd = (float) ((pxPerTick * Math.sqrt(2)) / 2.0);
            dx = (dx > 0 ? diagSpd : -diagSpd);
            dy = (dy > 0 ? diagSpd : -diagSpd);
        }

        // Spawn the decoy enemy at the player's old position
        final long decoyId = Realm.RANDOM.nextLong();
        final long durationMs = abilityItem.getEffect().getDuration();
        final Enemy decoy = new Enemy(decoyId, DECOY_ENEMY_ID,
                new Vector2f(oldX, oldY), player.getSize(), -1);
        decoy.setChaseRange(0);
        decoy.setAttackRange(0);
        decoy.addEffect(StatusEffectType.INVINCIBLE, durationMs);

        // Set direction flags so the client animates the decoy walking.
        // Do NOT set dx/dy on the entity — processDecoys handles all position
        // updates. Setting dx/dy here would cause double movement because
        // the enemy update loop also advances position by dx/dy.
        decoy.setRight(movingRight);
        decoy.setLeft(movingLeft);
        decoy.setDown(movingDown);
        decoy.setUp(movingUp);

        targetRealm.addEnemy(decoy);

        // Register tick-based movement + expiration
        targetRealm.registerDecoy(decoyId, player.getId(), oldX, oldY,
                dx, dy, DECOY_TRAVEL_DIST, durationMs);
    }
}
