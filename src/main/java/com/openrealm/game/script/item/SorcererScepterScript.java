package com.openrealm.game.script.item;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Sorcerer Scepter ability (items 270-276, T0-T6).
 * Chain lightning: hits nearest enemy, then chains to nearby enemies
 * with diminishing damage per chain link.
 */
public class SorcererScepterScript extends UseableItemScriptBase {

    private static final int MIN_ID = 270;
    private static final int MAX_ID = 276;
    private static final float INITIAL_RANGE = 384.0f;   // ~12 tiles to first target from cursor
    private static final float CHAIN_RANGE = 256.0f;     // ~8 tiles between chain targets
    private static final float DAMAGE_DECAY = 0.85f;     // each chain does 85% of previous
    private static final int BASE_TARGETS = 3;            // minimum chain targets

    public SorcererScepterScript(RealmManagerServer mgr) {
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
        invokeItemAbility(targetRealm, player, abilityItem, player.getPos().clone(player.getSize() / 2, player.getSize() / 2));
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem, Vector2f targetPos) {
        // Calculate max chain targets: base + 1 per 10 WIS over 50
        int wisBonus = Math.max(0, (player.getComputedStats().getWis() - 50) / 10);
        int maxTargets = BASE_TARGETS + (abilityItem.getItemId() - MIN_ID) + wisBonus; // scales with tier

        // Base damage from item's damage field (min-max roll) + attack stat
        short baseDamage = (abilityItem.getDamage() != null) ? abilityItem.getDamage().getInRange() : 100;
        baseDamage += player.getComputedStats().getAtt();

        final Vector2f playerCenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        // Initial target: nearest enemy to where the user clicked, not the player
        final Vector2f cursorPos = (targetPos != null) ? targetPos : playerCenter;

        final List<Long> hitEnemyIds = new ArrayList<>();
        Enemy currentTarget = findNearestEnemy(targetRealm, cursorPos, INITIAL_RANGE, hitEnemyIds);

        float currentDamage = baseDamage;
        Vector2f chainOrigin = playerCenter;

        for (int chain = 0; chain < maxTargets && currentTarget != null; chain++) {
            if (currentTarget.getDeath()) break;
            if (currentTarget.hasEffect(StatusEffectType.STASIS)) {
                // Skip stasis enemies, try next
                hitEnemyIds.add(currentTarget.getId());
                currentTarget = findNearestEnemy(targetRealm, chainOrigin, CHAIN_RANGE, hitEnemyIds);
                continue;
            }

            // Apply chain damage as TRUE DAMAGE — ignores enemy defense, just
            // like assassin poison. Buffs (DAMAGING / CURSED) still scale it.
            short dmg = (short) currentDamage;
            if (player.hasEffect(StatusEffectType.DAMAGING)) {
                dmg = (short) (dmg * 1.5);
            }
            if (currentTarget.hasEffect(StatusEffectType.CURSED)) {
                dmg = (short) (dmg * 1.25);
            }

            currentTarget.setHealth(currentTarget.getHealth() - dmg);
            this.mgr.broadcastTextEffect(EntityType.ENEMY, currentTarget, TextEffect.DAMAGE, "-" + dmg);

            if (currentTarget.getDeath()) {
                this.mgr.enemyDeath(targetRealm, currentTarget);
            }

            // Broadcast chain lightning arc visual from origin to target.
            // Tier (item id − MIN_ID) tints the bolt so a T6 scepter zaps
            // with a more dramatic colour than a T0.
            Vector2f targetCenter = currentTarget.getPos().clone(currentTarget.getSize() / 2, currentTarget.getSize() / 2);
            final byte arcTier = (byte) Math.max(0, abilityItem.getItemId() - MIN_ID);
            this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
                CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
                chainOrigin.x, chainOrigin.y, targetCenter.x, targetCenter.y, (short) 1200, arcTier));

            // Track hit and prepare next chain
            hitEnemyIds.add(currentTarget.getId());
            chainOrigin = targetCenter;
            currentDamage *= DAMAGE_DECAY;

            // Find next chain target
            currentTarget = findNearestEnemy(targetRealm, chainOrigin, CHAIN_RANGE, hitEnemyIds);
        }
    }

    /**
     * Find the nearest enemy within range that hasn't been hit yet.
     */
    private Enemy findNearestEnemy(Realm realm, Vector2f center, float range, List<Long> excludeIds) {
        Enemy nearest = null;
        float nearestDistSq = range * range;

        for (final Enemy enemy : realm.getEnemies().values()) {
            if (enemy.getDeath()) continue;
            if (excludeIds.contains(enemy.getId())) continue;

            float dx = enemy.getPos().x - center.x;
            float dy = enemy.getPos().y - center.y;
            float distSq = dx * dx + dy * dy;

            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = enemy;
            }
        }
        return nearest;
    }
}
