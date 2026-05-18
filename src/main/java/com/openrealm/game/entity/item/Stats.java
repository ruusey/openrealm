package com.openrealm.game.entity.item;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stats {
    private int hp;
    private short mp;
    private short def;
    private short str;
    private short spd;
    private short dex;
    private short vit;
    private short wis;

    // Stat calculation chaining
    public Stats concat(Stats other) {
        if (other == null)
            return this;
        return Stats.builder().hp(this.hp + other.getHp()).mp((short) (this.mp + other.getMp()))
                .def((short) (this.def + other.getDef())).str((short) (this.str + other.getStr()))
                .spd((short) (this.spd + other.getSpd())).dex((short) (this.dex + other.getDex()))
                .vit((short) (this.vit + other.getVit())).wis((short) (this.wis + other.getWis())).build();
    }

    public Stats subtract(Stats other) {
        if (other == null)
            return this;
        return Stats.builder().hp(this.hp - other.getHp()).mp((short) (this.mp - other.getMp()))
                .def((short) (this.def - other.getDef())).str((short) (this.str - other.getStr()))
                .spd((short) (this.spd - other.getSpd())).dex((short) (this.dex - other.getDex()))
                .vit((short) (this.vit - other.getVit())).wis((short) (this.wis - other.getWis())).build();
    }

    @Override
    public Stats clone() {
        return Stats.builder().hp(this.hp).mp(this.mp).def(this.def)
                .str(this.str).spd(this.spd).dex(this.dex).vit(this.vit)
                .wis(this.wis).build();
    }

    public short getSpd() {
        return this.spd;
    }
}
