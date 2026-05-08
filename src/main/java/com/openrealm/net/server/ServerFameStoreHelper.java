package com.openrealm.net.server;

import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.Packet;
import com.openrealm.net.client.packet.OpenFameStorePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.BuyFameItemPacket;
import com.openrealm.net.server.packet.TextPacket;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server handlers for the Fame Store: tile interaction (open the store UI on
 * the client) and BuyFameItemPacket (validate + spend fame + grant item).
 *
 * Security model: the client never deducts fame on its own. It sends a buy
 * request, and the server is the only thing that can call the data service's
 * /fame/spend endpoint. Inventory slot is validated server-side too, so a
 * forged buy request with a full inventory simply fails.
 */
@Slf4j
public class ServerFameStoreHelper {

    /** itemId range covering the 8 dyes. Anything outside is rejected. */
    public static final int DYE_ITEM_MIN = 821;
    public static final int DYE_ITEM_MAX = 828;
    /** itemId range covering the 8 enchantment crystals (Vit/Wis/HP/MP/Att/Def/Spd/Dex). */
    public static final int CRYSTAL_ITEM_MIN = 808;
    public static final int CRYSTAL_ITEM_MAX = 815;
    /** Cost in fame for any fame-store item. Centralized so we can add tiers later. */
    public static final long DYE_FAME_COST = 500L;
    public static final long ITEM_FAME_COST = DYE_FAME_COST;

    /** Whether the given itemId is sellable through the fame store. */
    private static boolean isFameStoreItem(int itemId) {
        return (itemId >= DYE_ITEM_MIN && itemId <= DYE_ITEM_MAX)
            || (itemId >= CRYSTAL_ITEM_MIN && itemId <= CRYSTAL_ITEM_MAX);
    }

    /**
     * itemId -> dyeId granted on use. Mirrors the dyeId field on each dye
     * item in game-items.json. Server stores only the opaque dye id; the
     * client resolves it to a color or pattern via dye-assets.json. New
     * cosmetics (gradients, patterned cloths) are a data-only addition.
     */
    public static final Map<Integer, Integer> DYE_ITEM_TO_DYE_ID = new HashMap<>();
    static {
        DYE_ITEM_TO_DYE_ID.put(821, 1); // green
        DYE_ITEM_TO_DYE_ID.put(822, 2); // yellow
        DYE_ITEM_TO_DYE_ID.put(823, 3); // red
        DYE_ITEM_TO_DYE_ID.put(824, 4); // blue
        DYE_ITEM_TO_DYE_ID.put(825, 5); // purple
        DYE_ITEM_TO_DYE_ID.put(826, 6); // orange
        DYE_ITEM_TO_DYE_ID.put(827, 7); // white
        DYE_ITEM_TO_DYE_ID.put(828, 8); // black
    }

    /**
     * Player walked up to a Fame Store tile and pressed Use. Fetch their
     * current account fame from the data service (source of truth) and send
     * back an OpenFameStorePacket so the UI can display it.
     */
    public static void handleOpenStore(RealmManagerServer mgr, Player player) {
        long fame = 0L;
        try {
            final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE
                    .executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
            if (account != null && account.getAccountFame() != null) {
                fame = account.getAccountFame();
            }
            // Cache so we don't have to refetch on every buy click; refreshed
            // again after each successful spend.
            player.setCachedAccountFame(fame);
        } catch (Exception e) {
            log.error("[FameStore] Failed to fetch account for player {}: {}", player.getId(), e.getMessage());
        }
        final OpenFameStorePacket reply = new OpenFameStorePacket(player.getId(), fame);
        mgr.enqueueServerPacket(player, reply);
    }

    /**
     * Process a buy request. Order of operations:
     *   1. Validate item is a sellable fame-shop item.
     *   2. Validate inventory has a free slot.
     *   3. Atomically deduct fame via the data service. Aborts with no item
     *      granted if balance is insufficient.
     *   4. Place the item in the free slot.
     *   5. Push an inventory update + persist the player.
     *
     * Step ordering matters: fame is only deducted after we know we have a
     * place to put the item, and the item is only granted after the deduct
     * succeeds. A failure at any step is logged and reported to the player
     * via TextPacket without taking partial actions.
     */
    public static void handleBuy(RealmManagerServer mgr, Packet packet) {
        final BuyFameItemPacket p = (BuyFameItemPacket) packet;
        final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
        if (realm == null) return;
        final Player player = realm.getPlayer(p.getPlayerId());
        if (player == null) return;
        try {
            final int itemId = p.getItemId();
            if (!isFameStoreItem(itemId)) {
                log.warn("[FameStore] Player {} tried to buy non-fame-shop itemId {}", player.getId(), itemId);
                notifyPlayer(mgr, player, "That item isn't sold here.");
                return;
            }
            final GameItem template = GameDataManager.GAME_ITEMS.get(itemId);
            if (template == null) {
                log.warn("[FameStore] Unknown itemId {}", itemId);
                notifyPlayer(mgr, player, "Item not available.");
                return;
            }
            final int slot = player.firstEmptyInvSlot();
            if (slot < 0) {
                notifyPlayer(mgr, player, "Inventory is full.");
                return;
            }
            // Deduct fame on the data service. If fame is insufficient, the
            // service throws and we bail out before granting any item.
            Long newTotal;
            try {
                newTotal = ServerGameLogic.DATA_SERVICE.executePost(
                        "/data/account/" + player.getAccountUuid() + "/fame/spend?amount=" + DYE_FAME_COST,
                        null, Long.class);
            } catch (Exception spendEx) {
                log.warn("[FameStore] Fame spend failed for player {} ({} fame, item {}): {}",
                        player.getId(), DYE_FAME_COST, itemId, spendEx.getMessage());
                notifyPlayer(mgr, player, "Not enough fame.");
                return;
            }
            // Grant the item.
            final GameItem granted = template.clone();
            granted.setUid(UUID.randomUUID().toString());
            granted.setStackCount(1);
            player.getInventory()[slot] = granted;
            // Refresh the cached fame total so the next OpenStore reflects it
            // without needing another GET.
            if (newTotal != null) player.setCachedAccountFame(newTotal);
            log.info("[FameStore] Player {} bought item {} for {} fame (now {} fame, slot {})",
                    player.getId(), itemId, DYE_FAME_COST, newTotal, slot);
            // Push the inventory update + an OpenStore refresh with the new
            // balance so the modal can reflect it without a refetch.
            sendInventoryUpdate(mgr, realm, player);
            mgr.enqueueServerPacket(player, new OpenFameStorePacket(player.getId(),
                    newTotal == null ? 0L : newTotal));
            mgr.persistPlayerAsync(player);
        } catch (Exception e) {
            log.error("[FameStore] handleBuy failed for player {}: {}", player.getId(), e.getMessage());
        }
    }

    private static void sendInventoryUpdate(RealmManagerServer mgr, Realm realm, Player player) throws Exception {
        final UpdatePacket update = realm.getPlayerAsPacket(player.getId());
        if (update != null) {
            mgr.enqueueServerPacket(player, update);
        }
    }

    private static void notifyPlayer(RealmManagerServer mgr, Player player, String message) {
        try {
            mgr.enqueueServerPacket(player,
                    TextPacket.from("SYSTEM", player.getName(), message));
        } catch (Exception ignored) {}
    }
}
