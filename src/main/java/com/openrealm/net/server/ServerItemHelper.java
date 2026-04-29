package com.openrealm.net.server;

import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.script.item.UseableItemScriptBase;
import com.openrealm.net.Packet;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.MoveItemPacket;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerItemHelper {

    /**
     * Returns true if {@code item} is allowed to occupy equipment slot
     * {@code slotIdx} (0-3) for {@code player}'s class. Mirrors the gating
     * logic used in {@link #handleMoveItemPacket}: targetSlot match,
     * class compatibility, and rejects consumables/stackables.
     *
     * Used as a single source of truth so we can validate equips on the
     * move path AND on character load (otherwise items saved to wrong slots
     * by an older bug stay there forever).
     */
    public static boolean canEquipInSlot(Player player, GameItem item, int slotIdx) {
        if (item == null) return false;
        if (slotIdx < 0 || slotIdx > 3) return false;
        if (item.isConsumable() || item.isStackable()) return false;
        if (item.getTargetSlot() >= 0 && item.getTargetSlot() != slotIdx) return false;
        return com.openrealm.game.contants.CharacterClass.isValidUser(player, item.getTargetClass());
    }

    /**
     * Sanity-pass after equipping items from saved character data: scan
     * equipment slots 0-3 and relocate any mismatched item into the first
     * empty inventory slot. Without this, a character that historically got
     * a wrong item into an equipment slot (via any past bug) keeps it forever
     * because equipSlots() doesn't validate.
     *
     * Returns the number of relocations performed.
     */
    public static int reconcileEquipment(Player player) {
        if (player == null) return 0;
        final GameItem[] inv = player.getInventory();
        if (inv == null) return 0;
        int relocated = 0;
        for (int slot = 0; slot < 4; slot++) {
            final GameItem cur = inv[slot];
            if (cur == null) continue;
            if (canEquipInSlot(player, cur, slot)) continue;
            // Mismatch: move to first empty inv slot (4-19). If none, drop.
            final int empty = player.firstEmptyInvSlot();
            if (empty >= 0) {
                inv[empty] = cur;
                inv[slot] = null;
                relocated++;
                log.warn("[Equipment] Relocated invalid item {} (targetSlot={}, targetClass={}) from equipment slot {} to inventory slot {} for player {}",
                        cur.getName(), cur.getTargetSlot(), cur.getTargetClass(), slot, empty, player.getId());
            } else {
                inv[slot] = null;
                relocated++;
                log.warn("[Equipment] Dropped invalid item {} from equipment slot {} (inventory full) for player {}",
                        cur.getName(), slot, player.getId());
            }
        }
        return relocated;
    }

    /**
     * Try to deposit `incoming` into the player's inventory. If `incoming` is a
     * stackable item, top-up any existing stacks of the same itemId before
     * spilling into a free slot. Returns true if any portion was deposited.
     * The `incoming` item's stackCount is mutated to reflect the leftover.
     */
    public static boolean tryDepositStackable(Player player, GameItem incoming) {
        if (incoming == null) return false;
        final int originalCount = incoming.getStackCount();
        final GameItem[] inv = player.getInventory();
        if (incoming.isStackable()) {
            // Top up existing stacks of the same itemId
            for (int i = 4; i < inv.length; i++) {
                final GameItem existing = inv[i];
                if (existing == null) continue;
                if (existing.getItemId() != incoming.getItemId()) continue;
                if (!existing.isStackable()) continue;
                final int room = existing.getMaxStack() - existing.getStackCount();
                if (room <= 0) continue;
                final int move = Math.min(room, incoming.getStackCount());
                existing.setStackCount(existing.getStackCount() + move);
                incoming.setStackCount(incoming.getStackCount() - move);
                if (incoming.getStackCount() <= 0) break;
            }
        }
        // Spill any remainder into the first empty slot. CRITICAL: park a
        // CLONE of `incoming` in the inventory and zero out `incoming`'s
        // stack count. The caller reads incoming.getStackCount() afterward
        // to decide how much to leave in the loot bag — without the zero,
        // the just-deposited stack stayed in both the bag and the inventory
        // (a full duplicate of every stackable pickup when no existing
        // stack was available to top up).
        if (incoming.getStackCount() > 0) {
            final int empty = player.firstEmptyInvSlot();
            if (empty >= 0) {
                inv[empty] = incoming.clone();
                incoming.setStackCount(0);
            }
        }
        // Return true if ANY portion was deposited — the top-up loop may
        // have moved some units even when the spill slot is unavailable.
        // The caller then sets the loot bag's stack count to the unmoved
        // remainder so partial pickups don't duplicate the topped-up units.
        return incoming.getStackCount() < originalCount;
    }

    public static void handleMoveItemPacket(RealmManagerServer mgr, Packet packet) throws Exception {
        final MoveItemPacket moveItemPacket = (MoveItemPacket) packet;
        ServerItemHelper.log.info("[ItemMoveHelper] Recieved MoveItem Packet from player {}", moveItemPacket.getPlayerId());

        final Realm realm = mgr.findPlayerRealm(moveItemPacket.getPlayerId());
        final Player player = realm.getPlayer(moveItemPacket.getPlayerId());

        final int fromIdx = moveItemPacket.getFromSlotIndex();
        final int targetIdx = moveItemPacket.getTargetSlotIndex();

        // Consume or drop HP/MP potion from potion storage (virtual slots 28/29)
        if (fromIdx == MoveItemPacket.HP_POTION_SLOT || fromIdx == MoveItemPacket.MP_POTION_SLOT) {
            final boolean isHp = fromIdx == MoveItemPacket.HP_POTION_SLOT;
            final int count = isHp ? player.getHpPotions() : player.getMpPotions();
            if (count <= 0) return;

            if (moveItemPacket.isConsume()) {
                if (isHp) player.consumeHpPotion();
                else player.consumeMpPotion();
            } else {
                // Drop one potion to ground as a loot item
                final int itemId = isHp ? Player.HP_POTION_ITEM_ID : Player.MP_POTION_ITEM_ID;
                final GameItem potionItem = GameDataManager.GAME_ITEMS.get(itemId);
                if (potionItem == null) return;
                if (isHp) player.setHpPotions(count - 1);
                else player.setMpPotions(count - 1);
                final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32);
                if (nearLoot != null && nearLoot.getFirstNullIdx() > -1) {
                    nearLoot.setItem(nearLoot.getFirstNullIdx(), potionItem.clone());
                    nearLoot.setContentsChanged(true);
                } else {
                    realm.addLootContainer(new LootContainer(LootTier.BROWN, player.getPos().clone(), potionItem.clone()));
                }
            }
            return;
        }

        final boolean fromIsGroundLoot = MoveItemPacket.isGroundLoot(fromIdx);
        final boolean fromIsInventory = MoveItemPacket.isInventory(fromIdx) || MoveItemPacket.isEquipment(fromIdx);

        // Reject any fromSlot that isn't a known valid range
        if (!fromIsInventory && !fromIsGroundLoot) {
            ServerItemHelper.log.warn("Player {} sent invalid from slot index {}", player.getId(), fromIdx);
            return;
        }

        // Reject any targetSlot that isn't -1 (drop) or a valid inventory/equipment slot
        if (targetIdx != -1 && !MoveItemPacket.isEquipment(targetIdx) && !MoveItemPacket.isInventory(targetIdx)) {
            ServerItemHelper.log.warn("Player {} sent invalid target slot index {}", player.getId(), targetIdx);
            return;
        }

        // Check for consumable item scripts (inventory only, not ground loot)
        if (moveItemPacket.isConsume() && fromIsInventory) {
            GameItem targetItem = player.getInventory()[fromIdx];
            if (targetItem == null) return;
            final UseableItemScriptBase script = mgr.getItemScript(targetItem.getItemId());
            if (script != null) {
                log.info("[ItemMoveHelper] Invoking usable item script for game item {}, player {}", targetItem, player);
                script.invokeUseItem(realm, player, player.getInventory()[fromIdx]);
                return;
            }
        }

        // Resolve source item
        final GameItem currentEquip = targetIdx == -1 ? null
                : player.getInventory()[targetIdx];
        GameItem from = null;
        if (fromIsInventory) {
            from = player.getInventory()[fromIdx];
        } else if (fromIsGroundLoot) {
            LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32);
            if (nearLoot != null) {
                int lootIdx = fromIdx - 20;
                if (lootIdx >= 0 && lootIdx < nearLoot.getItems().length) {
                    from = nearLoot.getItems()[lootIdx];
                }
            }
        }

        // Drop item from inventory/equipment to ground (ground loot items are already on the ground)
        if ((from != null) && moveItemPacket.isDrop() && fromIsInventory) {
            final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32);
            if (nearLoot == null) {
                realm.addLootContainer(new LootContainer(LootTier.BROWN, player.getPos().clone(), from.clone()));
                player.getInventory()[fromIdx] = null;
            } else if (nearLoot.getFirstNullIdx() > -1) {
                nearLoot.setItem(nearLoot.getFirstNullIdx(), from.clone());
                player.getInventory()[fromIdx] = null;
            }

        // Consume item (potions, food)
        } else if ((from != null) && from.isConsumable() && moveItemPacket.isConsume() && player.canConsume(from)
                && !MoveItemPacket.isGroundLoot(fromIdx)) {
            final Stats newStats = player.getStats().concat(from.getStats());
            player.setStats(newStats);
            if (from.getStats().getHp() > 0) {
                player.drinkHp();
            } else if (from.getStats().getMp() > 0) {
                player.drinkMp();
            }
            player.getInventory()[fromIdx] = null;

        // Equip: inventory (4-19) → equipment (0-3)
        } else if (MoveItemPacket.isInventory(fromIdx)
                && MoveItemPacket.isEquipment(targetIdx) && (from != null)) {
            if (!canEquipInSlot(player, from, targetIdx)) {
                ServerItemHelper.log.warn(
                        "Player {} rejected equip of {} (targetSlot={}, targetClass={}) into slot {}",
                        player.getId(), from.getName(), from.getTargetSlot(), from.getTargetClass(), targetIdx);
                return;
            }
            // If we're swapping with the equipped item, the displaced item
            // also has to fit somewhere — it's going to fromIdx (a regular
            // inv slot), which is unrestricted, so always safe.
            if (currentEquip != null) {
                player.getInventory()[fromIdx] = currentEquip.clone();
            } else {
                player.getInventory()[fromIdx] = null;
            }
            player.getInventory()[targetIdx] = from.clone();

        // Equip → equip swap (e.g. dragging from slot 0 to slot 3 directly).
        // Both endpoints must validate against the destination slot.
        } else if (MoveItemPacket.isEquipment(fromIdx)
                && MoveItemPacket.isEquipment(targetIdx) && (from != null)) {
            if (!canEquipInSlot(player, from, targetIdx)) {
                ServerItemHelper.log.warn(
                        "Player {} rejected equip-swap of {} into slot {} (mismatch)",
                        player.getId(), from.getName(), targetIdx);
                return;
            }
            if (currentEquip != null && !canEquipInSlot(player, currentEquip, fromIdx)) {
                ServerItemHelper.log.warn(
                        "Player {} rejected equip-swap: displaced item {} doesn't fit slot {}",
                        player.getId(), currentEquip.getName(), fromIdx);
                return;
            }
            // Pure swap.
            if (currentEquip != null) {
                player.getInventory()[fromIdx] = currentEquip.clone();
            } else {
                player.getInventory()[fromIdx] = null;
            }
            player.getInventory()[targetIdx] = from.clone();

        // Swap within inventory (4-19, including cross-bag)
        } else if (MoveItemPacket.isInventory(fromIdx)
                && MoveItemPacket.isInventory(targetIdx)) {
            GameItem to = player.getInventory()[targetIdx];
            if (to == null) {
                player.getInventory()[targetIdx] = from.clone();
                player.getInventory()[fromIdx] = null;
            } else if (from.isStackable() && to.isStackable()
                    && from.getItemId() == to.getItemId()
                    && to.getStackCount() < to.getMaxStack()) {
                // Merge same-itemId stacks. Move as much as fits, leave remainder in source.
                final int room = to.getMaxStack() - to.getStackCount();
                final int move = Math.min(room, from.getStackCount());
                to.setStackCount(to.getStackCount() + move);
                final int remaining = from.getStackCount() - move;
                if (remaining <= 0) {
                    player.getInventory()[fromIdx] = null;
                } else {
                    from.setStackCount(remaining);
                }
            } else {
                GameItem fromClone = from.clone();
                player.getInventory()[fromIdx] = to.clone();
                player.getInventory()[targetIdx] = fromClone;
            }

        // Unequip: equipment (0-3) → inventory (4-19). When the destination
        // slot already holds an item, this becomes a swap: that item moves
        // INTO the equip slot. It must therefore pass the same equip-slot
        // validation as a normal equip — otherwise a client could put any
        // item (e.g. a bow on a wizard) into an equip slot just by dragging
        // the equipped item onto an incompatible inventory item.
        } else if (MoveItemPacket.isEquipment(fromIdx)
                && MoveItemPacket.isInventory(targetIdx) && (from != null)) {
            if (currentEquip != null && !canEquipInSlot(player, currentEquip, fromIdx)) {
                ServerItemHelper.log.warn(
                        "Player {} rejected unequip-swap: incoming item {} doesn't fit equip slot {}",
                        player.getId(), currentEquip.getName(), fromIdx);
                return;
            }
            if (currentEquip != null) {
                player.getInventory()[fromIdx] = currentEquip.clone();
            } else {
                player.getInventory()[fromIdx] = null;
            }
            player.getInventory()[targetIdx] = from.clone();

        // Ground loot pickup (fromSlot 20-27)
        } else if (MoveItemPacket.isGroundLoot(fromIdx)) {
            final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(),
                    (int)(player.getSize() * 0.75));
            if (nearLoot == null) return;

            int lootIdx = fromIdx - 20;
            if (lootIdx < 0 || lootIdx >= nearLoot.getItems().length) return;

            final GameItem lootItem = nearLoot.getItems()[lootIdx];
            if (lootItem == null) return;

            // Intercept consumable HP/MP potions — route to potion storage, not inventory
            if (lootItem.getItemId() == Player.HP_POTION_ITEM_ID) {
                if (!player.addHpPotion()) {
                    ServerItemHelper.log.info("Player {} HP potion storage full (max {})", player.getId(), Player.MAX_CONSUMABLE_POTIONS);
                    return;
                }
                nearLoot.setItem(lootIdx, null);
                nearLoot.repackItems();
                nearLoot.setContentsChanged(true);
                return;
            }
            if (lootItem.getItemId() == Player.MP_POTION_ITEM_ID) {
                if (!player.addMpPotion()) {
                    ServerItemHelper.log.info("Player {} MP potion storage full (max {})", player.getId(), Player.MAX_CONSUMABLE_POTIONS);
                    return;
                }
                nearLoot.setItem(lootIdx, null);
                nearLoot.repackItems();
                nearLoot.setContentsChanged(true);
                return;
            }

            // Stackable pickup: top up existing stacks first, leave remainder on the ground.
            if (lootItem.isStackable()) {
                final GameItem incoming = lootItem.clone();
                final boolean deposited = tryDepositStackable(player, incoming);
                if (!deposited && incoming.getStackCount() > 0) {
                    ServerItemHelper.log.warn("Player {} inventory full, cannot pick up item", player.getId());
                    return;
                }
                if (incoming.getStackCount() > 0) {
                    // Partial pickup: leave remainder in the loot container
                    lootItem.setStackCount(incoming.getStackCount());
                    nearLoot.setContentsChanged(true);
                } else {
                    nearLoot.setItem(lootIdx, null);
                    nearLoot.repackItems();
                    nearLoot.setContentsChanged(true);
                }
                return;
            }

            int emptySlot = player.firstEmptyInvSlot();
            if (emptySlot < 0) {
                ServerItemHelper.log.warn("Player {} inventory full, cannot pick up item", player.getId());
                return;
            }

            player.getInventory()[emptySlot] = lootItem.clone();
            nearLoot.setItem(lootIdx, null);
            // Re-pack items to fill the gap left by the picked-up item
            nearLoot.repackItems();
            // Flag container as changed so the next LoadPacket broadcasts the update
            nearLoot.setContentsChanged(true);
        }
    }
}
