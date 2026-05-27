package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.PotionStorage;
import com.openrealm.net.Packet;
import com.openrealm.net.client.packet.OpenPotionStoragePacket;
import com.openrealm.net.client.packet.PotionStorageUpdatePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.core.IOService;
import com.openrealm.net.entity.NetAttributeModifier;
import com.openrealm.net.entity.NetEnchantment;
import com.openrealm.net.entity.NetGameItem;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.PotionStorageMovePacket;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-side handlers for the per-player Potion Storage container.
 *
 * Behavior parallels the existing vault-chest plumbing but is keyed to a
 * fixed interaction tile (id 328 in vault map 1, row 20 col 16) rather than
 * placed map entities. Containers are 32 slots, restricted to stackable
 * items and gems via {@link PotionStorage#canStore(GameItem)}.
 *
 * Persistence flows through {@link Realm#serializePotionStorageForSave()}
 * just like vault chests; the same chestsLoaded gate prevents wipe-on-error.
 */
@Slf4j
public class ServerPotionStorageHelper {

    /** Open the player's potion-storage container[0], creating one if absent. */
    public static void handleOpen(RealmManagerServer mgr, Player player) {
        try {
            final Realm realm = mgr.findPlayerRealm(player.getId());
            if (realm == null) return;
            final PotionStorage container = ensureContainer(realm, player);
            final NetGameItem[] netItems = toNetItems(container.getItems());
            mgr.enqueueServerPacket(player, new OpenPotionStoragePacket(player.getId(), netItems));
        } catch (Exception e) {
            log.error("[PotionStorage] handleOpen failed for player {}: {}", player.getId(), e.getMessage());
        }
    }

    /** Validate + execute a move/swap/merge between inventory and storage. */
    public static void handleMove(RealmManagerServer mgr, Packet packet) {
        try {
            final PotionStorageMovePacket p = (PotionStorageMovePacket) packet;
            final Player player = mgr.getPlayerByRemoteAddress(packet.getSrcIp());
            if (player == null) return;
            final Realm realm = mgr.findPlayerRealm(player.getId());
            if (realm == null) return;
            final PotionStorage container = ensureContainer(realm, player);

            final byte fromSide = p.getFromSide();
            final byte toSide = p.getToSide();
            final int fromIdx = p.getFromIdx();
            int toIdx = p.getToIdx();
            // toIdx == -1 with toSide == STORAGE is the "quick-store" /
            // auto-place sentinel: server picks the best destination
            // (first mergeable stack of the same itemId, else first empty
            // slot). Used by right-click in inventory while the modal is
            // open. We don't auto-route to inventory side because the
            // user's inventory has 16 slots and the choice is contextual.
            if (toSide == PotionStorageMovePacket.SIDE_STORAGE && toIdx == -1) {
                final GameItem srcPeek = readSlot(player, container, fromSide, fromIdx);
                if (srcPeek == null) return;
                if (!PotionStorage.canStore(srcPeek)) {
                    log.info("[PotionStorage] Quick-store rejected non-stackable/non-gem item {} from p{}",
                            srcPeek.getName(), player.getId());
                    return;
                }
                final int placed = findQuickStoreSlot(container, srcPeek);
                if (placed < 0) {
                    log.info("[PotionStorage] Quick-store: no available slot for {} (p{})",
                            srcPeek.getName(), player.getId());
                    return;
                }
                toIdx = placed;
            }
            if (!isValidIdx(fromSide, fromIdx, player) || !isValidIdx(toSide, toIdx, player)) {
                log.warn("[PotionStorage] Invalid move indices from p{}", player.getId());
                return;
            }
            if (fromSide == toSide && fromIdx == toIdx) return;

            final GameItem src = readSlot(player, container, fromSide, fromIdx);
            if (src == null) return;
            final GameItem dst = readSlot(player, container, toSide, toIdx);

            // Whitelist: anything entering the storage side must pass canStore.
            if (toSide == PotionStorageMovePacket.SIDE_STORAGE && !PotionStorage.canStore(src)) {
                log.info("[PotionStorage] Rejected non-stackable/non-gem item {} from p{}",
                        src.getName(), player.getId());
                return;
            }
            // The item we displace from a storage slot also has to qualify if it
            // ends up going back to inventory (it always does, since we don't
            // gate inv→storage on dst). Inventory is unrestricted, so no check
            // is needed for moves landing in inventory.

            // Stack-merge: same item id + both stackable + dst not full.
            if (dst != null && canMerge(src, dst)) {
                final int room = dst.getMaxStack() - dst.getStackCount();
                if (room > 0) {
                    final int moved = Math.min(room, src.getStackCount());
                    dst.setStackCount(dst.getStackCount() + moved);
                    if (moved >= src.getStackCount()) {
                        writeSlot(player, container, fromSide, fromIdx, null);
                    } else {
                        src.setStackCount(src.getStackCount() - moved);
                    }
                    pushUpdates(mgr, realm, player, container);
                    persistAsync(realm, player);
                    return;
                }
            }

            // Inventory drags are PLACE-ONLY: dropping an inventory item
            // onto an occupied, non-mergeable storage slot must not displace
            // the existing item back into inventory (per user policy "you
            // can only PLACE it in the potion storage - not also move items
            // in the storage"). Storage-side drags can still swap freely.
            if (fromSide == PotionStorageMovePacket.SIDE_INV
                    && toSide == PotionStorageMovePacket.SIDE_STORAGE
                    && dst != null) {
                log.info("[PotionStorage] Refusing inv->storage swap onto occupied slot {} for p{}",
                        toIdx, player.getId());
                // Re-broadcast current state so the client snaps back any
                // optimistic visual it may have applied.
                pushUpdates(mgr, realm, player, container);
                return;
            }

            // Otherwise: plain swap. Only reachable from storage->inv,
            // storage->storage, or inv->empty-storage paths now.
            writeSlot(player, container, toSide, toIdx, src);
            writeSlot(player, container, fromSide, fromIdx, dst);
            pushUpdates(mgr, realm, player, container);
            persistAsync(realm, player);
        } catch (Exception e) {
            log.error("[PotionStorage] handleMove failed: {}", e.getMessage(), e);
        }
    }

    private static boolean canMerge(GameItem a, GameItem b) {
        if (a == null || b == null) return false;
        if (a.getItemId() != b.getItemId()) return false;
        if (!a.isStackable() || !b.isStackable()) return false;
        return b.getStackCount() < b.getMaxStack();
    }

    /**
     * Auto-place destination for quick-store: first mergeable existing
     * stack of the same itemId (so we top up partial stacks before
     * burning a fresh slot), else the first empty slot. Returns -1 if
     * the container is full and the item can't merge anywhere.
     */
    private static int findQuickStoreSlot(PotionStorage container, GameItem incoming) {
        // Pass 1: prefer merging into an existing partial stack of the
        // same itemId. Multiple partial stacks of the same item are
        // possible after partial merges; topping up the FIRST one
        // (lowest slot index) keeps storage compact.
        for (int i = 0; i < container.getItems().length; i++) {
            final GameItem at = container.getItems()[i];
            if (at != null && canMerge(incoming, at)) return i;
        }
        // Pass 2: first empty slot.
        for (int i = 0; i < container.getItems().length; i++) {
            if (container.getItems()[i] == null) return i;
        }
        return -1;
    }

    private static boolean isValidIdx(byte side, int idx, Player player) {
        if (side == PotionStorageMovePacket.SIDE_INV) {
            return idx >= 0 && player.getInventory() != null && idx < player.getInventory().length;
        }
        if (side == PotionStorageMovePacket.SIDE_STORAGE) {
            return idx >= 0 && idx < PotionStorage.SIZE;
        }
        return false;
    }

    private static GameItem readSlot(Player player, PotionStorage container, byte side, int idx) {
        return side == PotionStorageMovePacket.SIDE_INV ? player.getInventory()[idx] : container.getItems()[idx];
    }

    private static void writeSlot(Player player, PotionStorage container, byte side, int idx, GameItem item) {
        if (side == PotionStorageMovePacket.SIDE_INV) {
            player.getInventory()[idx] = item;
        } else {
            container.getItems()[idx] = item;
        }
    }

    private static PotionStorage ensureContainer(Realm realm, Player player) {
        List<PotionStorage> list = realm.getPlayerPotionStorage().get(player.getId());
        if (list == null) {
            list = new ArrayList<>();
            realm.getPlayerPotionStorage().put(player.getId(), list);
        }
        if (list.isEmpty()) {
            list.add(new PotionStorage(0));
        }
        return list.get(0);
    }

    private static void pushUpdates(RealmManagerServer mgr, Realm realm, Player player, PotionStorage container)
            throws Exception {
        mgr.enqueueServerPacket(player, new PotionStorageUpdatePacket(player.getId(), toNetItems(container.getItems())));
        final UpdatePacket inv = realm.getPlayerAsPacket(player.getId());
        if (inv != null) mgr.enqueueServerPacket(player, inv);
    }

    /**
     * Persist the player's potion storage to the data service after every
     * move. Originally we only saved on portal exit / disconnect / transfer,
     * but those paths can race with server shutdown or be bypassed entirely
     * (e.g., the user force-closes the game inside the vault). Saving after
     * each move costs one ~80ms async POST and guarantees no in-memory state
     * is lost no matter how the player leaves.
     *
     * Same `chestsLoaded` gate as the existing chest save flow — we refuse
     * to write while setupChests is mid-flight, otherwise a load race could
     * bulk-replace the persisted state with [].
     */
    private static void persistAsync(Realm realm, Player player) {
        try {
            final List<ChestDto> snapshot = realm.serializePotionStorageForSave(player.getId());
            if (snapshot == null) return; // gate not lifted yet
            ServerGameLogic.DATA_SERVICE
                    .executePostAsync("/data/account/" + player.getAccountUuid() + "/potion-storage",
                            snapshot, PlayerAccountDto.class)
                    .exceptionally(ex -> {
                        log.warn("[PotionStorage] Async persist failed for player {}: {}",
                                player.getId(), ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("[PotionStorage] persistAsync threw for player {}: {}", player.getId(), e.getMessage());
        }
    }

    private static NetGameItem[] toNetItems(GameItem[] items) {
        final NetGameItem[] out = new NetGameItem[PotionStorage.SIZE];
        if (items == null) return out;
        for (int i = 0; i < PotionStorage.SIZE && i < items.length; i++) {
            out[i] = toNetGameItem(items[i]);
        }
        return out;
    }

    /**
     * Hand-rolled GameItem → NetGameItem copy that preserves stackCount,
     * enchantments, attribute modifiers, and forge metadata. ModelMapper drops
     * nested generics on this DTO, so the inventory broadcast path uses the
     * same approach (see UpdatePacket.toNetGameItem). Kept private here to
     * avoid coupling to that method's visibility.
     */
    private static NetGameItem toNetGameItem(GameItem item) {
        if (item == null) return new NetGameItem();
        final NetGameItem net = IOService.mapModel(item, NetGameItem.class);
        net.setStackable(item.isStackable());
        net.setMaxStack(item.getMaxStack());
        net.setStackCount(item.getStackCount());
        net.setCategory(item.getCategory());
        net.setForgeStatId(item.getForgeStatId());
        net.setForgeSlotId(item.getForgeSlotId());
        net.setRarity(item.getRarity());
        net.setGemstoneType(item.getGemstoneType());
        net.setGemPixelX(item.getGemPixelX());
        net.setGemPixelY(item.getGemPixelY());
        net.setGemPixelColor(item.getGemPixelColor());
        final List<NetEnchantment> ench;
        if (item.getEnchantments() != null && !item.getEnchantments().isEmpty()) {
            ench = new ArrayList<>(item.getEnchantments().size());
            for (Enchantment e : item.getEnchantments()) {
                ench.add(new NetEnchantment(e.getStatId(), e.getDeltaValue(), e.getPixelX(), e.getPixelY(),
                        e.getPixelColor()));
            }
        } else {
            ench = new ArrayList<>();
        }
        net.setEnchantments(ench);
        final List<NetAttributeModifier> mods;
        if (item.getAttributeModifiers() != null && !item.getAttributeModifiers().isEmpty()) {
            mods = new ArrayList<>(item.getAttributeModifiers().size());
            for (AttributeModifier m : item.getAttributeModifiers()) {
                mods.add(new NetAttributeModifier(m.getStatId(), m.getDeltaValue()));
            }
        } else {
            mods = new ArrayList<>();
        }
        net.setAttributeModifiers(mods);
        return net;
    }
}
