package com.openrealm.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.Map;

import com.openrealm.game.data.GameDataManager;
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
public class CharacterStatsDto extends TemporalDto {
    private static final long serialVersionUID = -966774703891631356L;

    private String characterStatsId;
    private Long xp;
    private Integer classId;
    private Integer hp;
    private Integer mp;
    private Integer def;
    private Integer str;
    private Integer spd;
    private Integer dex;
    private Integer vit;
    private Integer wis;
    private Integer hpPotions;
    private Integer mpPotions;
    // Opaque cosmetic dye id — resolved client-side via dye-assets.json. The
    // client decides whether it's a solid color, a gradient, or a patterned
    // cloth, so adding new cosmetics is a data-only change. 0/null = no dye.
    private Integer dyeId;

    // Phase 2D — skill points. Earned 1 per 2 levels from L2-L20 (10 total).
    // {@code availableSkillPoints} is the unspent pool. {@code abilitySkillPoints}
    // maps abilityId → invested level (cap 5 for non-ults, cap 3 for ults).
    // Locked once spent. Persisted alongside the rest of the stat dto.
    private Integer availableSkillPoints;
    private Map<Integer, Integer> abilitySkillPoints;

    public static CharacterStatsDto characterDefaults(final Integer characterClass) {
        final CharacterClassModel model = GameDataManager.CHARACTER_CLASSES.get(characterClass);
        return CharacterStatsDto.builder().xp(0l).hp((int) model.getBaseStats().getHp())
                .mp((int) model.getBaseStats().getMp()).def((int) model.getBaseStats().getDef())
                .str((int) model.getBaseStats().getStr()).spd((int) model.getBaseStats().getSpd())
                .dex((int) model.getBaseStats().getDex()).vit((int) model.getBaseStats().getVit())
                .wis((int) model.getBaseStats().getWis()).build();
    }
}
