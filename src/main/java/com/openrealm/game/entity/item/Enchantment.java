package com.openrealm.game.entity.item;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Enchantment {
    public static final byte STAT_VIT = 0;
    public static final byte STAT_WIS = 1;
    public static final byte STAT_HP = 2;
    public static final byte STAT_MP = 3;
    public static final byte STAT_ATT = 4;
    public static final byte STAT_DEF = 5;
    public static final byte STAT_SPD = 6;
    public static final byte STAT_DEX = 7;

    // Visual/legacy stat-delta fields (also used by STAT_DELTA effect).
    private byte statId;
    private byte deltaValue;
    private byte pixelX;
    private byte pixelY;
    private int pixelColor;

    // Generalized gem-effect fields. effectType=0 (STAT_DELTA) reproduces
    // legacy behavior 1:1 — param1=statId, magnitude=deltaValue.
    @Builder.Default
    private byte effectType = 0;       // GemEffectType ordinal
    @Builder.Default
    private byte param1 = 0;           // statId or StatusEffectType id
    @Builder.Default
    private short magnitude = 0;       // delta or percent (×1)
    @Builder.Default
    private int durationMs = 0;        // for ON_HIT_EFFECT

    /**
     * Legacy 5-arg constructor preserved for existing call sites that build
     * stat-delta enchantments. Auto-fills the typed-effect fields so the
     * Enchantment behaves correctly under the new pipeline.
     */
    public Enchantment(byte statId, byte deltaValue, byte pixelX, byte pixelY, int pixelColor) {
        this.statId = statId;
        this.deltaValue = deltaValue;
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.pixelColor = pixelColor;
        this.effectType = 0; // STAT_DELTA
        this.param1 = statId;
        this.magnitude = deltaValue;
        this.durationMs = 0;
    }

    public Enchantment clone() {
        return new Enchantment(this.statId, this.deltaValue, this.pixelX, this.pixelY,
                this.pixelColor, this.effectType, this.param1, this.magnitude, this.durationMs);
    }
}
