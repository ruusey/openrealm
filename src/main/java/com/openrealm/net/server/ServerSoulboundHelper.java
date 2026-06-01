package com.openrealm.net.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.net.realm.Realm;

/**
 * Per-enemy damage accounting used to gate soulbound loot to the players who
 * contributed a meaningful share of the kill. State lives on the {@link Realm}
 * (so it is collected with the realm); this helper is the stateless logic over it.
 */
public final class ServerSoulboundHelper {

    public static void trackDamage(final Realm realm, final long enemyId, final long playerId, final int damage) {
        realm.getDamageTracker()
                .computeIfAbsent(enemyId, k -> new ConcurrentHashMap<>())
                .merge(playerId, damage, Integer::sum);
    }

    /** Player IDs whose damage share meets SOULBOUND_DAMAGE_THRESHOLD on this enemy. */
    public static List<Long> getQualifyingPlayers(final Realm realm, final long enemyId) {
        final List<Long> qualifying = new ArrayList<>();
        final Map<Long, Integer> dmgMap = realm.getDamageTracker().get(enemyId);
        if (dmgMap == null || dmgMap.isEmpty()) return qualifying;
        int total = 0;
        for (final int d : dmgMap.values()) total += d;
        if (total == 0) return qualifying;
        for (final Map.Entry<Long, Integer> entry : dmgMap.entrySet()) {
            if ((float) entry.getValue() / total >= GlobalConstants.SOULBOUND_DAMAGE_THRESHOLD) {
                qualifying.add(entry.getKey());
            }
        }
        return qualifying;
    }

    public static void clearDamageTracking(final Realm realm, final long enemyId) {
        realm.getDamageTracker().remove(enemyId);
    }

    private ServerSoulboundHelper() {}
}
