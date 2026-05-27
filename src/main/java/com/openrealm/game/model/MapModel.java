package com.openrealm.game.model;

import java.util.List;
import java.util.Map;
import java.util.Random;

import com.openrealm.game.math.Vector2f;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
public class MapModel {
    private int mapId;
    private String mapName;
    private String mapKey;
    private int tileSize;
    private int width;
    private int height;
    // Default to -1 ("no terrain / no dungeon") so any map JSON that omits
    // these fields is treated as a static map by every consumer that checks
    // {@code terrainId < 0}. Without an explicit initializer the primitive
    // defaults to 0, which is Beach's real terrainId, causing static maps
    // to silently inherit Beach's random-spawn enemy groups.
    private int terrainId = -1;
    private int dungeonId = -1;
    private Map<String, int[][]> data;
    private DungeonGenerationParams dungeonParams;
    private List<StaticSpawn> staticSpawns;
    private List<float[]> spawnPoints; // [[x,y], [x,y], ...] — player spawn positions, picked randomly
    private List<PortalModel> staticPortals; // Permanent portals placed on the map
    private float difficulty; // Map-level difficulty for static maps (fallback when no terrain)
    private boolean isPvp;
    private List<float[]> teamASpawnPoints;
    private List<float[]> teamBSpawnPoints;
    /** PvP lane waypoints walked by team A (in order A-spawn → B-spawn).
     *  Each lane is List<float[]> of [x,y] in pixel coords; outer list = lanes. */
    private List<List<float[]>> pvpLanesTeamA;
    /** Team B walks the same lanes but in the reverse direction; populated explicitly
     *  in the map JSON so designers can place asymmetric waypoints per side if needed. */
    private List<List<float[]>> pvpLanesTeamB;
    /**
     * Phase 4 — dungeon/party scoping. How many parties may simultaneously
     * occupy this map instance.
     *   <=0  : unlimited / overworld behavior (any number of parties OK)
     *    1   : single-party dungeon (closes the entrance once one party owns it)
     *    >1  : reserved for shared-dungeon variants
     * Realm.dungeonPartyIds tracks which parties currently hold an instance;
     * the dungeon portal handler reads this cap to decide whether to accept
     * new entries or wave them off until the current occupants leave.
     */
    private int maxPartyCount;

    public Vector2f getCenter() {
        return new Vector2f((this.width / 2) * this.tileSize, ((this.height / 2) * (this.tileSize)));
    }

    /**
     * Returns a random spawn point if defined, otherwise the map center.
     */
    public Vector2f getRandomSpawnPoint() {
        if (this.spawnPoints != null && !this.spawnPoints.isEmpty()) {
            float[] sp = this.spawnPoints.get(new Random().nextInt(this.spawnPoints.size()));
            return new Vector2f(sp[0], sp[1]);
        }
        return getCenter();
    }
}
