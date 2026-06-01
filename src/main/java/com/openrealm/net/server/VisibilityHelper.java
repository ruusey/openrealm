package com.openrealm.net.server;

import com.openrealm.game.math.Vector2f;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileManager;
import com.openrealm.game.tile.TileMap;

/**
 * Line-of-sight across the collision layer's wall tiles ({@code TileData.isWall}).
 * Only true room walls block sight — collision objects (trees, rocks) and
 * decoration (flowers) do not. Used to cull targets/visibility behind walls:
 * AoE casts skip enemies a wall separates them from, and per-player LoadPacket
 * builds hide entities behind walls.
 *
 * The trace is a cheap grid DDA (Amanatides–Woo), and callers gate on
 * {@link TileManager#hasWalls()} so wall-less realms (the open overworld) never
 * run a single trace.
 */
public final class VisibilityHelper {

    // Backstop against a degenerate trace looping forever; far beyond any viewport span.
    private static final int MAX_STEPS = 4096;

    public static boolean hasLineOfSight(final TileManager tm, final Vector2f from, final Vector2f to) {
        if (from == null || to == null) return true;
        return hasLineOfSight(tm, from.x, from.y, to.x, to.y);
    }

    /** True when no wall tile lies strictly between the two world points. The
     *  endpoints' own tiles never occlude (an enemy standing in a doorway is
     *  still hittable; the eye's tile is never a wall). */
    public static boolean hasLineOfSight(final TileManager tm,
            final float fromX, final float fromY, final float toX, final float toY) {
        if (tm == null) return true;
        final TileMap coll = tm.getCollisionLayer();
        if (coll == null) return true;
        final int ts = coll.getTileSize();
        if (ts <= 0) return true;

        int cx = (int) (fromX / ts), cy = (int) (fromY / ts);
        final int ex = (int) (toX / ts), ey = (int) (toY / ts);
        if (cx == ex && cy == ey) return true;

        final float dirX = toX - fromX, dirY = toY - fromY;
        final int stepX = dirX > 0 ? 1 : (dirX < 0 ? -1 : 0);
        final int stepY = dirY > 0 ? 1 : (dirY < 0 ? -1 : 0);
        float tMaxX = stepX != 0 ? boundaryDist(fromX, dirX, cx, ts, stepX) : Float.POSITIVE_INFINITY;
        float tMaxY = stepY != 0 ? boundaryDist(fromY, dirY, cy, ts, stepY) : Float.POSITIVE_INFINITY;
        final float tDeltaX = stepX != 0 ? Math.abs(ts / dirX) : Float.POSITIVE_INFINITY;
        final float tDeltaY = stepY != 0 ? Math.abs(ts / dirY) : Float.POSITIVE_INFINITY;

        for (int i = 0; i < MAX_STEPS; i++) {
            if (tMaxX < tMaxY) { cx += stepX; tMaxX += tDeltaX; }
            else { cy += stepY; tMaxY += tDeltaY; }
            if (cx == ex && cy == ey) return true; // reached target — its tile never self-occludes
            if (isWall(coll, cx, cy)) return false;
        }
        return true;
    }

    /** Parametric distance from origin to the next grid boundary in the step direction. */
    private static float boundaryDist(final float origin, final float dir, final int cell,
            final int ts, final int step) {
        final float boundary = (step > 0) ? (cell + 1) * (float) ts : cell * (float) ts;
        return (boundary - origin) / dir;
    }

    private static boolean isWall(final TileMap coll, final int x, final int y) {
        if (!coll.isValidPosition(x, y)) return false;
        final Tile t = coll.getBlocks()[y][x];
        return t != null && t.getData() != null && t.getData().isWall();
    }

    private VisibilityHelper() {}
}
