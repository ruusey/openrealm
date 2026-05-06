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
    private int terrainId;
    private int dungeonId;
    private Map<String, int[][]> data;
    private DungeonGenerationParams dungeonParams;
    private List<StaticSpawn> staticSpawns;
    private List<float[]> spawnPoints; // [[x,y], [x,y], ...] — player spawn positions, picked randomly
    private List<PortalModel> staticPortals; // Permanent portals placed on the map
    private float difficulty; // Map-level difficulty for static maps (fallback when no terrain)

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
