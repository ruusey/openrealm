package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Knight Shield ability — broadcasts a stun shockwave visual at the
 * caster's position when the shield is bashed. The actual stun
 * projectile is spawned by the generic ability path; this script
 * only contributes the cast visual.
 *
 * Handles the tiered Knight Shield family (242–248, T0–T6).
 */
public class Item242Script extends UseableItemScriptBase {

    private static final int MIN_ID = 242;
    private static final int MAX_ID = 248;

    public Item242Script(final RealmManagerServer mgr) {
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
        // Shield-slam shockwave from the knight's feet. Wider radius than
        // the wizard burst because the feel is "ground impact", not a
        // localized cast.
        final byte tier = abilityItem != null ? (byte) Math.max(0, abilityItem.getItemId() - MIN_ID) : 0;
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                CreateEffectPacket.EFFECT_KNIGHT_SHOCKWAVE, center.x, center.y, 96.0f, (short) 700, tier));
    }
}
