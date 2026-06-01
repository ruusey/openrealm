package com.openrealm.net.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Player;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Self-contained damage-over-time system for players inside PvP realms — poison
 * and bleed. The enemy DoT path (Realm.activeDots) is separate; this owns the
 * player side end to end: registration, per-tick damage with feedback text,
 * cleanup, and routing lethal ticks through {@link RealmManagerServer#playerDeath}.
 *
 * Magnitude is a percent of the victim's max HP per second (snapshotted at apply
 * time), so any source can lay a balanced DoT without carrying its own damage
 * number. Re-applying the same effect type refreshes the DoT rather than stacking.
 *
 * Everything runs on the server tick thread (apply happens during packet handling,
 * {@link #tick} during tickGlobal), so the plain per-realm lists need no locking.
 */
public final class PvpEffectsManager {

    private static final long TICK_INTERVAL_MS = 200L;
    private static final float POISON_PCT_PER_SEC = 1.5f;
    private static final float BLEED_PCT_PER_SEC = 2.0f;

    // realmId -> active player DoTs in that realm.
    private static final Map<Long, List<PvpDot>> DOTS_BY_REALM = new ConcurrentHashMap<>();

    /** Poison the target player; no-op outside a PvP realm. */
    public static void applyPoison(final RealmManagerServer mgr, final Realm realm,
            final Player target, final Player source, final long durationMs) {
        register(mgr, realm, target, source, StatusEffectType.POISONED, POISON_PCT_PER_SEC, durationMs);
    }

    /** Bleed the target player; no-op outside a PvP realm. */
    public static void applyBleed(final RealmManagerServer mgr, final Realm realm,
            final Player target, final Player source, final long durationMs) {
        register(mgr, realm, target, source, StatusEffectType.BLEEDING, BLEED_PCT_PER_SEC, durationMs);
    }

    /** Poison every opposing-team player in radius (Assassin poison vial in PvP). */
    public static void applyPoisonAoe(final RealmManagerServer mgr, final Realm realm,
            final long sourcePlayerId, final float x, final float y, final float radius,
            final long durationMs) {
        if (mgr == null || realm == null || !realm.isPvp()) return;
        final Player source = mgr.getPlayerById(sourcePlayerId);
        final int sourceTeam = (source != null) ? source.getPvpTeamId() : 0;
        final float radSq = radius * radius;
        for (final Player target : realm.getPlayers().values()) {
            if (target == null || target.getId() == sourcePlayerId) continue;
            if (sourceTeam != 0 && target.getPvpTeamId() == sourceTeam) continue;
            if (target.hasEffect(StatusEffectType.INVINCIBLE)) continue;
            final float dx = target.getPos().x - x;
            final float dy = target.getPos().y - y;
            if (dx * dx + dy * dy > radSq) continue;
            applyPoison(mgr, realm, target, source, durationMs);
        }
    }

    private static void register(final RealmManagerServer mgr, final Realm realm,
            final Player target, final Player source, final StatusEffectType status,
            final float pctPerSec, final long durationMs) {
        if (mgr == null || realm == null || !realm.isPvp() || target == null || durationMs <= 0) return;
        final int maxHp = (target.getComputedStats() != null)
                ? target.getComputedStats().getHp() : target.getHealth();
        final int total = Math.max(1, Math.round(maxHp * (pctPerSec / 100f) * (durationMs / 1000f)));

        final List<PvpDot> dots = DOTS_BY_REALM.computeIfAbsent(realm.getRealmId(), k -> new ArrayList<>());
        // Refresh rather than stack: drop any existing same-type DoT on this target.
        dots.removeIf(d -> d.targetPlayerId == target.getId() && d.status == status);
        dots.add(new PvpDot(target.getId(), source != null ? source.getId() : 0L, status, total, durationMs));

        // Applies the status (icon/visual) and broadcasts the effect name to nearby players.
        mgr.applyStatusWithFeedback(realm, target, EntityType.PLAYER, status, durationMs);
    }

    /** Ticks every active player DoT. Call once per server tick from tickGlobal(). */
    public static void tick(final RealmManagerServer mgr) {
        if (DOTS_BY_REALM.isEmpty()) return;
        final long now = Instant.now().toEpochMilli();

        final Iterator<Map.Entry<Long, List<PvpDot>>> realmIt = DOTS_BY_REALM.entrySet().iterator();
        while (realmIt.hasNext()) {
            final Map.Entry<Long, List<PvpDot>> entry = realmIt.next();
            final Realm realm = mgr.getRealms().get(entry.getKey());
            if (realm == null || !realm.isPvp()) { realmIt.remove(); continue; }

            final List<PvpDot> dots = entry.getValue();
            final Iterator<PvpDot> it = dots.iterator();
            while (it.hasNext()) {
                final PvpDot dot = it.next();
                final Player target = realm.getPlayer(dot.targetPlayerId);
                // Drop when the player left, died, was cleansed, or the DoT elapsed.
                if (target == null || target.getDeath() || !target.hasEffect(dot.status)
                        || dot.isExpired(now)) {
                    it.remove();
                    continue;
                }
                if (now - dot.lastTickMs < TICK_INTERVAL_MS) continue;
                dot.lastTickMs = now;

                final int tickDmg = dot.nextTickDamage(TICK_INTERVAL_MS);
                if (tickDmg <= 0) { it.remove(); continue; }

                target.setHealth(target.getHealth() - tickDmg);
                final TextEffect fx = target.hasEffect(StatusEffectType.ARMOR_BROKEN)
                        ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
                mgr.broadcastTextEffect(realm, EntityType.PLAYER, target, fx, "-" + tickDmg);
                mgr.invalidatePlayerStateCache(target.getId());

                if (target.getDeath()) {
                    mgr.playerDeath(realm, target);
                    it.remove();
                }
            }
            if (dots.isEmpty()) realmIt.remove();
        }
    }

    private PvpEffectsManager() {}
}
