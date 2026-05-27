package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityEffect;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralizes the PvP-specific ability-effect decisions invoked from
 * {@link ServerAbilityHelper}. The PvE call sites need exactly three things:
 *  1) per-target filters that skip friendly-fire on enemies / non-allies on heals,
 *  2) ability-damage scaling (×0.1) so PvP duels last long enough to feel tactical,
 *  3) routing AoE damaging/debuff effects onto opposing PLAYERS (the PvE flow only
 *     iterates enemies, so a Wizard ult cast on a player target hits nobody otherwise).
 * Keeping these here means PvE ServerAbilityHelper just calls into this class once
 * per choke point — the team/lane logic stays in the Pvp* namespace.
 */
@Slf4j
public final class PvpAbilityHandler {

    /** Mirrors {@code ServerCombatHelper.PVP_DAMAGE_SCALE} — abilities scale identically. */
    public static final float PVP_ABILITY_DAMAGE_SCALE = 0.1f;

    private PvpAbilityHandler() {}

    /** PvE realms always return false. In PvP, true iff source and target enemy share teamId
     *  (friendly-fire skip — own-side minions never take ability damage from teammates). */
    public static boolean shouldSkipAoeEnemy(final Realm realm, final Player source, final Enemy enemy) {
        if (realm == null || !realm.isPvp()) return false;
        if (source == null || enemy == null) return false;
        return enemy.getTeamId() != 0 && source.getPvpTeamId() == enemy.getTeamId();
    }

    /** PvE realms always return false. In PvP, true iff source and ally are on DIFFERENT teams
     *  — heals/buffs should never reach opposing players. */
    public static boolean shouldSkipAoeAlly(final Realm realm, final Player source, final Player ally) {
        if (realm == null || !realm.isPvp()) return false;
        if (source == null || ally == null) return false;
        if (ally.getId() == source.getId()) return false; // self always counts as ally
        return source.getPvpTeamId() != ally.getPvpTeamId();
    }

    /**
     * Filter a candidate list of players down to those that should receive an ally-targeted
     * effect (heal/buff/passive) from {@code source}. PvE realms pass everyone through; PvP
     * realms keep self + same-team players only.
     *
     * Item scripts (Priest Tome, Holy Protection, Berserk, Seal of Blasphemous Prayer, etc.)
     * call this around their {@code realm.getPlayersInBounds(...)} iteration so a single PvP
     * decision controls who gets buffed — no per-script PvP branching.
     */
    public static Player[] filterAllies(final Realm realm, final Player source, final Player[] candidates) {
        if (candidates == null || candidates.length == 0) return candidates;
        if (realm == null || !realm.isPvp() || source == null) return candidates;
        int kept = 0;
        final Player[] tmp = new Player[candidates.length];
        for (final Player p : candidates) {
            if (p == null) continue;
            if (p.getId() == source.getId()) { tmp[kept++] = p; continue; }
            if (p.getPvpTeamId() != 0 && p.getPvpTeamId() == source.getPvpTeamId()) {
                tmp[kept++] = p;
            }
        }
        if (kept == candidates.length) return candidates;
        final Player[] out = new Player[kept];
        System.arraycopy(tmp, 0, out, 0, kept);
        return out;
    }

    /** Applies a damaging/debuffing AoE to opposing-team players in radius. No-op outside PvP.
     *  Mirrors the relevant subset of {@code aoeTargeted} but targeted at players instead of enemies. */
    public static void applyAoeToOpposingPlayers(final RealmManagerServer mgr, final Realm realm,
            final Player source, final Vector2f center, final float radius,
            final short rawDamage, final Ability ab) {
        if (realm == null || !realm.isPvp() || source == null || ab == null) return;
        final short scaledDamage = (short) Math.max(0, Math.round(rawDamage * PVP_ABILITY_DAMAGE_SCALE));
        final float radSq = radius * radius;
        final List<StatusEffectType> debuffs = collectEnemyHitStatuses(ab);
        final int durationMs = collectDebuffDuration(ab);
        final List<Player> killed = new ArrayList<>();
        for (final Player target : realm.getPlayers().values()) {
            if (target == null || target.getId() == source.getId()) continue;
            if (target.getPvpTeamId() == 0 || target.getPvpTeamId() == source.getPvpTeamId()) continue;
            if (target.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            final Vector2f tc = target.getPos().clone(target.getSize() / 2, target.getSize() / 2);
            final float dx = tc.x - center.x;
            final float dy = tc.y - center.y;
            if (dx * dx + dy * dy > radSq) continue;
            if (scaledDamage > 0) {
                target.setHealth(target.getHealth() - scaledDamage);
                mgr.sendTextEffectToPlayer(target, TextEffect.DAMAGE, "-" + scaledDamage);
            }
            for (final StatusEffectType st : debuffs) {
                mgr.applyStatusWithFeedback(realm, target, EntityType.PLAYER, st, durationMs);
            }
            mgr.invalidatePlayerStateCache(target.getId());
            if (target.getDeath()) killed.add(target);
        }
        for (final Player k : killed) {
            mgr.playerDeath(realm, k); // PvP short-circuit lives in playerDeath; routes to PvpMatchManager.
        }
    }

    /** Returns the scaled (×0.1) value for AoE ability damage applied directly to enemies in PvP.
     *  PvE call sites pass the raw value through unchanged. */
    public static short scaleAbilityDamage(final Realm realm, final short rawDamage) {
        if (realm == null || !realm.isPvp()) return rawDamage;
        return (short) Math.max(0, Math.round(rawDamage * PVP_ABILITY_DAMAGE_SCALE));
    }

    private static List<StatusEffectType> collectEnemyHitStatuses(final Ability ab) {
        final List<StatusEffectType> out = new ArrayList<>();
        for (final AbilityEffect e : ab.effectList()) {
            if (!"STATUS_APPLY".equalsIgnoreCase(e.getType())) continue;
            if (!"ENEMIES_HIT".equalsIgnoreCase(e.getTarget())) continue;
            try {
                final StatusEffectType st = StatusEffectType.map.get(
                        Short.parseShort(String.valueOf(e.getStatusId()).trim()));
                if (st != null) out.add(st);
            } catch (NumberFormatException ignore) { }
        }
        return out;
    }

    private static int collectDebuffDuration(final Ability ab) {
        for (final AbilityEffect e : ab.effectList()) {
            if ("STATUS_APPLY".equalsIgnoreCase(e.getType())
                    && "ENEMIES_HIT".equalsIgnoreCase(e.getTarget())
                    && e.getBaseDurationMs() > 0) {
                return (int) Math.min(Integer.MAX_VALUE, e.getBaseDurationMs());
            }
        }
        return 0;
    }

    // Suppress unused-import warning when only some of the fields/types above
    // are exercised by a given build profile.
    @SuppressWarnings("unused")
    private static final Stats UNUSED_STATS_REF = null;
}
