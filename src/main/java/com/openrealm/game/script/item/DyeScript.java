package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.ServerFameStoreHelper;
import com.openrealm.net.server.packet.TextPacket;

/**
 * Use-handler for dye items (Green/Yellow/Red/Blue/Purple/Orange/White/Black).
 * On consume:
 *   1. Look up the dyeId from the helper's itemId->dyeId map.
 *   2. Set player.dyeId so the renderer recolors masked sprite regions.
 *   3. Push an UpdatePacket so other players see the change immediately.
 *   4. Remove the consumed dye from inventory.
 *   5. Persist the character so the dye survives logout (it's reset on
 *      permadeath because the character row is deleted).
 */
public class DyeScript extends UseableItemScriptBase {

    public DyeScript(RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return ServerFameStoreHelper.DYE_ITEM_TO_DYE_ID.containsKey(itemId);
    }

    @Override
    public int getTargetItemId() {
        // Sentinel — handles() above is what actually matches. Pick the first
        // dye id so the registry has a non-zero anchor.
        return ServerFameStoreHelper.DYE_ITEM_MIN;
    }

    @Override
    public void invokeUseItem(Realm targetRealm, Player player, GameItem item) {
        final Integer dyeId = ServerFameStoreHelper.DYE_ITEM_TO_DYE_ID.get(item.getItemId());
        if (dyeId == null) return;
        player.setDyeId(dyeId);

        // Remove the consumed dye from inventory. Match by item uid so we
        // don't accidentally consume a different stack of the same dye color
        // sitting elsewhere in the bag.
        for (int i = 0; i < player.getInventory().length; i++) {
            final GameItem slot = player.getInventory()[i];
            if (slot != null && slot.getUid().equals(item.getUid())) {
                player.getInventory()[i] = null;
                break;
            }
        }

        try {
            // Push an inventory + state update so the client sees both the
            // consumed item and the new dyeId on the player.
            final UpdatePacket update = targetRealm.getPlayerAsPacket(player.getId());
            if (update != null) this.mgr.enqueueServerPacket(player, update);
            this.mgr.enqueueServerPacket(player, TextPacket.from(
                    "SYSTEM", player.getName(), "Dye applied!"));
        } catch (Exception ignored) {}

        // Persist so the dye survives a logout. Permadeath deletes the
        // character entirely so the dye implicitly resets next life.
        this.mgr.persistPlayerAsync(player);
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem) {
        // Dyes have no ability slot; no-op.
    }
}
