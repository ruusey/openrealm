package com.openrealm.game.script.item;

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
 * Necromancer Skull ability (items 200-206, T0-T6).
 * AoE damage at cursor position + vampirism heal to self.
 * Damage is dealt directly (not via projectiles) to enemies within radius.
 * Heal amount = percentage of total damage dealt.
 */
public class NecromancerSkullScript extends UseableItemScriptBase {

    // Skull item IDs: 200 (T0) through 206 (T6)
    private static final int MIN_ID = 200;
    private static final int MAX_ID = 206;

    // RotMG wiki values: radius scales T0=2.5 to T6=3.75 tiles (1 tile = 32px)
    private static final float[] RADIUS_BY_TIER = { 80f, 88f, 96f, 104f, 112f, 120f, 120f };
    // Base damage per tier: T0=65, T1=95, T2=140, T3=180, T4=230, T5=270, T6=300
    private static final short[] DAMAGE_BY_TIER = { 65, 95, 140, 180, 230, 270, 300 };
    // Flat heal amount per tier: T0=25, T1=55, T2=65, T3=90, T4=100, T5=105, T6=110
    private static final int[] HEAL_BY_TIER = { 25, 55, 65, 90, 100, 105, 110 };

    public NecromancerSkullScript(RealmManagerServer mgr) {
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
        final Vector2f center = (targetPos != null) ? targetPos : player.getPos().clone(player.getSize() / 2, player.getSize() / 2);

        int tier = abilityItem.getItemId() - MIN_ID;
        short baseDamage = (short) (DAMAGE_BY_TIER[tier] + player.getComputedStats().getAtt());
        float radius = RADIUS_BY_TIER[tier];
        int healAmount = HEAL_BY_TIER[tier];

        int totalDamageDealt = 0;
        for (final Enemy enemy : targetRealm.getEnemies().values()) {
            if (enemy.getDeath()) continue;
            if (enemy.hasEffect(StatusEffectType.STASIS)) continue;
            float dx = enemy.getPos().x - center.x;
            float dy = enemy.getPos().y - center.y;
            if (dx * dx + dy * dy <= radius * radius) {
                short dmg = (short) Math.max(baseDamage - enemy.getStats().getDef(), baseDamage * 0.15);
                enemy.setHealth(enemy.getHealth() - dmg);
                totalDamageDealt += dmg;
                this.mgr.broadcastTextEffect(EntityType.ENEMY, enemy, TextEffect.DAMAGE, "-" + dmg);
                if (enemy.getDeath()) {
                    this.mgr.enemyDeath(targetRealm, enemy);
                }
            }
        }

        // Broadcast vampirism visual — tier drives the colour so players
        // can tell a T0 skull from a T6 at a glance.
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
            CreateEffectPacket.EFFECT_VAMPIRISM, center.x, center.y, radius, (short) 1500, (byte) tier));

        // Flat heal (matches RotMG wiki) — heals regardless of damage dealt
        int maxHp = player.getComputedStats().getHp();
        int newHealth = Math.min(player.getHealth() + healAmount, maxHp);
        player.setHealth(newHealth);
        this.mgr.broadcastTextEffect(EntityType.PLAYER, player, TextEffect.HEAL, "+" + healAmount);
    }
}
