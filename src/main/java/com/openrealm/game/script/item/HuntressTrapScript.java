package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Huntress Trap ability (item 288).
 * Throws a trap grenade to cursor with a 0.6s arc.
 * On landing, arms a persistent trap zone on the ground.
 * When an enemy walks into the trigger radius, the trap detonates:
 * applies SLOWED + damage to all enemies in the blast radius.
 * Damage, radius, effect, duration, and MP cost all come from the item JSON.
 */
public class HuntressTrapScript extends UseableItemScriptBase {

    private static final int MIN_ID = 288;
    private static final int MAX_ID = 294;
    private static final long THROW_DURATION_MS = 1060;
    private static final long TRAP_LIFETIME_MS = 10000;

    public HuntressTrapScript(RealmManagerServer mgr) {
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
        final Vector2f center = (targetPos != null) ? targetPos
                : player.getPos().clone(player.getSize() / 2, player.getSize() / 2);

        // Read damage from item data (avg of min/max + ATT)
        int damage = 85;
        if (abilityItem.getDamage() != null) {
            damage = abilityItem.getDamage().getInRange() + player.getComputedStats().getAtt();
        }

        // Read effect from item data
        final short effectId = abilityItem.getEffect().getEffectId().effectId;
        final long effectDuration = abilityItem.getEffect().getDuration();

        // Read trap radius from item (trapRadius in tiles, convert to pixels)
        // Default 3.5 tiles = 112px
        float radiusPx = 3.5f * 32.0f;

        // Broadcast throw arc visual. Tier flows through to the throw, the
        // armed-trap ground ring, and the trigger snap so the whole sequence
        // shares one colour family per tier.
        final byte tier = (byte) Math.max(0, abilityItem.getItemId() - MIN_ID);
        final Vector2f playerCenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
                CreateEffectPacket.EFFECT_TRAP_THROW,
                playerCenter.x, playerCenter.y, center.x, center.y, (short) THROW_DURATION_MS, tier));

        // Register the trap (tier is propagated to TrapState so the
        // armed-ring + trigger-snap visuals match the throw colour).
        targetRealm.registerTrap(THROW_DURATION_MS, player.getId(),
                center.x, center.y, radiusPx, effectId, effectDuration, damage, TRAP_LIFETIME_MS, tier);
    }
}
