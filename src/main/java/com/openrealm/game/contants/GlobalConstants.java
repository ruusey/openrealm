package com.openrealm.game.contants;

public class GlobalConstants {

    // Loot tier upgrade: in zones with difficulty > 1, each dropped item has a
    // (BASE + PER_DIFFICULTY * difficulty)% chance to be upgraded one tier.
    public static final float LOOT_TIER_UPGRADE_BASE_PERCENT = 0.0f;
    public static final float LOOT_TIER_UPGRADE_PER_DIFFICULTY = 5.0f;
    public static final float LOOT_TIER_UPGRADE_MIN_DIFFICULTY = 1.0f;

    // Soulbound loot system: players must deal at least this percentage of
    // total damage to an enemy to qualify for soulbound loot drops.
    // Set to 5% (0.05f) - easily adjustable.
    public static final float SOULBOUND_DAMAGE_THRESHOLD = 0.05f;

    // Bag tier thresholds for CYAN vs PURPLE:
    // Items at or above these tiers drop in CYAN bags, lower tiers in PURPLE.
    // Weapons: top 4 tiers (tiers 9-12) -> CYAN, else PURPLE
    // Abilities: top 2 tiers (tiers 11-12) -> CYAN, else PURPLE  
    // Armors: top 4 tiers (tiers 9-12) -> CYAN, else PURPLE
    public static final byte CYAN_BAG_MIN_WEAPON_TIER = 9;
    public static final byte CYAN_BAG_MIN_ABILITY_TIER = 11;
    public static final byte CYAN_BAG_MIN_ARMOR_TIER = 9;

    public static final int BASE_SIZE = 32;
    
    public static final int BASE_TILE_SIZE = 32;

    public static final int BASE_SPRITE_SIZE = 8;

    public static final int PLAYER_CAP = 40;

    public static final int TILE_SIZE_NORM = 64;

    public static final int MEDIUM_ART_SIZE = 16;

    public static final int LARGE_ART_SIZE = 32;

    public static final int PLAYER_SIZE = 28;

    public static final int PLAYER_RENDER_SIZE = 32;

    /**
     * Circle-collision radius factor used for projectile vs entity hit detection.
     * radius = sprite_size * HIT_RADIUS_FACTOR. With 0.4f, a 28px player has a
     * hit radius of 11.2 (vs 14 for the AABB) and an 8px bullet has a hit radius
     * of 3.2. This makes hit detection more forgiving and rotation-invariant.
     * Client mirror: BULLET_HIT_RADIUS_FACTOR in game.js — keep in sync.
     */
    public static final float HIT_RADIUS_FACTOR = 0.4f;

    // --- Difficulty-based enemy damage scaling ---
    // Applied inside processPlayerHit before player defense is subtracted.
    // Curve: flat 1.0 until the threshold, then +PER_LEVEL per difficulty level
    // until the knee, then +PER_LEVEL_AFTER_KNEE per level past the knee, hard
    // capped at CAP. Dungeon instances use a 1.0-lower threshold than overworld
    // zones so a difficulty-2.0 dungeon hits harder than the difficulty-2.0
    // grasslands overworld zone (dungeons feel harder than their zone number).
    public static final float DAMAGE_SCALE_MIN_DIFFICULTY = 2.0f;          // overworld: no scaling at/below this
    public static final float DAMAGE_SCALE_DUNGEON_MIN_DIFFICULTY = 1.0f;  // dungeon: no scaling at/below this
    public static final float DAMAGE_SCALE_PER_LEVEL = 0.10f;              // +10% per diff level above threshold
    public static final float DAMAGE_SCALE_KNEE_DIFFICULTY = 6.0f;         // slope halves past this difficulty
    public static final float DAMAGE_SCALE_PER_LEVEL_AFTER_KNEE = 0.05f;   // +5% per diff level past knee
    public static final float DAMAGE_SCALE_CAP = 2.0f;                     // hard cap on the multiplier

    public static final long SOCKET_READ_TIMEOUT = 15000;
}
