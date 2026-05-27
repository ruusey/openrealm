package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.ShotContext;

public class MultishotGem implements Gemstone {

    public static final byte TYPE_ID = 3;
    // +1 extra → total = 1 (base) + 1 (extra) = 2 bullets, fanned symmetrically (no center bullet).
    // Earlier value of 2 produced left+CENTER+right, which doubled up with the unmodified shot.
    private static final int EXTRA = 1;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Multishot Gem"; }
    @Override public String description()  { return "Splits your basic attack into 2 spread projectiles."; }
    @Override public int    paintColor()   { return 0xFFFFD700; }

    @Override
    public boolean canSocketInto(GameItem item) {
        return item != null && item.getTargetSlot() == 0;
    }

    @Override
    public void modifyShot(ShotContext ctx, Player p, GameItem item) {
        ctx.setExtraProjectiles(ctx.getExtraProjectiles() + EXTRA);
    }
}
