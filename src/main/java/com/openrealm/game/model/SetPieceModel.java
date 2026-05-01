package com.openrealm.game.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reusable setpiece template loaded from setpieces.json.
 *
 * Mirrors the {@link MapModel} layer structure: layers live under
 * {@code data} keyed by string indices ("0" = base, "1" = collision/decoration).
 * Tile ID 0 means "don't overwrite the underlying terrain".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetPieceModel {
    private int setPieceId;
    private String name;
    private int width;
    private int height;
    /** Named tile layers: "0" = base, "1" = collision/decoration. */
    private Map<String, int[][]> data = new LinkedHashMap<>();

    @JsonIgnore
    public int[][] getLayer(String key) {
        return this.data == null ? null : this.data.get(key);
    }

    @JsonIgnore
    public int[][] getBaseLayer() {
        return getLayer("0");
    }

    @JsonIgnore
    public int[][] getCollisionLayer() {
        return getLayer("1");
    }
}
