package com.openrealm.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnimationFrameModel {
    private int row;
    private int col;
    /** Per-frame width override in source pixels. 0 = inherit from
     *  AnimationSetModel.spriteWidth, then AnimationModel.spriteSize.
     *  Server doesn't render but keeps the field so JSON round-trips
     *  through any read/write paths preserve the override. */
    private int spriteWidth;
    /** Per-frame height override. Same fallback chain as spriteWidth. */
    private int spriteHeight;
}
