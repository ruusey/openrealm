package com.openrealm.net.client.packet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;
import com.openrealm.net.entity.NetTile;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Slf4j
@Streamable
@AllArgsConstructor
@PacketId(packetId = (byte)8)
public class LoadMapPacket extends Packet {
	@SerializableField(order = 0, type = SerializableLong.class)
    private long realmId;
	@SerializableField(order = 1, type = SerializableShort.class)
    private short mapId;
	@SerializableField(order = 2, type = SerializableShort.class)
    private short mapWidth;
	@SerializableField(order = 3, type = SerializableShort.class)
    private short mapHeight;
	@SerializableField(order = 4, type = NetTile.class, isCollection=true)
    private NetTile[] tiles;

    public LoadMapPacket() {

    }

    public static LoadMapPacket from(long realmId, short mapId, short mapWidth, short mapHeight, List<NetTile> tiles) throws Exception {
    	return from(realmId, mapId, mapWidth, mapHeight, tiles.toArray(new NetTile[0]));
    }

    public static LoadMapPacket from(long realmId, short mapId, short mapWidth, short mapHeight,  NetTile[] tiles) throws Exception {
    	return new LoadMapPacket(realmId, mapId, mapWidth, mapHeight, tiles);
    }

    /**
     * Pack a NetTile's identifying fields (tileId, layer, x, y) into a single
     * long for O(1) Set lookup. Avoids the per-comparison reflection / equals
     * overhead and lets diff() / equals() run in O(N) instead of O(N²).
     * Layout: [tileId 16 | layer 8 | x 20 | y 20] — 64 bits total.
     * Sign-extension safe for tileId (short) and layer (byte).
     */
    private static long packTileKey(NetTile t) {
        return ((long) (t.getTileId() & 0xFFFF) << 48)
             | ((long) (t.getLayer() & 0xFF) << 40)
             | ((long) (t.getXIndex() & 0xFFFFF) << 20)
             |  ((long) (t.getYIndex() & 0xFFFFF));
    }

    public LoadMapPacket difference(LoadMapPacket other) throws Exception {
        // If the player is changing realms, force the new tiles to be sent
        if (this.realmId != other.getRealmId())
            return other;
        // Build a hash set of THIS packet's tile keys ONCE, then check each
        // tile in `other` in O(1). Was O(N²): with 40 viewers × ~628 tiles
        // per viewport at 4 Hz LoadMap rate, the old linear scan was costing
        // ~63 M comparisons/sec — the dominant CPU sink in 40-player
        // scenarios on a 2-vCPU box (TPS dropped to 7).
        final NetTile[] myTiles = this.getTiles();
        final Set<Long> myKeys = new HashSet<>(myTiles.length * 2);
        for (final NetTile t : myTiles) myKeys.add(packTileKey(t));

        final List<NetTile> diff = new ArrayList<>();
        for (final NetTile tileOther : other.getTiles()) {
            if (!myKeys.contains(packTileKey(tileOther))) {
                diff.add(tileOther);
            }
        }
        if (diff.isEmpty())
            return null;
        return LoadMapPacket.from(other.getRealmId(), other.getMapId(),
                other.getMapWidth(), other.getMapHeight(), diff);
    }

    public boolean equals(LoadMapPacket other) {
        if (other == null) return false;
        if (this.realmId != other.getRealmId()) return false;
        if (this.mapId != other.getMapId()) return false;
        if (this.mapHeight != other.getMapHeight() || this.mapWidth != other.getMapWidth()) return false;

        final NetTile[] myTiles = this.getTiles();
        final NetTile[] otherTiles = other.getTiles();
        if (myTiles.length != otherTiles.length) return false;

        // Set-based content equality. Order doesn't matter — getLoadMapTiles
        // re-iterates the player viewport every call so even an unchanged
        // player position can produce arrays in slightly different order if
        // anything in the realm shifts. Set comparison correctly returns
        // true for "same tiles, any order".
        final Set<Long> myKeys = new HashSet<>(myTiles.length * 2);
        for (final NetTile t : myTiles) myKeys.add(packTileKey(t));
        for (final NetTile t : otherTiles) {
            if (!myKeys.contains(packTileKey(t))) return false;
        }
        return true;
    }

    /** Kept for backward-compatibility callers; new code should use the
     *  Set-based {@link #difference(LoadMapPacket)} which is O(N) total. */
    public static boolean tilesContains(NetTile tile, NetTile[] array) {
        for (NetTile netTile : array) {
            if (tile.equals(netTile))
                return true;
        }
        return false;
    }
}
