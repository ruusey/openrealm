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
public class AttributeModifier {
    private byte statId;       // 0..7 (VIT, WIS, HP, MP, ATT, DEF, SPD, DEX)
    private byte deltaValue;   // signed: −3..+3 typical, scaled by rarity

    public AttributeModifier clone() {
        return new AttributeModifier(this.statId, this.deltaValue);
    }
}
