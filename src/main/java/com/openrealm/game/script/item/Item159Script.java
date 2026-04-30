package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Rogue Cloak ability — broadcasts a smoke-poof vanish effect at the
 * caster's position. The INVISIBLE status itself is applied by the
 * generic ability path in RealmManagerServer.useAbility (effect.isSelf()
 * + effect.getEffectId() = INVISIBLE), so this script only contributes
 * the visual.
 *
 * Handles the test cloak (159) and the tiered Cloak of Shadows family
 * (207–213, T0–T6).
 */
public class Item159Script extends UseableItemScriptBase {

    public Item159Script(final RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return itemId == 159 || (itemId >= 207 && itemId <= 213);
    }

    @Override
    public void invokeUseItem(final Realm targetRealm, final Player player, final GameItem item) {
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem) {
        // Smoke poof at the caster's center — radius=48 is roughly twice
        // the player sprite, duration 700ms is a quick puff that doesn't
        // linger after the rogue has run off.
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                CreateEffectPacket.EFFECT_SMOKE_POOF, center.x, center.y, 48.0f, (short) 700,
                abilityItem != null ? abilityItem.getTier() : (byte) 0));
    }

    @Override
    public int getTargetItemId() {
        return 159;
    }
}
