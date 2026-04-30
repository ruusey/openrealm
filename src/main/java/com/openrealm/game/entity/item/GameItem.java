package com.openrealm.game.entity.item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openrealm.account.dto.EnchantmentDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.model.SpriteModel;
import com.openrealm.net.entity.NetGameItemRef;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@AllArgsConstructor
@Builder
@Slf4j
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameItem extends SpriteModel {
    private int itemId;
    @Builder.Default
    private String uid = UUID.randomUUID().toString();
    private String name;
    private String description;
    private Stats stats;
    private Damage damage;
    private Effect effect;
    private List<Effect> selfEffects;
    private boolean consumable;
    private byte tier;
    private byte targetSlot;
    private byte targetClass;
    private byte fameBonus;
    // Stackable items (shards, essence) merge in inventory up to maxStack
    @Builder.Default
    private boolean stackable = false;
    @Builder.Default
    private int maxStack = 1;
    // Item category — used by forge & stacking. "generic", "shard", "crystal", "essence"
    @Builder.Default
    private String category = "generic";
    // For shards/crystals: 0=VIT 1=WIS 2=HP 3=MP 4=ATT 5=DEF 6=SPD 7=DEX. -1 if N/A.
    @Builder.Default
    private byte forgeStatId = -1;
    // For essence: 0=weapon 1=ability 2=armor 3=ring. -1 if N/A.
    @Builder.Default
    private byte forgeSlotId = -1;
    // Per-instance: current quantity in this stack. 1 for non-stackable items.
    @Builder.Default
    private int stackCount = 1;
    // Per-instance: applied enchantments (max 5)
    @Builder.Default
    private List<Enchantment> enchantments = new java.util.ArrayList<>();

    public GameItem() {
        this.uid = UUID.randomUUID().toString();
        this.stackable = false;
        this.maxStack = 1;
        this.category = "generic";
        this.forgeStatId = -1;
        this.forgeSlotId = -1;
        this.stackCount = 1;
        this.enchantments = new java.util.ArrayList<>();
    }

    @Override
    public GameItem clone() {
        GameItem.GameItemBuilder builder = GameItem.builder().itemId(this.itemId).uid(this.uid).name(this.name)
                .description(this.description).consumable(this.consumable).tier(this.tier).targetSlot(this.targetSlot)
                .targetClass(this.targetClass).fameBonus(this.fameBonus)
                .stackable(this.stackable).maxStack(this.maxStack).category(this.category)
                .forgeStatId(this.forgeStatId).forgeSlotId(this.forgeSlotId).stackCount(this.stackCount);

        if (this.damage != null) {
            builder = builder.damage(this.damage.clone());
        }

        if (this.stats != null) {
            builder = builder.stats(this.stats.clone());
        }

        if (this.enchantments != null && !this.enchantments.isEmpty()) {
            final java.util.List<Enchantment> copy = new java.util.ArrayList<>(this.enchantments.size());
            for (Enchantment e : this.enchantments) {
                copy.add(e == null ? null : e.clone());
            }
            builder = builder.enchantments(copy);
        }

        GameItem itemFinal = builder.build();
        itemFinal.setAngleOffset(this.getAngleOffset());
        itemFinal.setRow(this.getRow());
        itemFinal.setCol(this.getCol());
        itemFinal.setSpriteKey(this.getSpriteKey());

        return itemFinal;
    }

    public void applySpriteModel(final SpriteModel model) {
        this.setRow(model.getRow());
        this.setCol(model.getCol());
        this.setAngleOffset(model.getAngleOffset());
        this.setSpriteKey(model.getSpriteKey());
    }
    
    public GameItemRefDto toGameItemRefDto(int idx) {
        final List<EnchantmentDto> enchDtos;
        if (this.enchantments != null && !this.enchantments.isEmpty()) {
            enchDtos = new ArrayList<>(this.enchantments.size());
            for (Enchantment e : this.enchantments) {
                enchDtos.add(new EnchantmentDto(e.getStatId(), e.getDeltaValue(), e.getPixelX(), e.getPixelY(), e.getPixelColor()));
            }
        } else {
            enchDtos = null;
        }
        return GameItemRefDto.builder().itemId(this.itemId).slotIdx(idx).itemUuid(this.uid)
                .stackCount(this.stackCount).enchantments(enchDtos).build();
    }

    public NetGameItemRef asNetGameItemRef(int idx) {
    	return new NetGameItemRef(itemId, idx, this.uid);
    }

    public static GameItem fromGameItemRef(final GameItemRefDto gameItem) {
        final GameItem template = GameDataManager.GAME_ITEMS.get(gameItem.getItemId());
        if (template == null) return null;
        // Clone the template so per-instance state (uid, stackCount, enchantments)
        // does not leak between players sharing the same item definition.
        final GameItem item = template.clone();
        item.setUid(gameItem.getItemUuid());
        if (gameItem.getStackCount() != null) {
            item.setStackCount(gameItem.getStackCount());
        }
        if (gameItem.getEnchantments() != null && !gameItem.getEnchantments().isEmpty()) {
            final List<Enchantment> loaded = new ArrayList<>(gameItem.getEnchantments().size());
            for (EnchantmentDto e : gameItem.getEnchantments()) {
                loaded.add(new Enchantment(
                        e.getStatId() == null ? 0 : e.getStatId(),
                        e.getDeltaValue() == null ? 0 : e.getDeltaValue(),
                        e.getPixelX() == null ? 0 : e.getPixelX(),
                        e.getPixelY() == null ? 0 : e.getPixelY(),
                        e.getPixelColor() == null ? 0 : e.getPixelColor()));
            }
            item.setEnchantments(loaded);
        } else {
            item.setEnchantments(new ArrayList<>());
        }
        return item;
    }

    public static GameItem fromGameItemRef(final NetGameItemRef gameItem) {
        final GameItem template = GameDataManager.GAME_ITEMS.get(gameItem.getItemId());
        if (template == null) return null;
        final GameItem item = template.clone();
        item.setUid(gameItem.getItemUuid());
        return item;
    }
    
}
