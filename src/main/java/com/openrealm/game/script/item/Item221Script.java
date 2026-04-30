package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Wizard Spell ability — broadcasts an arcane release flare at the caster
 * when the spell fires. Damage projectiles are spawned by the generic
 * ability path in RealmManagerServer.useAbility; this script only adds
 * the cast-time visual so the wizard "feels" like a caster, not just a
 * stat block firing missiles.
 *
 * Handles the tiered Wand-of-* spell family (221–227, T0–T6).
 */
public class Item221Script extends UseableItemScriptBase {

    private static final int MIN_ID = 221;
    private static final int MAX_ID = 227;

    public Item221Script(final RealmManagerServer mgr) {
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
                                   final Vector2f targetPos) {
        // Tier-coloured arcane burst at the caster's center. Short-lived so
        // it doesn't compete visually with the projectiles flying out.
        final byte tier = abilityItem != null ? (byte) Math.max(0, abilityItem.getItemId() - MIN_ID) : 0;
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                CreateEffectPacket.EFFECT_WIZARD_BURST, center.x, center.y, 56.0f, (short) 600, tier));
    }
}
