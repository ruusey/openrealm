package com.openrealm.game.script.item;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Paladin Seal ability — applies HEALING + DAMAGING buff to nearby players.
 * Handles test seal (153) and tiered seals (263-269).
 */
public class Item153Script extends UseableItemScriptBase {

    public Item153Script(RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return itemId == 153 || (itemId >= 263 && itemId <= 269);
    }

    @Override
    public void invokeUseItem(final Realm targetRealm, final Player player, final GameItem item) {
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem) {
        final long duration = abilityItem.getEffect().getDuration();
        for (final Player target : targetRealm
                .getPlayersInBounds(targetRealm.getTileManager().getRenderViewPort(player, 5))) {
            target.addEffect(abilityItem.getEffect().getEffectId(), duration);
            target.addEffect(StatusEffectType.DAMAGING, duration * 2);
            this.mgr.broadcastTextEffect(
                EntityType.PLAYER, target,
                TextEffect.PLAYER_INFO, "HEALING + DAMAGING");
        }
        // Broadcast paladin seal — its own holy-cross visual so it doesn't
        // read as a priest heal. Tier carries through for the colour tint.
        final int tier = (abilityItem.getItemId() >= 263 && abilityItem.getItemId() <= 269)
                ? abilityItem.getItemId() - 263 : Math.max(0, abilityItem.getTier());
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
            CreateEffectPacket.EFFECT_PALADIN_SEAL, center.x, center.y, 160.0f, (short) 1500, (byte) tier));
    }

    @Override
    public int getTargetItemId() {
        return 153;
    }

}
