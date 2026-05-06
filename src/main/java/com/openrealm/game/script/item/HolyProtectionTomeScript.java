package com.openrealm.game.script.item;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Tome of Holy Protection (UT) — a priest tome that heals a lesser amount
 * but grants the ARMORED status effect (2x DEF) to the caster.
 * Nearby players still receive a small heal.
 *
 * MP Cost: 160 (high)
 * Heal: 70 HP (lesser than tiered tomes)
 * Self: ARMORED for 5s base + 0.1s per WIS over 30
 * Stats: +40 HP, +8 DEF
 */
public class HolyProtectionTomeScript extends UseableItemScriptBase {

    private static final int ITEM_ID = 286;
    private static final int HEAL_AMOUNT = 70;
    private static final long ARMORED_BASE_DURATION = 4000;

    public HolyProtectionTomeScript(final RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return itemId == ITEM_ID;
    }

    @Override
    public int getTargetItemId() {
        return ITEM_ID;
    }

    @Override
    public void invokeUseItem(final Realm targetRealm, final Player player, final GameItem item) {
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem) {
        final Effect effect = abilityItem.getEffect();

        // Broadcast heal radius visual
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                CreateEffectPacket.EFFECT_HEAL_RADIUS, center.x, center.y, 224.0f, (short) 1500));

        // Heal all players in range (lesser heal than normal tomes)
        final Player[] targets = targetRealm
                .getPlayersInBounds(targetRealm.getTileManager().getRenderViewPort(player, 7));
        for (final Player target : targets) {
            target.addEffect(effect.getEffectId(), effect.getDuration());
            int maxHp = target.getComputedStats().getHp();
            int missing = maxHp - target.getHealth();
            if (missing > 0) {
                int toHeal = Math.min(HEAL_AMOUNT, missing);
                target.setHealth(target.getHealth() + toHeal);
                this.mgr.broadcastTextEffect(
                        EntityType.PLAYER, target,
                        TextEffect.HEAL, "+" + toHeal);
            }
        }

        // Apply ARMORED to self
        long armoredDuration = ARMORED_BASE_DURATION;
        player.addEffect(StatusEffectType.ARMORED, armoredDuration);
        this.mgr.broadcastTextEffect(EntityType.PLAYER, player,
                TextEffect.PLAYER_INFO, "ARMORED");
    }
}
