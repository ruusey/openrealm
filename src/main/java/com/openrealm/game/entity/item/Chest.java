package com.openrealm.game.entity.item;

import java.util.UUID;

import com.openrealm.game.contants.LootTier;
import com.openrealm.game.math.Vector2f;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Chest extends LootContainer {
    public Chest(Vector2f pos) {
        super(LootTier.CHEST, pos);
        this.setUid(UUID.randomUUID().toString());
    }

    public Chest(Vector2f pos, GameItem loot) {
        super(LootTier.CHEST, pos, loot);
        this.setUid(UUID.randomUUID().toString());
    }

    public Chest(Vector2f pos, GameItem[] loot) {
        super(LootTier.CHEST, pos, loot);
        this.setUid(UUID.randomUUID().toString());
    }

    public Chest(LootContainer c) {
        super(LootTier.CHEST, c.getPos(), c.getItems());
        this.setLootContainerId(c.getLootContainerId());
        this.setContentsChanged(c.getContentsChanged());
    }

    public void addItemAtFirtIdx(GameItem item) {
        int idx = -1;
        for (int i = 0; i < this.getItems().length; i++) {
            if (super.getItems()[i] == null) {
                idx = i;
            }
        }
        if (idx > -1) {
            super.setItem(idx, item);
        }
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public String toString() {
        return (this.getLootContainerId() + " " + this.getPos() + " isChest=" + (this instanceof Chest));
    }

}
