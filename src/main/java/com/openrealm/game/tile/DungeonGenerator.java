package com.openrealm.game.tile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.realm.Realm;
import com.openrealm.util.Graph;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DungeonGenerator {
	private int width;
	private int height;
	private int tileSize;
	private int minRooms;
	private int maxRooms;
	private int minRoomWidth;
	private int maxRoomWidth;
	private int minRoomHeight;
	private int maxRoomHeight;
	private List<RoomShapeTemplate> shapeTemplates;
	private List<Integer> floorTileIds;
	private int wallTileId;
	private List<String> hallwayStyles;
	private int bossEnemyId;
	private Graph<TileMap> dungeon;
	private int bossRoomCenterX = -1;
	private int bossRoomCenterY = -1;
	private int spawnRoomCenterX = -1;
	private int spawnRoomCenterY = -1;

	public DungeonGenerator(int width, int height, int tileSize, int minRooms, int maxRooms, int minRoomWidth,
			int maxRoomWidth, int minRoomHeight, int maxRoomHeight, List<RoomShapeTemplate> shapeTemplates,
			List<Integer> floorTileIds, int wallTileId, List<String> hallwayStyles, int bossEnemyId) {
		this.width = width;
		this.height = height;
		this.tileSize = tileSize;
		this.minRooms = minRooms;
		this.maxRooms = maxRooms;
		this.minRoomWidth = minRoomWidth;
		this.maxRoomWidth = maxRoomWidth;
		this.minRoomHeight = minRoomHeight;
		this.maxRoomHeight = maxRoomHeight;
		this.shapeTemplates = shapeTemplates;
		this.floorTileIds = floorTileIds;
		this.wallTileId = wallTileId;
		this.hallwayStyles = hallwayStyles;
		this.bossEnemyId = bossEnemyId;
		this.dungeon = new Graph<>();
	}

	public int getBossRoomCenterX() {
		return this.bossRoomCenterX;
	}

	public int getBossRoomCenterY() {
		return this.bossRoomCenterY;
	}

	public int getSpawnRoomCenterX() {
		return this.spawnRoomCenterX;
	}

	public int getSpawnRoomCenterY() {
		return this.spawnRoomCenterY;
	}

	private TileModel getRandomFloorTile() {
		if (this.floorTileIds != null && !this.floorTileIds.isEmpty()) {
			int id = this.floorTileIds.get(Realm.RANDOM.nextInt(this.floorTileIds.size()));
			TileModel tile = GameDataManager.TILES.get(id);
			if (tile != null) return tile;
		}
		return GameDataManager.TILES.get(29);
	}

	private TileModel getWallTile() {
		if (this.wallTileId > 0) {
			TileModel tile = GameDataManager.TILES.get(this.wallTileId);
			if (tile != null) return tile;
		}
		return null;
	}

	public List<TileMap> generateDungeon() {
		final TileMap baseLayer = new TileMap(this.tileSize, this.width, this.height);
		final TileMap collisionLayer = new TileMap(this.tileSize, this.width, this.height);

		baseLayer.fill(0);
		collisionLayer.fill(0);
		final int numRooms = this.minRooms + Realm.RANDOM.nextInt((this.maxRooms - this.minRooms) + 1);

		TileMap previousRoom = null;
		int previousRoomOffsetX = 0;
		int previousRoomOffsetY = 0;

		int bossRoomOffsetX = -1, bossRoomOffsetY = -1;
		int bossRoomWidth = -1, bossRoomHeight = -1;

		log.info("[DungeonGen] Generating new procedural dungeon realm with room count {}. Params: {}", numRooms, this);

		final List<int[]> placedRooms = new ArrayList<>();

		for (int i = 0; i < numRooms; i++) {
			final boolean isBossRoom = (i == numRooms - 1);
			int roomMinW = this.minRoomWidth;
			int roomMaxW = this.maxRoomWidth;
			int roomMinH = this.minRoomHeight;
			int roomMaxH = this.maxRoomHeight;

			if (isBossRoom) {
				roomMinW = (int) (this.maxRoomWidth * 1.2);
				roomMaxW = (int) (this.maxRoomWidth * 1.5);
				roomMinH = (int) (this.maxRoomHeight * 1.2);
				roomMaxH = (int) (this.maxRoomHeight * 1.5);
			}

			final TileMap room = this.getRoom(this.tileSize, roomMinW, roomMaxW, roomMinH, roomMaxH,
					this.shapeTemplates, isBossRoom);

			int offsetX, offsetY;
			boolean placed = false;
			int attempts = 0;

			if (i == 0) {
				offsetX = 5 + Realm.RANDOM.nextInt(Math.max(1, this.width / 4));
				offsetY = 5 + Realm.RANDOM.nextInt(Math.max(1, this.height / 4));
				placed = true;
			} else {
				offsetX = 0;
				offsetY = 0;
				while (!placed && attempts < 30) {
					attempts++;
					int minGap = Math.max(this.minRoomWidth, this.minRoomHeight) + 3;
					int gapX = minGap + Realm.RANDOM.nextInt(Math.max(1, this.maxRoomWidth));
					int gapY = minGap + Realm.RANDOM.nextInt(Math.max(1, this.maxRoomHeight));

					int centerX = this.width / 2, centerY = this.height / 2;
					int biasX = previousRoomOffsetX < centerX ? 1 : -1;
					int biasY = previousRoomOffsetY < centerY ? 1 : -1;
					int dirX = Realm.RANDOM.nextInt(3) == 0 ? -biasX : biasX;
					int dirY = Realm.RANDOM.nextInt(3) == 0 ? -biasY : biasY;

					if (Realm.RANDOM.nextBoolean()) {
						offsetX = previousRoomOffsetX + (dirX * gapX);
						offsetY = previousRoomOffsetY + (dirY * (minGap / 2 + Realm.RANDOM.nextInt(Math.max(1, gapY / 2))));
					} else {
						offsetX = previousRoomOffsetX + (dirX * (minGap / 2 + Realm.RANDOM.nextInt(Math.max(1, gapX / 2))));
						offsetY = previousRoomOffsetY + (dirY * gapY);
					}

					offsetX = Math.max(2, Math.min(offsetX, this.width - room.getWidth() - 2));
					offsetY = Math.max(2, Math.min(offsetY, this.height - room.getHeight() - 2));

					boolean overlaps = false;
					for (int[] pr : placedRooms) {
						if (offsetX < pr[0] + pr[2] + 2 && offsetX + room.getWidth() + 2 > pr[0]
								&& offsetY < pr[1] + pr[3] + 2 && offsetY + room.getHeight() + 2 > pr[1]) {
							overlaps = true;
							break;
						}
					}
					// Boss room must be >= 40% of the map diagonal away from spawn.
					if (!overlaps && isBossRoom && this.spawnRoomCenterX >= 0) {
						int bCenterX = offsetX + room.getWidth() / 2;
						int bCenterY = offsetY + room.getHeight() / 2;
						double dist = Math.sqrt(Math.pow(bCenterX - this.spawnRoomCenterX, 2)
								+ Math.pow(bCenterY - this.spawnRoomCenterY, 2));
						double minDist = Math.sqrt(this.width * this.width + this.height * this.height) * 0.4;
						if (dist < minDist) {
							overlaps = true;
						}
					}
					if (!overlaps) placed = true;
				}
				if (!placed) {
					if (isBossRoom && this.spawnRoomCenterX >= 0) {
						offsetX = this.spawnRoomCenterX < this.width / 2
								? this.width - room.getWidth() - 5
								: 5;
						offsetY = this.spawnRoomCenterY < this.height / 2
								? this.height - room.getHeight() - 5
								: 5;
					}
					offsetX = Math.max(2, Math.min(offsetX, this.width - room.getWidth() - 2));
					offsetY = Math.max(2, Math.min(offsetY, this.height - room.getHeight() - 2));
				}
			}

			placedRooms.add(new int[]{ offsetX, offsetY, room.getWidth(), room.getHeight() });

			baseLayer.append(room, offsetX, offsetY);

			if (i == 0) {
				this.spawnRoomCenterX = offsetX + (room.getWidth() / 2);
				this.spawnRoomCenterY = offsetY + (room.getHeight() / 2);
			}

			log.info("[DungeonGen] Dungeon room added at coordinates {}, {} (boss={})", offsetX, offsetY, isBossRoom);
			if (previousRoom != null) {
				final int previousRoomCenterX = previousRoomOffsetX + (previousRoom.getWidth() / 2);
				final int previousRoomCenterY = previousRoomOffsetY + (previousRoom.getHeight() / 2);

				final int roomCenterX = offsetX + (room.getWidth() / 2);
				final int roomCenterY = offsetY + (room.getHeight() / 2);

				this.connectPoints(baseLayer, previousRoomCenterX, previousRoomCenterY, roomCenterX, roomCenterY);

				if (isBossRoom) {
					this.bossRoomCenterX = roomCenterX;
					this.bossRoomCenterY = roomCenterY;
					bossRoomOffsetX = offsetX;
					bossRoomOffsetY = offsetY;
					bossRoomWidth = room.getWidth();
					bossRoomHeight = room.getHeight();
				}
				this.dungeon.addVertex(previousRoom);
				this.dungeon.addVertex(room);
				this.dungeon.addEdge(previousRoom, room, true);
			}
			previousRoomOffsetX = offsetX;
			previousRoomOffsetY = offsetY;
			previousRoom = room;
		}

		int branchCount = Math.max(2, numRooms / 4);
		this.addSideBranches(baseLayer, branchCount);

		// Two passes: the second catches diagonal gaps at corridor intersections.
		this.lineWithWalls(baseLayer, collisionLayer);
		this.lineWithWalls(baseLayer, collisionLayer);

		this.roughenWalls(baseLayer, collisionLayer);
		this.lineWithWalls(baseLayer, collisionLayer);

		// Boss entrance MUST be carved after wall lining or the opening gets blocked.
		if (bossRoomOffsetX >= 0) {
			this.carveBossRoomEntrance(baseLayer, collisionLayer,
					bossRoomOffsetX, bossRoomOffsetY, bossRoomWidth, bossRoomHeight);
		}

		return Arrays.asList(baseLayer, collisionLayer);
	}

	private void lineWithWalls(TileMap baseLayer, TileMap collisionLayer) {
		TileModel wallTile = this.getWallTile();
		if (wallTile == null) return;

		Tile[][] baseTiles = baseLayer.getBlocks();
		Tile[][] collTiles = collisionLayer.getBlocks();

		for (int row = 0; row < this.height; row++) {
			for (int col = 0; col < this.width; col++) {
				Tile baseTile = baseTiles[row][col];
				if (baseTile != null && !baseTile.isVoid()) continue;
				if (collTiles[row][col] != null && !collTiles[row][col].isVoid()) continue;

				boolean adjacentToFloor = false;
				for (int dr = -1; dr <= 1 && !adjacentToFloor; dr++) {
					for (int dc = -1; dc <= 1 && !adjacentToFloor; dc++) {
						if (dr == 0 && dc == 0) continue;
						int nr = row + dr;
						int nc = col + dc;
						if (nr >= 0 && nr < this.height && nc >= 0 && nc < this.width) {
							Tile neighbor = baseTiles[nr][nc];
							if (neighbor != null && !neighbor.isVoid()) {
								adjacentToFloor = true;
							}
						}
					}
				}

				if (adjacentToFloor) {
					collisionLayer.setTileAt(row, col, wallTile);
				}
			}
		}
	}

	private void carveBossRoomEntrance(TileMap baseLayer, TileMap collisionLayer,
			int roomOffsetX, int roomOffsetY, int roomWidth, int roomHeight) {
		int halfOpening = 1;

		int topRow = roomOffsetY - 1;
		if (topRow >= 0) {
			for (int col = roomOffsetX; col < roomOffsetX + roomWidth; col++) {
				if (col < 0 || col >= baseLayer.getWidth()) continue;
				Tile outside = baseLayer.getBlocks()[topRow][col];
				if (outside != null && !outside.isVoid()) {
					this.carveWallAt(collisionLayer, roomOffsetY, col, halfOpening);
				}
			}
		}

		int bottomRow = roomOffsetY + roomHeight;
		if (bottomRow < baseLayer.getHeight()) {
			for (int col = roomOffsetX; col < roomOffsetX + roomWidth; col++) {
				if (col < 0 || col >= baseLayer.getWidth()) continue;
				Tile outside = baseLayer.getBlocks()[bottomRow][col];
				if (outside != null && !outside.isVoid()) {
					this.carveWallAt(collisionLayer, roomOffsetY + roomHeight - 1, col, halfOpening);
				}
			}
		}

		int leftCol = roomOffsetX - 1;
		if (leftCol >= 0) {
			for (int row = roomOffsetY; row < roomOffsetY + roomHeight; row++) {
				if (row < 0 || row >= baseLayer.getHeight()) continue;
				Tile outside = baseLayer.getBlocks()[row][leftCol];
				if (outside != null && !outside.isVoid()) {
					this.carveWallAt(collisionLayer, row, roomOffsetX, halfOpening);
				}
			}
		}

		int rightCol = roomOffsetX + roomWidth;
		if (rightCol < baseLayer.getWidth()) {
			for (int row = roomOffsetY; row < roomOffsetY + roomHeight; row++) {
				if (row < 0 || row >= baseLayer.getHeight()) continue;
				Tile outside = baseLayer.getBlocks()[row][rightCol];
				if (outside != null && !outside.isVoid()) {
					this.carveWallAt(collisionLayer, row, roomOffsetX + roomWidth - 1, halfOpening);
				}
			}
		}
	}

	private void carveWallAt(TileMap collisionLayer, int row, int col, int halfOpening) {
		for (int dr = -halfOpening; dr <= halfOpening; dr++) {
			for (int dc = -halfOpening; dc <= halfOpening; dc++) {
				int nr = row + dr;
				int nc = col + dc;
				if (nr >= 0 && nr < collisionLayer.getHeight() && nc >= 0 && nc < collisionLayer.getWidth()) {
					collisionLayer.getBlocks()[nr][nc] = null;
				}
			}
		}
	}

	private String pickHallwayStyle() {
		if (this.hallwayStyles != null && !this.hallwayStyles.isEmpty()) {
			return this.hallwayStyles.get(Realm.RANDOM.nextInt(this.hallwayStyles.size()));
		}
		return "L_SHAPED";
	}

	public void connectPoints(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		String style = this.pickHallwayStyle();
		log.info("[DungeonGen] Connecting rooms SRC {}, {}. TARGET {}, {} with style {}", srcX, srcY, destX, destY, style);

		switch (style) {
			case "ZIGZAG":
				this.connectZigzag(targetLayer, srcX, srcY, destX, destY);
				break;
			case "WIDE":
				this.connectWide(targetLayer, srcX, srcY, destX, destY);
				break;
			case "WINDING":
				this.connectWinding(targetLayer, srcX, srcY, destX, destY);
				break;
			case "CURVED":
				this.connectCurved(targetLayer, srcX, srcY, destX, destY);
				break;
			case "S_BEND":
				this.connectSBend(targetLayer, srcX, srcY, destX, destY);
				break;
			case "L_SHAPED":
			default:
				this.connectLShaped(targetLayer, srcX, srcY, destX, destY);
				break;
		}
	}

	private void fillHorizontal(TileMap targetLayer, int row, int startCol, int endCol, int halfWidth) {
		int minCol = Math.min(startCol, endCol);
		int maxCol = Math.max(startCol, endCol);
		for (int col = minCol; col <= maxCol; col++) {
			for (int r = row - halfWidth; r <= row + halfWidth; r++) {
				this.safeSetFloorTile(targetLayer, r, col);
			}
		}
	}

	private void fillVertical(TileMap targetLayer, int col, int startRow, int endRow, int halfWidth) {
		int minRow = Math.min(startRow, endRow);
		int maxRow = Math.max(startRow, endRow);
		for (int row = minRow; row <= maxRow; row++) {
			for (int c = col - halfWidth; c <= col + halfWidth; c++) {
				this.safeSetFloorTile(targetLayer, row, c);
			}
		}
	}

	private void safeSetFloorTile(TileMap targetLayer, int row, int col) {
		try {
			if (row >= 0 && row < targetLayer.getHeight() && col >= 0 && col < targetLayer.getWidth()) {
				targetLayer.setTileAt(row, col, this.getRandomFloorTile());
			}
		} catch (Exception e) {
			log.debug("[DungeonGen] Failed to fill walkway tile at {}, {}. Reason: {}", row, col, e.getMessage());
		}
	}

	private void connectLShaped(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int hw = 1;
		this.fillHorizontal(targetLayer, srcY, srcX, destX, hw);
		this.fillVertical(targetLayer, destX, srcY, destY, hw);
		// Fill corner square so the L-bend has no diagonal gap.
		for (int r = srcY - hw; r <= srcY + hw; r++) {
			for (int c = destX - hw; c <= destX + hw; c++) {
				this.safeSetFloorTile(targetLayer, r, c);
			}
		}
	}

	private void connectZigzag(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int midX1 = (srcX + destX) / 3 + (Realm.RANDOM.nextInt(5) - 2);
		int midX2 = (srcX + destX) * 2 / 3 + (Realm.RANDOM.nextInt(5) - 2);
		int midY = (srcY + destY) / 2 + (Realm.RANDOM.nextInt(7) - 3);

		midX1 = Math.max(1, Math.min(midX1, this.width - 2));
		midX2 = Math.max(1, Math.min(midX2, this.width - 2));
		midY = Math.max(1, Math.min(midY, this.height - 2));

		int hw = 1;
		this.fillHorizontal(targetLayer, srcY, srcX, midX1, hw);
		this.fillVertical(targetLayer, midX1, srcY, midY, hw);
		for (int r = srcY - hw; r <= srcY + hw; r++)
			for (int c = midX1 - hw; c <= midX1 + hw; c++)
				this.safeSetFloorTile(targetLayer, r, c);

		this.fillHorizontal(targetLayer, midY, midX1, midX2, hw);
		for (int r = midY - hw; r <= midY + hw; r++) {
			for (int c = midX1 - hw; c <= midX1 + hw; c++)
				this.safeSetFloorTile(targetLayer, r, c);
			for (int c = midX2 - hw; c <= midX2 + hw; c++)
				this.safeSetFloorTile(targetLayer, r, c);
		}

		this.fillVertical(targetLayer, midX2, midY, destY, hw);
		this.fillHorizontal(targetLayer, destY, midX2, destX, hw);
		for (int r = destY - hw; r <= destY + hw; r++)
			for (int c = midX2 - hw; c <= midX2 + hw; c++)
				this.safeSetFloorTile(targetLayer, r, c);
	}

	private void connectWide(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int hw = 2;
		this.fillHorizontal(targetLayer, srcY, srcX, destX, hw);
		this.fillVertical(targetLayer, destX, srcY, destY, hw);
		for (int r = srcY - hw; r <= srcY + hw; r++) {
			for (int c = destX - hw; c <= destX + hw; c++) {
				this.safeSetFloorTile(targetLayer, r, c);
			}
		}
	}

	private void connectWinding(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int curX = srcX;
		int curY = srcY;
		int maxSteps = Math.abs(destX - srcX) + Math.abs(destY - srcY) + 20;
		int steps = 0;

		while ((curX != destX || curY != destY) && steps < maxSteps) {
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					this.safeSetFloorTile(targetLayer, curY + dr, curX + dc);
				}
			}

			int dx = Integer.compare(destX, curX);
			int dy = Integer.compare(destY, curY);

			if (Realm.RANDOM.nextFloat() < 0.7f) {
				if (Math.abs(destX - curX) > Math.abs(destY - curY)) {
					curX += dx;
				} else if (dy != 0) {
					curY += dy;
				} else {
					curX += dx;
				}
			} else {
				if (Realm.RANDOM.nextBoolean()) {
					curX += (Realm.RANDOM.nextInt(3) - 1);
				} else {
					curY += (Realm.RANDOM.nextInt(3) - 1);
				}
			}

			curX = Math.max(1, Math.min(curX, this.width - 2));
			curY = Math.max(1, Math.min(curY, this.height - 2));
			steps++;
		}

		for (int dr = -1; dr <= 1; dr++) {
			for (int dc = -1; dc <= 1; dc++) {
				this.safeSetFloorTile(targetLayer, destY + dr, destX + dc);
			}
		}
	}

	public List<Integer> getRoomLinkParams(TileMap room0, TileMap room1) {
		return null;
	}

	public TileMap getRoom(int tileSize, int minRoomWidth, int maxRoomWidth, int minRoomHeight, int maxRoomHeight,
			List<RoomShapeTemplate> shapeTemplates, boolean isBossRoom) {
		final RoomShapeTemplate shape = shapeTemplates.get(Realm.RANDOM.nextInt(shapeTemplates.size()));
		final int roomWidth = minRoomWidth + Realm.RANDOM.nextInt((maxRoomWidth - minRoomWidth) + 1);
		final int roomHeight = minRoomHeight + Realm.RANDOM.nextInt((maxRoomHeight - minRoomHeight) + 1);
		final TileMap baseLayer = new TileMap(tileSize, roomWidth, roomHeight);
		log.info("[DungeonGen] Generating room with params tileSize={}, roomWidth={}, roomHeight={}, shape={}", tileSize, roomWidth, roomHeight, shape);

		switch (shape) {
			case RECTANGLE:
				this.generateRectangleRoom(baseLayer, roomWidth, roomHeight);
				break;
			case OVAL:
				this.generateOvalRoom(baseLayer, roomWidth, roomHeight);
				break;
			case DIAMOND:
				this.generateDiamondRoom(baseLayer, roomWidth, roomHeight);
				break;
			case CROSS:
				this.generateCrossRoom(baseLayer, roomWidth, roomHeight);
				break;
			case L_SHAPE:
				this.generateLShapeRoom(baseLayer, roomWidth, roomHeight);
				break;
			case TRIANGLE:
				this.generateTriangleRoom(baseLayer, roomWidth, roomHeight);
				break;
			case IRREGULAR:
				this.generateIrregularRoom(baseLayer, roomWidth, roomHeight);
				break;
			default:
				this.generateRectangleRoom(baseLayer, roomWidth, roomHeight);
				break;
		}

		return baseLayer;
	}

	private void generateRectangleRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				TileModel model = this.getRandomFloorTile();
				baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
			}
		}
	}

	private void generateOvalRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		final int centerX = roomWidth / 2;
		final int centerY = roomHeight / 2;
		final Vector2f pos = new Vector2f(centerX, centerY);
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				if (pos.distanceTo(new Vector2f(i, j)) < (roomWidth / 2)) {
					final TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	private void generateDiamondRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		final int centerX = roomWidth / 2;
		final int centerY = roomHeight / 2;
		final int radius = Math.min(centerX, centerY);
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				int dx = Math.abs(j - centerX);
				int dy = Math.abs(i - centerY);
				if (dx + dy <= radius) {
					final TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	private void generateCrossRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		final int centerX = roomWidth / 2;
		final int centerY = roomHeight / 2;
		final int armWidth = Math.max(2, roomWidth / 3);
		final int armHeight = Math.max(2, roomHeight / 3);
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				boolean inVerticalArm = (j >= centerX - armWidth / 2 && j < centerX + armWidth / 2);
				boolean inHorizontalArm = (i >= centerY - armHeight / 2 && i < centerY + armHeight / 2);
				if (inVerticalArm || inHorizontalArm) {
					final TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	private void generateLShapeRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		int splitX = roomWidth / 2 + Realm.RANDOM.nextInt(Math.max(1, roomWidth / 4));
		int splitY = roomHeight / 2 + Realm.RANDOM.nextInt(Math.max(1, roomHeight / 4));
		boolean mirrored = Realm.RANDOM.nextBoolean();

		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				boolean inShape;
				if (mirrored) {
					inShape = (i >= splitY || j >= (roomWidth - splitX));
				} else {
					inShape = (i >= splitY || j < splitX);
				}
				if (inShape) {
					final TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	private void generateTriangleRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		int dir = Realm.RANDOM.nextInt(4); // 0=up 1=down 2=left 3=right
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				boolean inShape;
				switch (dir) {
					case 0: {
						float progress = (float) i / roomHeight;
						float halfW = (roomWidth / 2f) * progress;
						inShape = Math.abs(j - roomWidth / 2f) <= halfW;
						break;
					}
					case 1: {
						float progress = 1f - (float) i / roomHeight;
						float halfW = (roomWidth / 2f) * progress;
						inShape = Math.abs(j - roomWidth / 2f) <= halfW;
						break;
					}
					case 2: {
						float progress = (float) j / roomWidth;
						float halfH = (roomHeight / 2f) * progress;
						inShape = Math.abs(i - roomHeight / 2f) <= halfH;
						break;
					}
					default: {
						float progress = 1f - (float) j / roomWidth;
						float halfH = (roomHeight / 2f) * progress;
						inShape = Math.abs(i - roomHeight / 2f) <= halfH;
						break;
					}
				}
				if (inShape) {
					TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	/** Cellular-automata cave: 45% seed + 4 iters of B5678/S45678. */
	private void generateIrregularRoom(TileMap baseLayer, int roomWidth, int roomHeight) {
		boolean[][] alive = new boolean[roomHeight][roomWidth];
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				if (i == 0 || i == roomHeight - 1 || j == 0 || j == roomWidth - 1) {
					alive[i][j] = false;
				} else {
					alive[i][j] = Realm.RANDOM.nextFloat() < 0.45f;
				}
			}
		}

		for (int iter = 0; iter < 4; iter++) {
			boolean[][] next = new boolean[roomHeight][roomWidth];
			for (int i = 1; i < roomHeight - 1; i++) {
				for (int j = 1; j < roomWidth - 1; j++) {
					int neighbors = 0;
					for (int di = -1; di <= 1; di++) {
						for (int dj = -1; dj <= 1; dj++) {
							if (di == 0 && dj == 0) continue;
							if (alive[i + di][j + dj]) neighbors++;
						}
					}
					next[i][j] = neighbors >= 5 || (alive[i][j] && neighbors >= 4);
				}
			}
			alive = next;
		}

		// Force center open so the room is usable.
		int cx = roomWidth / 2, cy = roomHeight / 2;
		int clearRadius = Math.min(roomWidth, roomHeight) / 4;
		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				int dx = j - cx, dy = i - cy;
				if (dx * dx + dy * dy <= clearRadius * clearRadius) {
					alive[i][j] = true;
				}
			}
		}

		for (int i = 0; i < roomHeight; i++) {
			for (int j = 0; j < roomWidth; j++) {
				if (alive[i][j]) {
					TileModel model = this.getRandomFloorTile();
					baseLayer.setTileAt(i, j, (short) model.getTileId(), model.getData());
				}
			}
		}
	}

	/** Quadratic Bézier corridor with a perpendicular control-point offset. */
	private void connectCurved(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int midX = (srcX + destX) / 2;
		int midY = (srcY + destY) / 2;
		int dx = destX - srcX;
		int dy = destY - srcY;
		float dist = (float) Math.sqrt(dx * dx + dy * dy);
		float offset = dist * (0.2f + Realm.RANDOM.nextFloat() * 0.2f);
		if (Realm.RANDOM.nextBoolean()) offset = -offset;
		float perpX = -dy / dist;
		float perpY = dx / dist;
		int ctrlX = midX + Math.round(perpX * offset);
		int ctrlY = midY + Math.round(perpY * offset);

		int steps = (int) (dist * 1.5f);
		steps = Math.max(steps, 20);
		int hw = 1;
		for (int s = 0; s <= steps; s++) {
			float t = (float) s / steps;
			float u = 1f - t;
			float bx = u * u * srcX + 2 * u * t * ctrlX + t * t * destX;
			float by = u * u * srcY + 2 * u * t * ctrlY + t * t * destY;
			int px = Math.round(bx);
			int py = Math.round(by);
			for (int dr = -hw; dr <= hw; dr++) {
				for (int dc = -hw; dc <= hw; dc++) {
					this.safeSetFloorTile(targetLayer, py + dr, px + dc);
				}
			}
		}
	}

	private void connectSBend(TileMap targetLayer, int srcX, int srcY, int destX, int destY) {
		int midX = (srcX + destX) / 2;
		int midY = (srcY + destY) / 2;
		midX += Realm.RANDOM.nextInt(5) - 2;
		midY += Realm.RANDOM.nextInt(5) - 2;
		midX = Math.max(2, Math.min(midX, this.width - 3));
		midY = Math.max(2, Math.min(midY, this.height - 3));

		this.connectCurved(targetLayer, srcX, srcY, midX, midY);
		this.connectCurved(targetLayer, midX, midY, destX, destY);
	}

	void roughenWalls(TileMap baseLayer, TileMap collisionLayer) {
		Tile[][] base = baseLayer.getBlocks();
		Tile[][] coll = collisionLayer.getBlocks();
		float carveChance = 0.12f;

		// Collect candidates first; modifying mid-iteration would corrupt the scan.
		List<int[]> carves = new ArrayList<>();
		for (int r = 2; r < this.height - 2; r++) {
			for (int c = 2; c < this.width - 2; c++) {
				if (coll[r][c] == null || coll[r][c].isVoid()) continue;
				int floorNeighbors = 0;
				for (int dr = -1; dr <= 1; dr++) {
					for (int dc = -1; dc <= 1; dc++) {
						if (dr == 0 && dc == 0) continue;
						if (base[r + dr][c + dc] != null && !base[r + dr][c + dc].isVoid()
								&& (coll[r + dr][c + dc] == null || coll[r + dr][c + dc].isVoid())) {
							floorNeighbors++;
						}
					}
				}
				if (floorNeighbors >= 1 && floorNeighbors <= 2 && Realm.RANDOM.nextFloat() < carveChance) {
					carves.add(new int[]{ r, c });
				}
			}
		}

		TileModel wallTile = this.getWallTile();
		for (int[] pos : carves) {
			int r = pos[0], c = pos[1];
			coll[r][c] = null;
			baseLayer.setTileAt(r, c, this.getRandomFloorTile());
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					int nr = r + dr, nc = c + dc;
					if (nr < 0 || nr >= this.height || nc < 0 || nc >= this.width) continue;
					if ((base[nr][nc] == null || base[nr][nc].isVoid())
							&& (coll[nr][nc] == null || coll[nr][nc].isVoid())) {
						if (wallTile != null) collisionLayer.setTileAt(nr, nc, wallTile);
					}
				}
			}
		}
	}

	void addSideBranches(TileMap baseLayer, int count) {
		Tile[][] base = baseLayer.getBlocks();
		int added = 0;
		int attempts = 0;
		while (added < count && attempts < count * 10) {
			attempts++;
			int r = 3 + Realm.RANDOM.nextInt(Math.max(1, this.height - 6));
			int c = 3 + Realm.RANDOM.nextInt(Math.max(1, this.width - 6));
			if (base[r][c] == null || base[r][c].isVoid()) continue;

			// Corridor tiles have ~3-5 floor neighbors; rooms have 7-8.
			int floorCount = 0;
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					if (dr == 0 && dc == 0) continue;
					if (r + dr >= 0 && r + dr < this.height && c + dc >= 0 && c + dc < this.width
							&& base[r + dr][c + dc] != null && !base[r + dr][c + dc].isVoid()) {
						floorCount++;
					}
				}
			}
			if (floorCount < 3 || floorCount > 5) continue;

			int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
			int[] dir = dirs[Realm.RANDOM.nextInt(4)];
			int checkR = r + dir[0] * 2;
			int checkC = c + dir[1] * 2;
			if (checkR < 2 || checkR >= this.height - 2 || checkC < 2 || checkC >= this.width - 2) continue;
			if (base[checkR][checkC] != null && !base[checkR][checkC].isVoid()) continue;

			int branchLen = 3 + Realm.RANDOM.nextInt(4);
			boolean valid = true;
			for (int step = 1; step <= branchLen; step++) {
				int br = r + dir[0] * step;
				int bc = c + dir[1] * step;
				if (br < 2 || br >= this.height - 2 || bc < 2 || bc >= this.width - 2) {
					valid = false;
					break;
				}
			}
			if (!valid) continue;

			int hw = Realm.RANDOM.nextBoolean() ? 1 : 0;
			for (int step = 1; step <= branchLen; step++) {
				int br = r + dir[0] * step;
				int bc = c + dir[1] * step;
				for (int d = -hw; d <= hw; d++) {
					if (dir[0] == 0) {
						this.safeSetFloorTile(baseLayer, br + d, bc);
					} else {
						this.safeSetFloorTile(baseLayer, br, bc + d);
					}
				}
			}
			int endR = r + dir[0] * branchLen;
			int endC = c + dir[1] * branchLen;
			int endSize = 1 + Realm.RANDOM.nextInt(2);
			for (int dr = -endSize; dr <= endSize; dr++) {
				for (int dc = -endSize; dc <= endSize; dc++) {
					this.safeSetFloorTile(baseLayer, endR + dr, endC + dc);
				}
			}
			added++;
		}
	}
}
