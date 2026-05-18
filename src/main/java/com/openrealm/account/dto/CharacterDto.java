package com.openrealm.account.dto;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.model.CharacterClassModel;

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
public class CharacterDto extends TemporalDto {
    private static final long serialVersionUID = -8940547643757956271L;

    private String characterId;
    private String characterUuid;
    private Integer characterClass;
    private CharacterStatsDto stats;
    private Set<GameItemRefDto> items;
    
    
    public int numStatsMaxed() {
        int count = 0;
        for (int i = 0; i < 8; i++) {
            if (this.isStatMaxed(i)) {
                count++;
            }
        }
        return count;
    }

    public boolean isStatMaxed(int statIdx) {
        CharacterClassModel characterClass = GameDataManager.CHARACTER_CLASSES.get(this.characterClass);
        Stats maxStats = characterClass.getMaxStats();
        boolean maxed = false;
        switch (statIdx) {
        case 0:
            maxed = this.stats.getHp() >= maxStats.getHp();
            break;
        case 1:
            maxed = this.stats.getMp() >= maxStats.getMp();
            break;
        case 2:
            maxed = this.stats.getDef() >= maxStats.getDef();
            break;
        case 3:
            maxed = this.stats.getStr() >= maxStats.getStr();
            break;
        case 4:
            maxed = this.stats.getSpd() >= maxStats.getSpd();
            break;
        case 5:
            maxed = this.stats.getDex() >= maxStats.getDex();
            break;
        case 6:
            maxed = this.stats.getVit() >= maxStats.getVit();
            break;
        case 7:
            maxed = this.stats.getWis() >= maxStats.getWis();
            break;
        }
        return maxed;
    }
}
