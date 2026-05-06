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
 * Warrior Helm ability — applies SPEEDY buff to nearby players.
 * Handles test helm (156) and tiered helms (235-241).
 */
public class Item156Script extends UseableItemScriptBase {

    public Item156Script(final RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return itemId == 156 || (itemId >= 235 && itemId <= 241);
    }

    @Override
    public void invokeUseItem(final Realm targetRealm, final Player player, final GameItem item) {
    }

    @Override
    public void invokeItemAbility(final Realm targetRealm, final Player player, final GameItem abilityItem) {
        final long duration = abilityItem.getEffect().getDuration();
        for (final Player target : targetRealm
                .getPlayersInBounds(targetRealm.getTileManager().getRenderViewPort(player, 5))) {
            // Berserk (attack speed) to all nearby players
            target.addEffect(StatusEffectType.BERSERK, duration);
            if (target.getId() == player.getId()) {
                // Speedy (move speed) to self only
                target.addEffect(StatusEffectType.SPEEDY, duration);
                this.mgr.broadcastTextEffect(
                    EntityType.PLAYER, target,
                    TextEffect.PLAYER_INFO, "BERSERK + SPEEDY");
            } else {
                this.mgr.broadcastTextEffect(
                    EntityType.PLAYER, target,
                    TextEffect.PLAYER_INFO, "BERSERK");
            }
        }
        // Broadcast warrior buff visual — distinct from priest heal so the
        // two abilities are visually unmistakable. Tier scales the colour.
        final int tier = (abilityItem.getItemId() >= 235 && abilityItem.getItemId() <= 241)
                ? abilityItem.getItemId() - 235 : Math.max(0, abilityItem.getTier());
        final Vector2f center = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
            CreateEffectPacket.EFFECT_WARRIOR_BUFF, center.x, center.y, 160.0f, (short) 900,
            (byte) tier));
    }

    @Override
    public int getTargetItemId() {
        return 156;
    }

}
