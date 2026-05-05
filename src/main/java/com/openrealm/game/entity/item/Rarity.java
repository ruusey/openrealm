package com.openrealm.game.entity.item;

public enum Rarity {
    COMMON(1, "Common", 0xFFB8B8B8),
    UNCOMMON(2, "Uncommon", 0xFF4FC85A),
    RARE(3, "Rare", 0xFF3F8CFF),
    EPIC(4, "Epic", 0xFFB04FE0),
    LEGENDARY(5, "Legendary", 0xFFFF9418),
    MYTHICAL(6, "Mythical", 0xFFE83838);

    public final int gemSlots;
    public final String displayName;
    public final int color;

    Rarity(int gemSlots, String displayName, int color) {
        this.gemSlots = gemSlots;
        this.displayName = displayName;
        this.color = color;
    }

    public static Rarity fromOrdinal(int ord) {
        final Rarity[] vals = Rarity.values();
        if (ord < 0) return COMMON;
        if (ord >= vals.length) return vals[vals.length - 1];
        return vals[ord];
    }

    public static int slotsFor(int ord) {
        return fromOrdinal(ord).gemSlots;
    }
}
