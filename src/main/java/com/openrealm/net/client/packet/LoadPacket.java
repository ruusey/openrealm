package com.openrealm.net.client.packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.IOService;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.entity.NetBullet;
import com.openrealm.net.entity.NetEnemy;
import com.openrealm.net.realm.ShortIdAllocator;
import com.openrealm.net.entity.NetLootContainer;
import com.openrealm.net.entity.NetPlayer;
import com.openrealm.net.entity.NetPortal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Streamable
@AllArgsConstructor
@NoArgsConstructor
@PacketId(packetId = (byte)9)
public class LoadPacket extends Packet {
	@SerializableField(order = 0, type = NetPlayer.class, isCollection=true)
    private NetPlayer[] players;
	@SerializableField(order = 1, type = NetEnemy.class, isCollection=true)
    private NetEnemy[] enemies;
	@SerializableField(order = 2, type = NetBullet.class, isCollection=true)
    private NetBullet[] bullets;
	@SerializableField(order = 3, type = NetLootContainer.class, isCollection=true)
    private NetLootContainer[] containers;
	@SerializableField(order = 4, type = NetPortal.class, isCollection=true)
    private NetPortal[] portals;
	@SerializableField(order = 5, type = com.openrealm.net.core.nettypes.SerializableByte.class)
    private byte difficulty;

    public static LoadPacket from(Player[] players, LootContainer[] loot, Bullet[] bullets, Enemy[] enemies,
            Portal[] portals) throws Exception {
    	 LoadPacket load = null;
    	try {
            // Hand-rolled mapping for all entity types — replaces
            // IOService.mapModel(...) which used ModelMapper reflection.
            // ModelMapper walks 10-20 fields per entity reflectively per
            // call; with ~200 visible bullets + 11 players + 50 enemies +
            // 6 portals × 11 viewers × 32 Hz that was 90K+ reflective
            // mappings/sec — the dominant CPU sink during ability spam
            // and the cause of the TPS drop on a 2-vCPU instance. Direct
            // field copy is 10-100× faster.
            final NetPlayer[] mappedPlayers = new NetPlayer[players.length];
            for (int i = 0; i < players.length; i++) {
                mappedPlayers[i] = NetPlayer.fromPlayer(players[i]);
            }
            final NetEnemy[] mappedEnemies = new NetEnemy[enemies.length];
            for (int i = 0; i < enemies.length; i++) {
                mappedEnemies[i] = NetEnemy.fromEnemy(enemies[i]);
            }
            final NetBullet[] mappedBullets = new NetBullet[bullets.length];
            for (int i = 0; i < bullets.length; i++) {
                mappedBullets[i] = NetBullet.fromBullet(bullets[i]);
            }
            // Loot containers retain ModelMapper for now — they have nested
            // NetGameItem arrays whose mapping is more involved, and they're
            // low-volume (typically 0-5 per realm) so reflection cost is
            // negligible.
            final NetLootContainer[] mappedLoot = IOService.mapModel(loot, NetLootContainer[].class);
            final NetPortal[] mappedPortals = new NetPortal[portals.length];
            for (int i = 0; i < portals.length; i++) {
                mappedPortals[i] = NetPortal.fromPortal(portals[i]);
            }
            load = new LoadPacket(mappedPlayers, mappedEnemies, mappedBullets, mappedLoot, mappedPortals, (byte) 0);
    	}catch(Exception e) {
    		log.error("Failed to build load packet from mapped game data. Reason {}", e);
    	}
        return load;
    }

    /**
     * Build a LoadPacket with compact short IDs populated from the allocator.
     * Clients use these short IDs to resolve CompactMovePacket entries.
     */
    public static LoadPacket from(Player[] players, LootContainer[] loot, Bullet[] bullets, Enemy[] enemies,
            Portal[] portals, ShortIdAllocator allocator) throws Exception {
        LoadPacket load = from(players, loot, bullets, enemies, portals);
        if (load != null && allocator != null) {
            for (int i = 0; i < load.getPlayers().length; i++) {
                load.getPlayers()[i].setShortId(allocator.getOrAssign(load.getPlayers()[i].getId()));
            }
            for (int i = 0; i < load.getEnemies().length; i++) {
                load.getEnemies()[i].setShortId(allocator.getOrAssign(load.getEnemies()[i].getId()));
            }
        }
        return load;
    }

    public boolean equals(LoadPacket other) {
    	if(other == null) {
    		return false;
    	}
        // Visibility-set equality must be ORDER-INDEPENDENT. The spatial grid
        // stores cells as ConcurrentHashMap.newKeySet(), whose iteration order
        // is not stable — when ANY entity (most often a bullet) is added or
        // removed from a cell, the rebucketing can shuffle the iteration
        // order of OTHER entities in that cell. Comparing as Lists then
        // returned false even when the visible entity SETS were identical,
        // forcing a full LoadPacket on every tick (~140 kbit/s of redundant
        // player snapshots for 6 stationary test bots). Sets fix that.
        final java.util.Set<Long> playerIdsThis = Stream.of(this.players).map(NetPlayer::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> playerIdsOther = Stream.of(other.getPlayers()).map(NetPlayer::getId)
                .collect(Collectors.toSet());

        final java.util.Set<Long> lootIdsThis = Stream.of(this.containers).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> lootIdsOther = Stream.of(other.getContainers()).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());

        final java.util.Set<Long> enemyIdsThis = Stream.of(this.enemies).map(NetEnemy::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> enemyIdsOther = Stream.of(other.getEnemies()).map(NetEnemy::getId)
                .collect(Collectors.toSet());

        final java.util.Set<Long> bulletIdsThis = Stream.of(this.bullets).map(NetBullet::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> bulletIdsOther = Stream.of(other.getBullets()).map(NetBullet::getId)
                .collect(Collectors.toSet());

        final java.util.Set<Long> portalIdsThis = Stream.of(this.portals).map(NetPortal::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> portalIdsOther = Stream.of(other.getPortals()).map(NetPortal::getId)
                .collect(Collectors.toSet());

        boolean containersEq = lootIdsThis.equals(lootIdsOther);
        if (containersEq) {
            for (final NetLootContainer c : this.containers) {
                if (c.isContentsChanged()) {
                    containersEq = false;
                    break;
                }
            }
        }

        return (playerIdsThis.equals(playerIdsOther) && enemyIdsThis.equals(enemyIdsOther)
                && bulletIdsThis.equals(bulletIdsOther) && portalIdsThis.equals(portalIdsOther) && containersEq);

    }

    /**
     * Returns true if the visible *entity set* (players, enemies, portals)
     * is identical between this and other — IGNORING bullets and loot.
     *
     * This is the "is anything besides bullets/loot interesting" check that
     * lets the call site take a much cheaper path when only bullets cycled.
     * Without this split, every shot fired anywhere in the visible radius
     * forced a full per-player+enemy+portal snapshot to every nearby client
     * (~1.8 Mbit/s with 6 spam-shooting wizard bots).
     */
    public boolean entitySetEquals(LoadPacket other) {
        if (other == null) return false;
        final java.util.Set<Long> playerIdsThis = Stream.of(this.players).map(NetPlayer::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> playerIdsOther = Stream.of(other.getPlayers()).map(NetPlayer::getId)
                .collect(Collectors.toSet());
        if (!playerIdsThis.equals(playerIdsOther)) return false;
        final java.util.Set<Long> enemyIdsThis = Stream.of(this.enemies).map(NetEnemy::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> enemyIdsOther = Stream.of(other.getEnemies()).map(NetEnemy::getId)
                .collect(Collectors.toSet());
        if (!enemyIdsThis.equals(enemyIdsOther)) return false;
        final java.util.Set<Long> portalIdsThis = Stream.of(this.portals).map(NetPortal::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> portalIdsOther = Stream.of(other.getPortals()).map(NetPortal::getId)
                .collect(Collectors.toSet());
        if (!portalIdsThis.equals(portalIdsOther)) return false;
        // Loot ID set + contentsChanged: track loot churn here too so the fast
        // path still flushes loot updates without forcing a full snapshot.
        final java.util.Set<Long> lootIdsThis = Stream.of(this.containers).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> lootIdsOther = Stream.of(other.getContainers()).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());
        if (!lootIdsThis.equals(lootIdsOther)) return false;
        for (final NetLootContainer c : this.containers) {
            if (c.isContentsChanged()) return false;
        }
        return true;
    }

    /**
     * Build a minimal LoadPacket containing only bullet+loot deltas with
     * empty players/enemies/portals arrays. The client's handleLoad iterates
     * packet.players etc. and merges entries into existing state — it does
     * NOT delete entries missing from the packet (UnloadPacket handles
     * removal). So empty arrays are safe and skipped client-side.
     */
    public LoadPacket bulletAndLootDelta(final LoadPacket other) throws Exception {
        if (other == null) return this;
        final java.util.Set<Long> bulletIdsThis = Stream.of(this.bullets).map(NetBullet::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> lootIdsThis = Stream.of(this.containers).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());
        final List<NetBullet> bulletsDiff = new ArrayList<>();
        for (final NetBullet b : other.getBullets()) {
            if (!bulletIdsThis.contains(b.getId())) bulletsDiff.add(b);
        }
        final List<NetLootContainer> lootDiff = new ArrayList<>();
        for (final NetLootContainer p : other.getContainers()) {
            if (!lootIdsThis.contains(p.getLootContainerId()) || p.isContentsChanged()) lootDiff.add(p);
        }
        return new LoadPacket(new NetPlayer[0], new NetEnemy[0],
                bulletsDiff.toArray(new NetBullet[0]),
                lootDiff.toArray(new NetLootContainer[0]),
                new NetPortal[0], other.getDifficulty());
    }

    /**
     * Build an UnloadPacket containing only the bullets that despawned —
     * paired with bulletAndLootDelta() above. Players/enemies/portals/loot
     * are intentionally empty because the entity set is unchanged; the
     * full difference() path handles those when entitySetEquals == false.
     */
    public UnloadPacket bulletUnloadDifference(final LoadPacket other) throws Exception {
        if (other == null) return UnloadPacket.from(new Long[0], new Long[0],
                new Long[0], new Long[0], new Long[0]);
        final java.util.Set<Long> bulletIdsOther = Stream.of(other.getBullets()).map(NetBullet::getId)
                .collect(Collectors.toSet());
        final List<Long> bulletsDiff = new ArrayList<>();
        for (final NetBullet b : this.bullets) {
            if (!bulletIdsOther.contains(b.getId())) bulletsDiff.add(b.getId());
        }
        return UnloadPacket.from(new Long[0], bulletsDiff.toArray(new Long[0]),
                new Long[0], new Long[0], new Long[0]);
    }

    public LoadPacket combine(final LoadPacket other) throws Exception {
    	if(other==null) {
    		return this;
    	}
        // Players, enemies, and portals: always send the full set.
        // These are low-count entities and the delta was causing state desync
        // where the server believed the client had entities it never received
        // (the moment any LoadPacket failed to reach the client, those entities
        // were permanently invisible — they never reappeared in subsequent
        // diffs because the server's cached state already "had" them). Sending
        // the full snapshot every tick lets the client self-heal from any
        // dropped/corrupted packet within one tick.
        //
        // Bullets and loot containers DO use deltas: bullets are high-volume
        // and self-heal via natural expiration; loot containers re-sync via
        // the contentsChanged override.
        final List<Long> bulletIdsThis = Stream.of(this.bullets).map(NetBullet::getId).collect(Collectors.toList());
        final List<Long> lootIdsThis = Stream.of(this.containers).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toList());

        final List<NetBullet> bulletsDiff = new ArrayList<>();
        for (final NetBullet b : other.getBullets()) {
            if (!bulletIdsThis.contains(b.getId())) {
                bulletsDiff.add(b);
            }
        }

        final List<NetLootContainer> lootDiff = new ArrayList<>();
        for (final NetLootContainer p : other.getContainers()) {
            if (!lootIdsThis.contains(p.getLootContainerId()) || p.isContentsChanged()) {
                lootDiff.add(p);
            }
        }

        return new LoadPacket(other.getPlayers(), other.getEnemies(),
                bulletsDiff.toArray(new NetBullet[0]), lootDiff.toArray(new NetLootContainer[0]),
                other.getPortals(), other.getDifficulty());
    }

    /**
     * Like combine() but emits ONLY entities new to the client (delta) for
     * players/enemies/portals — instead of the full snapshot. This is the
     * default path for entity-set-change events (e.g. a bot walked into
     * another player's viewport). The full-snapshot self-heal is preserved
     * by the caller's periodic 3s refresh which routes through combine().
     *
     * Cuts slow-path bandwidth from ~6.6 KB (11-player snapshot) to typically
     * ~600 B (just the entity that crossed the boundary). With 11 viewers all
     * walking around, this saves the bulk of LoadPacket bandwidth.
     */
    public LoadPacket combineDelta(final LoadPacket other) throws Exception {
        if (other == null) return this;

        final java.util.Set<Long> playerIdsThis = Stream.of(this.players).map(NetPlayer::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> enemyIdsThis = Stream.of(this.enemies).map(NetEnemy::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> portalIdsThis = Stream.of(this.portals).map(NetPortal::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> bulletIdsThis = Stream.of(this.bullets).map(NetBullet::getId)
                .collect(Collectors.toSet());
        final java.util.Set<Long> lootIdsThis = Stream.of(this.containers).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toSet());

        final List<NetPlayer> playersDiff = new ArrayList<>();
        for (final NetPlayer p : other.getPlayers()) {
            if (!playerIdsThis.contains(p.getId())) playersDiff.add(p);
        }
        final List<NetEnemy> enemiesDiff = new ArrayList<>();
        for (final NetEnemy e : other.getEnemies()) {
            if (!enemyIdsThis.contains(e.getId())) enemiesDiff.add(e);
        }
        final List<NetPortal> portalsDiff = new ArrayList<>();
        for (final NetPortal p : other.getPortals()) {
            if (!portalIdsThis.contains(p.getId())) portalsDiff.add(p);
        }
        final List<NetBullet> bulletsDiff = new ArrayList<>();
        for (final NetBullet b : other.getBullets()) {
            if (!bulletIdsThis.contains(b.getId())) bulletsDiff.add(b);
        }
        final List<NetLootContainer> lootDiff = new ArrayList<>();
        for (final NetLootContainer p : other.getContainers()) {
            if (!lootIdsThis.contains(p.getLootContainerId()) || p.isContentsChanged()) lootDiff.add(p);
        }

        return new LoadPacket(
                playersDiff.toArray(new NetPlayer[0]),
                enemiesDiff.toArray(new NetEnemy[0]),
                bulletsDiff.toArray(new NetBullet[0]),
                lootDiff.toArray(new NetLootContainer[0]),
                portalsDiff.toArray(new NetPortal[0]),
                other.getDifficulty());
    }

    public UnloadPacket difference(LoadPacket other) throws Exception {
    	//if(other==null)
        final List<Long> playerIdsOther = Stream.of(other.getPlayers()).map(NetPlayer::getId).collect(Collectors.toList());
        final List<Long> lootIdsOther = Stream.of(other.getContainers()).map(NetLootContainer::getLootContainerId)
                .collect(Collectors.toList());
        final List<Long> bulletIdsOther = Stream.of(other.getBullets()).map(NetBullet::getId).collect(Collectors.toList());
        final List<Long> enemyIdsOther = Stream.of(other.getEnemies()).map(NetEnemy::getId).collect(Collectors.toList());
        final List<Long> portalIdsOther = Stream.of(other.getPortals()).map(NetPortal::getId).collect(Collectors.toList());

        final List<NetPlayer> players = Arrays.asList(this.getPlayers());
        final List<NetLootContainer> loot = Arrays.asList(this.getContainers());
        final List<NetBullet> bullets = Arrays.asList(this.getBullets());
        final List<NetEnemy> enemies = Arrays.asList(this.getEnemies());
        final List<NetPortal> portals = Arrays.asList(this.getPortals());

        // Unload bullets that the server removed (hit entity, hit wall, expired).
        // Dead reckoning is disabled so all nearby bullets are always sent — no
        // non-deterministic subset selection to cause flickering.
        final List<Long> bulletsDiff = new ArrayList<>();
        for (final NetBullet b : bullets) {
            if (!bulletIdsOther.contains(b.getId())) {
                bulletsDiff.add(b.getId());
            }
        }

        final List<Long> portalsDiff = new ArrayList<>();
        for (final NetPortal p : portals) {
            if (!portalIdsOther.contains(p.getId())) {
                portalsDiff.add(p.getId());
            }
        }

        final List<Long> playersDiff = new ArrayList<>();
        for (final NetPlayer p : players) {
            if (!playerIdsOther.contains(p.getId())) {
                playersDiff.add(p.getId());
            }
        }

        final List<Long> lootDiff = new ArrayList<>();
        for (final NetLootContainer p : loot) {
            if (!lootIdsOther.contains(p.getLootContainerId())) {
                lootDiff.add(p.getLootContainerId());
            }
        }

        final List<Long> enemyDiff = new ArrayList<>();
        for (final NetEnemy e : enemies) {
            if (!enemyIdsOther.contains(e.getId())) {
                enemyDiff.add(e.getId());
            }
        }

		return UnloadPacket.from(playersDiff.toArray(new Long[0]), bulletsDiff.toArray(new Long[0]),
				enemyDiff.toArray(new Long[0]), lootDiff.toArray(new Long[0]), portalsDiff.toArray(new Long[0]));
    }

    public boolean containsPlayer(final Long player) {
        for (final NetPlayer p : this.players) {
            if (p.getId() == player)
                return true;
        }

        return false;
    }

    public boolean containsEnemy(final Long enemy) {
        for (final NetEnemy e : this.enemies) {
            if (e.getId() == enemy)
                return true;
        }

        return false;
    }

    public boolean containsBullet(final Long bullet) {
        for (final NetBullet b : this.bullets) {
            if (b.getId() == bullet)
                return true;
        }

        return false;
    }

    public boolean containsLootContainer(final Long container) {
        for (final NetLootContainer lc : this.containers) {
            if (lc.getLootContainerId() == container)
                return true;
        }

        return false;
    }

    public boolean containsPortal(final Long portal) {
        for (final NetPortal p : this.portals) {
            if (p.getId() == portal)
                return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return (this.players == null || this.players.length == 0)
                && (this.enemies == null || this.enemies.length == 0)
                && (this.bullets == null || this.bullets.length == 0)
                && (this.containers == null || this.containers.length == 0)
                && (this.portals == null || this.portals.length == 0);
    }
}
