package com.openrealm.game.tile;

import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Tile {
	private short tileId;
	private short row;
	private short col;
	private short tileSize = (short) GlobalConstants.BASE_TILE_SIZE;
	// Pack collision/slows/damaging/isWall into a single byte to eliminate TileData object per tile.
	// Bit 0 = collision, bit 1 = slows, bit 2 = damaging, bit 3 = isWall
	private byte flags;

	private static final TileData[] SHARED_DATA = new TileData[16];
	static {
		for (int i = 0; i < 16; i++) {
			SHARED_DATA[i] = new TileData((byte)(i & 1), (byte)((i >> 1) & 1), (byte)((i >> 2) & 1), (byte)((i >> 3) & 1));
		}
	}

	public Tile(short tileId, Vector2f pos, TileData data, short size, boolean discovered) {
		this.tileId = tileId;
		this.tileSize = size;
		this.col = (short) (pos.x / size);
		this.row = (short) (pos.y / size);
		this.flags = dataToFlags(data);
	}

	public Tile(short tileId, short row, short col, TileData data, short size) {
		this.tileId = tileId;
		this.tileSize = size;
		this.row = row;
		this.col = col;
		this.flags = dataToFlags(data);
	}

	private static byte dataToFlags(TileData data) {
		if (data == null) return 0;
		return (byte) ((data.hasCollision() ? 1 : 0)
				| (data.slows() ? 2 : 0)
				| (data.damaging() ? 4 : 0)
				| (data.isWall() ? 8 : 0));
	}

	public TileData getData() {
		return SHARED_DATA[this.flags & 0xF];
	}

	public void setData(TileData data) {
		this.flags = dataToFlags(data);
	}

	public int getSize() {
		return this.tileSize;
	}

	public boolean update(Rectangle bounds) {
		return false;
	}

	public int getWidth() {
		return this.tileSize;
	}

	public int getHeight() {
		return this.tileSize;
	}

	public Vector2f getPos() {
		return new Vector2f(this.col * this.tileSize, this.row * this.tileSize);
	}

	public float getWorldX() {
		return this.col * this.tileSize;
	}

	public float getWorldY() {
		return this.row * this.tileSize;
	}

	public boolean isVoid() {
		return this.tileId == 0;
	}

	public boolean isDiscovered() {
		return false;
	}
}
