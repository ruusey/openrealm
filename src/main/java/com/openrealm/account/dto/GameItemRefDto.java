package com.openrealm.account.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.openrealm.net.entity.NetGameItemRef;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = false)
public class GameItemRefDto extends TemporalDto {
    private static final long serialVersionUID = -5119762736198793613L;

    private Integer itemId;
    private Integer slotIdx;
    private String itemUuid;
    private Integer stackCount;
    private List<EnchantmentDto> enchantments;
    // 0..5; null on legacy records (defaults to COMMON, with rarity migration
    // bumping items that already carry multiple enchantments).
    private Byte rarity;
    private List<AttributeModifierDto> attributeModifiers;


    public NetGameItemRef asNetGameItemRef() {
    	return new NetGameItemRef(itemId, slotIdx, itemUuid);
    }

}
