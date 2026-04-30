package com.openrealm.game.tile;

import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.TileModel;

import lombok.Data;

@Data
public class TileMap {
    private short mapId;
    private Tile[][] tiles;
    private int tileSize;
    private int width;
    private int height;

    public TileMap(int tileSize, int width, int height) {
        this.tileSize = tileSize;
        this.width = width;
        this.height = height;
        this.tiles = new Tile[height][width];
    }

    public TileMap(short mapId, int tileSize, int width, int height) {
        this.mapId = mapId;
        this.tileSize = tileSize;
        this.width = width;
        this.height = height;
        this.tiles = new Tile[height][width];
    }

    public TileMap(short mapId, Tile[][] blocks, int tileSize, int width, int height) {
        this.mapId = mapId;
        this.tiles = blocks;
        this.tileSize = tileSize;
        this.width = width;
        this.height = height;
    }

    public Tile[][] getBlocks() {
        return this.tiles;
    }
    
    public void fill(int tileId) {
        final TileModel model = GameDataManager.TILES.get(tileId);
        final short tid = (short) model.getTileId();
        final TileData data = model.getData();
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                this.tiles[i][j] = new Tile(tid, (short) i, (short) j, data, (short) this.tileSize);
            }
        }
    }

    public void setTileAt(int row, int col, short tileId, TileData data) {
        this.tiles[row][col] = new Tile(tileId, (short) row, (short) col, data, (short) this.tileSize);
    }

    public void setTileAt(int row, int col, TileModel model) {
        this.tiles[row][col] = new Tile((short) model.getTileId(), (short) row, (short) col, model.getData(), (short) this.tileSize);
    }
    
    public TileMap append(TileMap other, int offsetX, int offsetY) {
        for(int i = 0; i<other.getBlocks().length; i++) {
            for(int j = 0; j<other.getBlocks()[i].length; j++) {
                Tile current = other.tiles[i][j];
                if(current!=null && !current.isVoid()) {
                    this.tiles[i+offsetY][j+offsetX]=current;
                }
            }
        }
        return this;
    }

    public Tile[] getBlocksInBounds(Rectangle cam) {
        int x = (int) ((cam.getPos().x) / this.getTileSize());
        int y = (int) ((cam.getPos().y) / this.getTileSize());
        List<Tile> results = new ArrayList<>();
        for (int i = x; i < (x + (cam.getWidth() / this.getTileSize())); i++) {
            for (int j = y; j < (y + (cam.getHeight() / this.getTileSize())); j++) {
                if (((i + (j * this.getHeight())) > -1) && ((i + (j * this.getHeight())) < this.getBlocks().length)
                        && (this.getBlocks()[i + (j * this.getHeight())] != null)) {
                    Tile toAdd = this.getBlocks()[i][j];
                    results.add(toAdd);
                }
            }
        }
        return results.toArray(new Tile[0]);
    }
    
    public boolean isValidPosition(float x, float y) {
    	return x > -1 && x<this.getWidth() && y>-1 && y<this.getHeight();
    }

    @SuppressWarnings("unused")
	private Tile[][] to2dArray(Tile[] tiles){
    	Tile[][] result = new Tile[maxY(tiles)][maxX(tiles)];
    	for(Tile t : tiles) {
    		result[(int) (t.getPos().y/t.getSize())][(int) (t.getPos().x/t.getSize())] = t;
    	}
    	return result;
    }
    
    private int maxX(Tile[] tiles) {
    	Integer best = Integer.MIN_VALUE;
    	for(Tile tile : tiles) {
    		if(tile.getPos().x>best) {
    			best = (int) tile.getPos().x;
    		}
    	}
    	return best;
    }
    
    private int maxY(Tile[] tiles) {
    	Integer best = Integer.MIN_VALUE;
    	for(Tile tile : tiles) {
    		if(tile.getPos().y>best) {
    			best = (int) tile.getPos().y;
    		}
    	}
    	return best;
    }
}
