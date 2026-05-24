package com.openrealm.net.server;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

public final class ServerPassiveTickHelper {

    private static final int GUIDING_LIGHT_WIS_DIVISOR = 5;

    private static final float AURA_RADIUS    = 320f;
    private static final float AURA_RADIUS_SQ = AURA_RADIUS * AURA_RADIUS;

    private static final float NECRO_AURA_R    = AURA_RADIUS / 3f;
    private static final float NECRO_AURA_SQ   = NECRO_AURA_R * NECRO_AURA_R;

    private static final long REFRESH_MS = 800L;

    /** Called every server tick; internally gates to every 8 ticks. */
    public static void tick(final RealmManagerServer mgr, final long tickCounter) {
        if (tickCounter % 8 != 0) return;
        for (final Realm realm : mgr.getRealms().values()) {
            if (realm.getPlayers().isEmpty()) continue;
            for (final Player p : realm.getPlayers().values()) {
                final PassiveAbility pa = p.getClassPassive();
                if (pa == null) continue;
                final int pid = pa.getId();
                if (pid == 11003) {
                    refreshProtectiveAura(realm, p);
                } else if (pid == 11006) {
                    refreshHolyResolve(p);
                } else if (pid == 11015 || pid == 12006) {
                    refreshGuidingLight(mgr, realm, p);
                } else if (pid == 11008) {
                    refreshNecroticAura(realm, p);
                }
            }
        }
    }

    private static void refreshProtectiveAura(final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        for (final Player ally : realm.getPlayers().values()) {
            final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
            final float dx = ac.x - pc.x, dy = ac.y - pc.y;
            if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
            ally.addEffect(StatusEffectType.PROTECTED, REFRESH_MS);
        }
    }

    private static void refreshHolyResolve(final Player p) {
        final int maxHp = p.getComputedStats() != null ? p.getComputedStats().getHp() : p.getHealth();
        if (maxHp > 0 && p.getHealth() * 2 < maxHp) {
            p.addEffect(StatusEffectType.BRACED, REFRESH_MS);
        }
    }

    private static void refreshGuidingLight(final RealmManagerServer mgr, final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        final short bonus = (short) Math.max(0,
                p.getComputedStats().getWis() / GUIDING_LIGHT_WIS_DIVISOR);
        for (final Player ally : realm.getPlayers().values()) {
            final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
            final float dx = ac.x - pc.x, dy = ac.y - pc.y;
            if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
            // Only show floating text on first application, not every 125ms refresh.
            if (bonus > 0 && !ally.hasEffect(StatusEffectType.EMPOWERED_STR)) {
                mgr.broadcastTextEffect(realm, EntityType.PLAYER, ally,
                        TextEffect.HEAL, "+" + bonus + " STR");
                mgr.broadcastTextEffect(realm, EntityType.PLAYER, ally,
                        TextEffect.HEAL, "+" + bonus + " DEX");
            }
            ally.addEffect(StatusEffectType.EMPOWERED_STR, REFRESH_MS, bonus);
            ally.addEffect(StatusEffectType.EMPOWERED_DEX, REFRESH_MS, bonus);
        }
    }

    private static void refreshNecroticAura(final Realm realm, final Player p) {
        final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
        for (final Enemy enemy : realm.getEnemies().values()) {
            if (enemy == null || enemy.getDeath()) continue;
            if (enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            final float dx = enemy.getPos().x - pc.x;
            final float dy = enemy.getPos().y - pc.y;
            if (dx * dx + dy * dy > NECRO_AURA_SQ) continue;
            enemy.addEffect(StatusEffectType.CURSED, REFRESH_MS);
        }
    }

    private ServerPassiveTickHelper() {}
}
