package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Rarity;
import com.openrealm.game.model.TileModel;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileMap;
import com.openrealm.net.Packet;
import com.openrealm.net.client.packet.OpenForgePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.net.server.packet.ConsumeShardStackPacket;
import com.openrealm.net.server.packet.ForgeDisenchantPacket;
import com.openrealm.net.server.packet.ForgeEnchantPacket;
import com.openrealm.net.server.packet.InteractTilePacket;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-side handlers for the pixel-forge enchantment system: stat-shard
 * consumption, tile-based forge interaction, enchant, and bulk disenchant.
 */
@Slf4j
public class ServerForgeHelper {

    // Shard itemId -> Crystal itemId mapping. Shards 800..807 -> Crystals 808..815
    public static final int SHARD_ITEM_BASE = 800;
    public static final int CRYSTAL_ITEM_BASE = 808;
    public static final int ESSENCE_ITEM_BASE = 816;
    // Universal essence: a single non-stackable item that substitutes for any
    // 50 typed-essence in the forge. Consumed entirely on use.
    public static final int UNIVERSAL_ESSENCE_ITEM_ID = 820;

    // Pixel colors used to paint the forged pixel onto the item sprite, by statId 0..7.
    // ARGB format: 0xAARRGGBB. statId order matches Stats: VIT, WIS, HP, MP, ATT, DEF, SPD, DEX.
    public static final int[] STAT_COLORS = new int[] {
            0xFFC81F1F, // VIT red
            0xFF3F6CFF, // WIS blue
            0xFFF0A3A3, // HP pink
            0xFFA070D8, // MP purple
            0xFFC850DC, // ATT magenta
            0xFF1A1A1A, // DEF black
            0xFF5FD06F, // SPD green
            0xFFF08C2C  // DEX orange
    };

    /**
     * Hard ceiling — actual cap is per-item from {@link com.openrealm.game.entity.item.Rarity#gemSlots}.
     * Kept as a sanity floor so a misconfigured rarity ordinal cannot exceed
     * the original design bound.
     */
    public static final int MAX_ENCHANTMENTS_PER_ITEM = 6;
    public static final int ENCHANT_ESSENCE_COST = 50;
    public static final int SHARDS_PER_CRYSTAL = 10;
    // HP and MP scale into the hundreds, so a +1 enchantment is essentially
    // invisible — non-pool stats (VIT/WIS/ATT/DEF/SPD/DEX) cap at 75-ish and
    // every point matters. Boost HP/MP enchant magnitude to keep parity.
    // statId order: 0=VIT 1=WIS 2=HP 3=MP 4=ATT 5=DEF 6=SPD 7=DEX
    public static final int HP_MP_ENCHANT_DELTA = 5;
    public static final int DEFAULT_ENCHANT_DELTA = 1;

    public static void handleConsumeShardStack(RealmManagerServer mgr, Packet packet) throws Exception {
        final ConsumeShardStackPacket p = (ConsumeShardStackPacket) packet;
        final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
        if (realm == null) return;
        final Player player = realm.getPlayer(p.getPlayerId());
        if (player == null) return;

        final int slot = p.getFromSlotIndex();
        if (slot < 4 || slot >= player.getInventory().length) {
            log.warn("[Forge] Player {} tried to consume shard from invalid slot {}", player.getId(), slot);
            return;
        }
        final GameItem stack = player.getInventory()[slot];
        if (stack == null) return;
        if (!"shard".equals(stack.getCategory())) {
            log.warn("[Forge] Player {} tried to consume non-shard item {} as shard", player.getId(), stack.getName());
            return;
        }
        if (stack.getStackCount() < SHARDS_PER_CRYSTAL) {
            log.warn("[Forge] Player {} tried to consume shard stack of {} (needs {})",
                    player.getId(), stack.getStackCount(), SHARDS_PER_CRYSTAL);
            return;
        }
        final byte statId = stack.getForgeStatId();
        if (statId < 0 || statId > 7) {
            log.warn("[Forge] Shard {} has invalid forgeStatId {}", stack.getName(), statId);
            return;
        }
        final GameItem crystalTemplate = GameDataManager.GAME_ITEMS.get(CRYSTAL_ITEM_BASE + statId);
        if (crystalTemplate == null) {
            log.warn("[Forge] No crystal definition for statId {}", statId);
            return;
        }

        // Deduct 10 shards. If the stack had exactly 10, replace with the crystal in the same slot;
        // otherwise leave the partial shard stack and place the crystal in the first free slot.
        if (stack.getStackCount() == SHARDS_PER_CRYSTAL) {
            final GameItem crystal = crystalTemplate.clone();
            crystal.setUid(UUID.randomUUID().toString());
            crystal.setStackCount(1);
            player.getInventory()[slot] = crystal;
        } else {
            stack.setStackCount(stack.getStackCount() - SHARDS_PER_CRYSTAL);
            final int empty = player.firstEmptyInvSlot();
            if (empty < 0) {
                // No room — refund and bail
                stack.setStackCount(stack.getStackCount() + SHARDS_PER_CRYSTAL);
                log.warn("[Forge] Player {} inventory full, cannot place crystal", player.getId());
                return;
            }
            final GameItem crystal = crystalTemplate.clone();
            crystal.setUid(UUID.randomUUID().toString());
            crystal.setStackCount(1);
            player.getInventory()[empty] = crystal;
        }

        sendInventoryUpdate(mgr, realm, player);
    }

    public static void handleInteractTile(RealmManagerServer mgr, Packet packet) throws Exception {
        final InteractTilePacket p = (InteractTilePacket) packet;
        final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
        if (realm == null) return;
        final Player player = realm.getPlayer(p.getPlayerId());
        if (player == null) return;

        // Validate proximity: player must be within ~3 tiles of the target tile
        final int tileSize = GlobalConstants.BASE_TILE_SIZE;
        final float playerTileX = player.getPos().x / tileSize;
        final float playerTileY = player.getPos().y / tileSize;
        final float dx = playerTileX - (p.getTileX() + 0.5f);
        final float dy = playerTileY - (p.getTileY() + 0.5f);
        if ((dx * dx + dy * dy) > (3.0f * 3.0f)) {
            log.warn("[Forge] Player {} too far from tile ({},{})", player.getId(), p.getTileX(), p.getTileY());
            return;
        }

        final String interactionType = lookupTileInteraction(realm, p.getTileX(), p.getTileY());
        if (interactionType == null) {
            log.warn("[Forge] Tile ({},{}) is not interactive", p.getTileX(), p.getTileY());
            return;
        }

        if ("forge".equals(interactionType)) {
            final OpenForgePacket reply = new OpenForgePacket(player.getId());
            mgr.enqueueServerPacket(player, reply);
        } else if ("fame_store".equals(interactionType)) {
            ServerFameStoreHelper.handleOpenStore(mgr, player);
        }
    }

    public static void handleForgeEnchant(RealmManagerServer mgr, Packet packet) throws Exception {
        final ForgeEnchantPacket p = (ForgeEnchantPacket) packet;
        final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
        if (realm == null) return;
        final Player player = realm.getPlayer(p.getPlayerId());
        if (player == null) return;

        final int targetSlot = p.getTargetItemSlot();
        final int crystalSlot = p.getCrystalSlotIndex();
        final int essenceSlot = p.getEssenceSlotIndex();
        if (targetSlot < 0 || targetSlot >= player.getInventory().length
                || crystalSlot < 0 || crystalSlot >= player.getInventory().length
                || essenceSlot < 0 || essenceSlot >= player.getInventory().length) {
            log.warn("[Forge] Player {} sent invalid forge slot indices", player.getId());
            return;
        }
        final GameItem target = player.getInventory()[targetSlot];
        final GameItem crystal = player.getInventory()[crystalSlot];
        final GameItem essence = player.getInventory()[essenceSlot];
        if (target == null || crystal == null || essence == null) {
            log.warn("[Forge] Player {} sent forge with empty slot", player.getId());
            return;
        }
        // Target must be equipment (non-stackable, has a targetSlot 0..3)
        if (target.isStackable() || target.getTargetSlot() < 0 || target.getTargetSlot() > 3) {
            log.warn("[Forge] Cannot enchant non-equipment item {}", target.getName());
            return;
        }
        // Crystal slot accepts either a stat crystal (legacy) or a typed gem.
        final boolean isGem = "gem".equals(crystal.getCategory());
        if (!isGem && !"crystal".equals(crystal.getCategory())) {
            log.warn("[Forge] Slot must hold a crystal or gem, got {}", crystal.getCategory());
            return;
        }
        if (crystal.getItemId() != p.getCrystalItemId()) {
            log.warn("[Forge] Crystal/gem slot mismatch: expected itemId {}, got {}",
                    p.getCrystalItemId(), crystal.getItemId());
            return;
        }
        // Essence must be category "essence". Universal essence (item 820) is
        // accepted for any target slot; typed essence must match the target slot.
        final boolean isUniversalEssence = essence.getItemId() == UNIVERSAL_ESSENCE_ITEM_ID;
        if (!"essence".equals(essence.getCategory())) {
            log.warn("[Forge] Item {} is not essence", essence.getName());
            return;
        }
        if (!isUniversalEssence && essence.getForgeSlotId() != target.getTargetSlot()) {
            log.warn("[Forge] Essence type {} does not match target slot {}",
                    essence.getForgeSlotId(), target.getTargetSlot());
            return;
        }
        // Cost check: typed essence requires a stack of 50; universal essence
        // is a single non-stackable item that substitutes for the whole 50.
        if (!isUniversalEssence && essence.getStackCount() < ENCHANT_ESSENCE_COST) {
            log.warn("[Forge] Player {} has insufficient essence: {} (need {})",
                    player.getId(), essence.getStackCount(), ENCHANT_ESSENCE_COST);
            return;
        }
        // Enchantment cap: rarity-driven. A COMMON item allows 1 gem,
        // MYTHICAL allows 6. Legacy items with more enchantments than their
        // (recently-introduced) rarity allows have already been migrated to
        // a higher rarity at load — see GameItem.fromGameItemRef.
        List<Enchantment> existing = target.getEnchantments();
        if (existing == null) existing = new ArrayList<>();
        final int rarityCap = target.getMaxEnchantments();
        if (existing.size() >= rarityCap) {
            log.warn("[Forge] Item {} at rarity cap ({} slots for {})", target.getName(),
                    rarityCap, Rarity.fromOrdinal(target.getRarity()).displayName);
            return;
        }
        // Pixel must not already be enchanted
        for (Enchantment e : existing) {
            if (e.getPixelX() == p.getPixelX() && e.getPixelY() == p.getPixelY()) {
                log.warn("[Forge] Pixel ({},{}) already enchanted on {}", p.getPixelX(), p.getPixelY(), target.getName());
                return;
            }
        }
        // Build the enchantment from either a stat crystal or a typed gem.
        final Enchantment newEnch;
        if (isGem) {
            final byte gemEffect = crystal.getGemEffectType();
            if (gemEffect < 0) {
                log.warn("[Forge] Gem {} has no gemEffectType", crystal.getName());
                return;
            }
            // For gems, pixelColor follows STAT_COLORS when the gem still
            // targets a stat, otherwise we use a neutral indicator color
            // keyed by effectType ordinal so the painted pixel stays
            // visually distinct.
            final byte gp1 = crystal.getGemParam1();
            final int color = (gemEffect == 0 || gemEffect == 1) && gp1 >= 0 && gp1 <= 7
                    ? STAT_COLORS[gp1]
                    : gemEffectColor(gemEffect);
            newEnch = new Enchantment(
                    /*statId*/ (byte) (gemEffect == 0 ? gp1 : 0),
                    /*deltaValue*/ (byte) (gemEffect == 0 ? Math.min(Byte.MAX_VALUE, crystal.getGemMagnitude()) : 0),
                    /*pixelX*/ p.getPixelX(),
                    /*pixelY*/ p.getPixelY(),
                    /*pixelColor*/ color,
                    gemEffect,
                    gp1,
                    crystal.getGemMagnitude(),
                    crystal.getGemDurationMs());
        } else {
            final byte statId = crystal.getForgeStatId();
            if (statId < 0 || statId > 7) {
                log.warn("[Forge] Crystal {} has invalid forgeStatId {}", crystal.getName(), statId);
                return;
            }
            // HP/MP get a +5 delta because their stat scale is much larger than the other six.
            final int color = STAT_COLORS[statId];
            final byte delta = (statId == 2 || statId == 3)
                    ? (byte) HP_MP_ENCHANT_DELTA
                    : (byte) DEFAULT_ENCHANT_DELTA;
            newEnch = new Enchantment(statId, delta, p.getPixelX(), p.getPixelY(), color);
        }

        // Deduct cost: remove crystal/gem entirely; universal essence is removed
        // entirely too, typed essence is decremented by 50 (or removed if exact).
        player.getInventory()[crystalSlot] = null;
        if (isUniversalEssence || essence.getStackCount() == ENCHANT_ESSENCE_COST) {
            player.getInventory()[essenceSlot] = null;
        } else {
            essence.setStackCount(essence.getStackCount() - ENCHANT_ESSENCE_COST);
        }

        existing.add(newEnch);
        target.setEnchantments(existing);

        log.info("[Forge] Player {} enchanted {} with effectType={} at pixel ({},{}) (now {}/{} slots)",
                player.getId(), target.getName(), newEnch.getEffectType(),
                p.getPixelX(), p.getPixelY(), existing.size(), rarityCap);

        sendInventoryUpdate(mgr, realm, player);
        // Persist immediately so a crash/disconnect doesn't lose the new enchantment
        mgr.persistPlayerAsync(player);
    }

    /** Visual fallback color for non-stat gem effects. */
    private static int gemEffectColor(byte effectType) {
        switch (effectType) {
        case 2: return 0xFFFFD700; // PROJECTILE_COUNT — gold
        case 3: return 0xFFFF4040; // PROJECTILE_DAMAGE — red
        case 4: return 0xFFA040FF; // ON_HIT_EFFECT — purple
        case 5: return 0xFF40FF80; // LIFESTEAL — green
        case 6: return 0xFFFFA000; // CRIT_CHANCE — orange
        default: return 0xFFFFFFFF;
        }
    }

    public static void handleForgeDisenchant(RealmManagerServer mgr, Packet packet) throws Exception {
        final ForgeDisenchantPacket p = (ForgeDisenchantPacket) packet;
        final Realm realm = mgr.findPlayerRealm(p.getPlayerId());
        if (realm == null) return;
        final Player player = realm.getPlayer(p.getPlayerId());
        if (player == null) return;

        final int targetSlot = p.getTargetItemSlot();
        if (targetSlot < 0 || targetSlot >= player.getInventory().length) return;
        final GameItem target = player.getInventory()[targetSlot];
        if (target == null) return;
        if (target.getEnchantments() == null || target.getEnchantments().isEmpty()) return;

        final int removed = target.getEnchantments().size();
        target.setEnchantments(new ArrayList<>());
        log.info("[Forge] Player {} disenchanted {} (removed {} enchantments)",
                player.getId(), target.getName(), removed);

        sendInventoryUpdate(mgr, realm, player);
        mgr.persistPlayerAsync(player);
    }

    private static void sendInventoryUpdate(RealmManagerServer mgr, Realm realm, Player player) throws Exception {
        final UpdatePacket update = realm.getPlayerAsPacket(player.getId());
        if (update != null) {
            mgr.enqueueServerPacket(player, update);
        }
    }

    /**
     * Find the interactionType of any tile at (tileX, tileY) by scanning all
     * map layers. Returns the first non-null interactionType found, or null.
     */
    private static String lookupTileInteraction(Realm realm, int tileX, int tileY) {
        try {
            if (realm.getTileManager() == null) return null;
            for (final TileMap layer : realm.getTileManager().getMapLayers()) {
                if (layer == null) continue;
                if (tileY < 0 || tileY >= layer.getHeight()) continue;
                if (tileX < 0 || tileX >= layer.getWidth()) continue;
                final Tile tile = layer.getBlocks()[tileY][tileX];
                if (tile == null || tile.isVoid()) continue;
                final TileModel def = GameDataManager.TILES.get((int) tile.getTileId());
                if (def == null) continue;
                if (def.getInteractionType() != null && !def.getInteractionType().isEmpty()) {
                    return def.getInteractionType();
                }
            }
        } catch (Exception e) {
            log.warn("[Forge] lookupTileInteraction error: {}", e.getMessage());
        }
        return null;
    }
}
