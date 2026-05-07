package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnimationSetModel {
    private List<AnimationFrameModel> frames;
    private List<Integer> durations;
    /** Set-level width override. Applies to every frame in this set
     *  unless that frame defines its own spriteWidth. 0 = inherit from
     *  AnimationModel.spriteSize. */
    private int spriteWidth;
    /** Set-level height override. */
    private int spriteHeight;
}
