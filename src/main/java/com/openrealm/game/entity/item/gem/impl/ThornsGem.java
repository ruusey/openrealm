package com.openrealm.game.entity.item.gem.impl;

import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;

/**
 * Reflects a fraction of damage taken back at the attacker — whether that
 * attacker is an enemy (PvE) or another player (PvP). Lethal PvP reflects are
 * routed through playerDeath by the combat caller.
 */
public class ThornsGem implements Gemstone {

    public static final byte TYPE_ID = 6;
    private static final int REFLECT_PCT = 20;

    @Override public byte   typeId()       { return TYPE_ID; }
    @Override public String displayName()  { return "Thorns Gem"; }
    @Override public String description()  { return "Reflects " + REFLECT_PCT + "% of damage taken back to enemy attackers."; }
    @Override public int    paintColor()   { return 0xFF8FE08F; }

    @Override
    public boolean canSocketInto(GameItem item) {
        // Defensive gem — armor or weapon, not a ring/ability.
        if (item == null) return false;
        final byte slot = item.getTargetSlot();
        return slot == 0 || slot == 2;
    }

    @Override
    public void onPlayerHit(Player p, int damageTaken, Entity attacker, GameItem item) {
        if (damageTaken <= 0 || attacker == null) return;
        final int reflect = Math.max(1, (damageTaken * REFLECT_PCT) / 100);
        attacker.setHealth(attacker.getHealth() - reflect);
    }
}
