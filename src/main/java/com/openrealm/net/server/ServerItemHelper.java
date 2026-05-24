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
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.SplitStackPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerItemHelper {

    /**
     * One-shot migration from pre-Phase-1B saves (4 equip slots) to current 5-slot layout.
     * Idempotent — returns input unchanged if already new-format.
     */
    public static Map<Integer, GameItem> migrateLegacySlotLayout(Map<Integer, GameItem> loaded) {
        if (loaded == null || loaded.isEmpty()) return loaded;
        final GameItem atSlot1 = loaded.get(1);
        final GameItem atSlot4 = loaded.get(4);
        final boolean slot1LooksNew = (atSlot1 == null) || (atSlot1.getTargetSlot() == 1);
        // Slot 4 holding targetSlot==4 means new-layout ring slot, NOT legacy backpack.
        final boolean slot4LooksNew = (atSlot4 == null) || (atSlot4.getTargetSlot() == 4);
        if (slot1LooksNew && slot4LooksNew) {
            return loaded;
        }
        final boolean anyOldBackpack = loaded.keySet().stream().anyMatch(k -> k != null && k >= 5 && k <= 19);
        final boolean anyNewBackpack = loaded.keySet().stream().anyMatch(k -> k != null && k >= 5 && k <= 20);
        if (slot1LooksNew && (!anyOldBackpack || anyNewBackpack)) {
            return loaded;
        }
        final Map<Integer, GameItem> migrated = new HashMap<>();
        if (loaded.get(0) != null) migrated.put(0, loaded.get(0));
        if (loaded.get(2) != null) migrated.put(1, loaded.get(2)); // armor 2 -> 1
        if (loaded.get(3) != null) migrated.put(4, loaded.get(3)); // ring 3 -> 4
        for (int oldSlot = 4; oldSlot <= 19; oldSlot++) {
            final GameItem it = loaded.get(oldSlot);
            if (it != null) migrated.put(oldSlot + 1, it);
        }
        final GameItem legacyAbility = loaded.get(1);
        if (legacyAbility != null) {
            int placed = -1;
            for (int bp = 5; bp <= 20; bp++) {
                if (!migrated.containsKey(bp)) { migrated.put(bp, legacyAbility); placed = bp; break; }
            }
            if (placed < 0) {
                log.warn("[SlotMigration] No room to relocate legacy ability item {} — dropping",
                        legacyAbility.getName());
            } else {
                log.info("[SlotMigration] Legacy ability {} relocated from slot 1 to backpack slot {}",
                        legacyAbility.getName(), placed);
            }
        }
        log.info("[SlotMigration] Migrated {} item(s) from legacy to new slot layout",
                migrated.size());
        return migrated;
    }


    /** Single source of truth for equip validity — used by move path AND character load. */
    public static boolean canEquipInSlot(Player player, GameItem item, int slotIdx) {
        if (item == null) return false;
        if (slotIdx < 0 || slotIdx >= Player.EQUIPMENT_SLOT_COUNT) {
            log.warn("[canEquipInSlot] reject {} into slot {} — slot out of range", item.getName(), slotIdx);
            return false;
        }
        if (item.isConsumable() || item.isStackable()) {
            log.warn("[canEquipInSlot] reject {} into slot {} — consumable={} stackable={}",
                    item.getName(), slotIdx, item.isConsumable(), item.isStackable());
            return false;
        }
        // Strict slot match — targetSlot==-1 (legacy ability items) must be rejected from every slot.
        if (item.getTargetSlot() != slotIdx) {
            log.warn("[canEquipInSlot] reject {} into slot {} — targetSlot={} (expected {})",
                    item.getName(), slotIdx, item.getTargetSlot(), slotIdx);
            return false;
        }
        final boolean classOk = CharacterClass.canEquip(player, item);
        if (!classOk) {
            log.warn("[canEquipInSlot] reject {} into slot {} — class mismatch: player.classId={} item.targetClass={} item.itemClass={}",
                    item.getName(), slotIdx, player.getClassId(), item.getTargetClass(), item.getItemClass());
        }
        return classOk;
    }

    /** Scan equipment slots after load and relocate any mismatched items. Returns relocation count. */
    public static int reconcileEquipment(Player player) {
        if (player == null) return 0;
        final GameItem[] inv = player.getInventory();
        if (inv == null) return 0;
        int relocated = 0;
        for (int slot = 0; slot < Player.EQUIPMENT_SLOT_COUNT; slot++) {
            final GameItem cur = inv[slot];
            if (cur == null) continue;
            if (canEquipInSlot(player, cur, slot)) continue;
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

    public static void handleSplitStackPacket(RealmManagerServer mgr, Packet packet) {
        try {
            final SplitStackPacket p = (SplitStackPacket) packet;
            final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
            if (realm == null) return;
            final Player player = realm.getPlayer(p.getPlayerId());
            if (player == null) return;
            final int fromSlot = p.getFromSlot();
            if (fromSlot < Player.EQUIPMENT_SLOT_COUNT || fromSlot >= player.getInventory().length) {
                log.warn("[SplitStack] Player {} sent out-of-range slot {}", player.getId(), fromSlot);
                return;
            }
            final GameItem src = player.getInventory()[fromSlot];
            if (src == null || !src.isStackable() || src.getStackCount() < 2) {
                log.info("[SplitStack] Player {} slot {} is not a splittable stack", player.getId(), fromSlot);
                return;
            }
            int empty = -1;
            for (int i = Player.EQUIPMENT_SLOT_COUNT; i < player.getInventory().length; i++) {
                if (player.getInventory()[i] == null) { empty = i; break; }
            }
            if (empty < 0) {
                log.info("[SplitStack] Player {} inventory full — refusing split of {}",
                        player.getId(), src.getName());
                return;
            }
            // ceil(N/2) stays at source, floor(N/2) goes to empty.
            final int total = src.getStackCount();
            final int splitCount = total / 2;
            final int sourceCount = total - splitCount;
            src.setStackCount(sourceCount);
            final GameItem split = src.clone();
            split.setUid(UUID.randomUUID().toString());
            split.setStackCount(splitCount);
            player.getInventory()[empty] = split;

            final UpdatePacket update = realm.getPlayerAsPacket(player.getId());
            if (update != null) mgr.enqueueServerPacket(player, update);

            log.info("[SplitStack] Player {} split {} ({} -> {} + {}) from slot {} into slot {}",
                    player.getId(), src.getName(), total, sourceCount, splitCount, fromSlot, empty);
        } catch (Exception e) {
            log.error("[SplitStack] handler failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Deposit {@code incoming} stackable into inventory, topping up existing stacks first.
     * Mutates {@code incoming.stackCount} to the leftover. Returns true if any portion was deposited.
     */
    public static boolean tryDepositStackable(Player player, GameItem incoming) {
        if (incoming == null) return false;
        final int originalCount = incoming.getStackCount();
        final GameItem[] inv = player.getInventory();
        if (incoming.isStackable()) {
            for (int i = Player.EQUIPMENT_SLOT_COUNT; i < inv.length; i++) {
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
        // CRITICAL: park a CLONE in inventory and zero out incoming.stackCount —
        // caller reads incoming.getStackCount() to decide loot-bag remainder.
        if (incoming.getStackCount() > 0) {
            final int empty = player.firstEmptyInvSlot();
            if (empty >= 0) {
                inv[empty] = incoming.clone();
                incoming.setStackCount(0);
            }
        }
        return incoming.getStackCount() < originalCount;
    }

    public static void handleMoveItemPacket(RealmManagerServer mgr, Packet packet) throws Exception {
        final MoveItemPacket moveItemPacket = (MoveItemPacket) packet;

        final Realm realm = mgr.findPlayerRealm(moveItemPacket.getPlayerId());
        final Player player = realm.getPlayer(moveItemPacket.getPlayerId());

        final int fromIdx = moveItemPacket.getFromSlotIndex();
        final int targetIdx = moveItemPacket.getTargetSlotIndex();
        ServerItemHelper.log.info("[ItemMove] player={} from={} target={} drop={} consume={} pos=({}, {})",
                moveItemPacket.getPlayerId(), fromIdx, targetIdx,
                moveItemPacket.isDrop(), moveItemPacket.isConsume(),
                player != null ? player.getPos().x : -1f,
                player != null ? player.getPos().y : -1f);

        // Consume or drop HP/MP potion from potion storage (virtual slots 28/29)
        if (fromIdx == MoveItemPacket.HP_POTION_SLOT || fromIdx == MoveItemPacket.MP_POTION_SLOT) {
            final boolean isHp = fromIdx == MoveItemPacket.HP_POTION_SLOT;
            final int count = isHp ? player.getHpPotions() : player.getMpPotions();
            if (count <= 0) return;

            if (moveItemPacket.isConsume()) {
                if (isHp) player.consumeHpPotion();
                else player.consumeMpPotion();
                if (player.getMetrics() != null) {
                    player.getMetrics().recordPotion(isHp);
                }
            } else {
                final int itemId = isHp ? Player.HP_POTION_ITEM_ID : Player.MP_POTION_ITEM_ID;
                final GameItem potionItem = GameDataManager.GAME_ITEMS.get(itemId);
                if (potionItem == null) return;
                if (isHp) player.setHpPotions(count - 1);
                else player.setMpPotions(count - 1);
                final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32, player.getId());
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

        if (!fromIsInventory && !fromIsGroundLoot) {
            ServerItemHelper.log.warn("Player {} sent invalid from slot index {}", player.getId(), fromIdx);
            return;
        }

        if (targetIdx != -1 && !MoveItemPacket.isEquipment(targetIdx) && !MoveItemPacket.isInventory(targetIdx)) {
            ServerItemHelper.log.warn("Player {} sent invalid target slot index {}", player.getId(), targetIdx);
            return;
        }

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

        final GameItem currentEquip = targetIdx == -1 ? null
                : player.getInventory()[targetIdx];
        GameItem from = null;
        if (fromIsInventory) {
            from = player.getInventory()[fromIdx];
        } else if (fromIsGroundLoot) {
            LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32, player.getId());
            if (nearLoot != null) {
                int lootIdx = fromIdx - MoveItemPacket.groundLootBase();
                if (lootIdx >= 0 && lootIdx < nearLoot.getItems().length) {
                    from = nearLoot.getItems()[lootIdx];
                }
            }
        }

        if ((from != null) && moveItemPacket.isDrop() && fromIsInventory) {
            final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(), 32, player.getId());
            if (nearLoot == null) {
                realm.addLootContainer(new LootContainer(LootTier.BROWN, player.getPos().clone(), from.clone()));
                player.getInventory()[fromIdx] = null;
            } else if (nearLoot.getFirstNullIdx() > -1) {
                nearLoot.setItem(nearLoot.getFirstNullIdx(), from.clone());
                player.getInventory()[fromIdx] = null;
            }

        } else if ((from != null) && from.isConsumable() && moveItemPacket.isConsume() && player.canConsume(from)
                && !MoveItemPacket.isGroundLoot(fromIdx)) {
            final Stats newStats = player.getStats().concat(from.getStats());
            player.setStats(newStats);
            if (from.getStats().getHp() > 0) {
                player.drinkHp();
            } else if (from.getStats().getMp() > 0) {
                player.drinkMp();
            }
            if (from.isStackable() && from.getStackCount() > 1) {
                from.setStackCount(from.getStackCount() - 1);
            } else {
                player.getInventory()[fromIdx] = null;
            }

        } else if (MoveItemPacket.isInventory(fromIdx)
                && MoveItemPacket.isEquipment(targetIdx) && (from != null)) {
            if (!canEquipInSlot(player, from, targetIdx)) {
                ServerItemHelper.log.warn(
                        "Player {} rejected equip of {} (targetSlot={}, targetClass={}) into slot {}",
                        player.getId(), from.getName(), from.getTargetSlot(), from.getTargetClass(), targetIdx);
                return;
            }
            if (currentEquip != null) {
                player.getInventory()[fromIdx] = currentEquip.clone();
            } else {
                player.getInventory()[fromIdx] = null;
            }
            player.getInventory()[targetIdx] = from.clone();

        } else if (MoveItemPacket.isEquipment(fromIdx)
                && MoveItemPacket.isEquipment(targetIdx) && (from != null)) {
            // Equip-swap: both endpoints must validate against the destination slot.
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
            if (currentEquip != null) {
                player.getInventory()[fromIdx] = currentEquip.clone();
            } else {
                player.getInventory()[fromIdx] = null;
            }
            player.getInventory()[targetIdx] = from.clone();

        } else if (MoveItemPacket.isInventory(fromIdx)
                && MoveItemPacket.isInventory(targetIdx)) {
            GameItem to = player.getInventory()[targetIdx];
            if (to == null) {
                player.getInventory()[targetIdx] = from.clone();
                player.getInventory()[fromIdx] = null;
            } else if (from.isStackable() && to.isStackable()
                    && from.getItemId() == to.getItemId()
                    && to.getStackCount() < to.getMaxStack()) {
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

        } else if (MoveItemPacket.isEquipment(fromIdx)
                && MoveItemPacket.isInventory(targetIdx) && (from != null)) {
            // Unequip-with-swap: the displaced inv item enters an equip slot — must validate.
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

        } else if (MoveItemPacket.isGroundLoot(fromIdx)) {
            final int pickupRadius = player.getSize() + 24;
            final LootContainer nearLoot = mgr.getClosestLootContainer(realm.getRealmId(), player.getPos(),
                    pickupRadius, player.getId());
            if (nearLoot == null) {
                ServerItemHelper.log.info(
                        "[ItemMoveHelper] Player {} ground-loot pickup: no loot container within {}px of pos ({}, {})",
                        player.getId(), pickupRadius, player.getPos().x, player.getPos().y);
                return;
            }

            int lootIdx = fromIdx - MoveItemPacket.groundLootBase();
            if (lootIdx < 0 || lootIdx >= nearLoot.getItems().length) return;

            final GameItem lootItem = nearLoot.getItems()[lootIdx];
            if (lootItem == null) return;

            // HP/MP potions route to potion storage, not inventory.
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

            if (lootItem.isStackable()) {
                final GameItem incoming = lootItem.clone();
                final boolean deposited = tryDepositStackable(player, incoming);
                if (!deposited && incoming.getStackCount() > 0) {
                    ServerItemHelper.log.warn("Player {} inventory full, cannot pick up item", player.getId());
                    return;
                }
                if (incoming.getStackCount() > 0) {
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
            nearLoot.repackItems();
            nearLoot.setContentsChanged(true);
        }
    }
}
