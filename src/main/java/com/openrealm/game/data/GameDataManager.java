package com.openrealm.game.data;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.ExperienceModel;
import com.openrealm.game.model.LootGroupModel;
import com.openrealm.game.model.LootTableModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.RealmEventModel;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.model.TileModel;
import com.openrealm.net.core.IOService;
import com.openrealm.net.server.ServerGameLogic;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GameDataManager {
	public static final transient ObjectMapper JSON_MAPPER = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	public static Map<Integer, ProjectileGroup>               PROJECTILE_GROUPS = null;
	public static Map<Integer, GameItem>                      GAME_ITEMS = null;
	public static Map<Integer, EnemyModel>                    ENEMIES = null;
	public static Map<Integer, TileModel>                     TILES = null;
	public static Map<Integer, MapModel>                      MAPS = null;
	public static Map<Integer, TerrainGenerationParameters>   TERRAINS = null;
	public static Map<Integer, PortalModel>                   PORTALS = null;
	public static Map<Integer, CharacterClassModel>           CHARACTER_CLASSES = null;
	public static Map<Integer, LootTableModel>                LOOT_TABLES = null;
	public static Map<Integer, LootGroupModel>                LOOT_GROUPS = null;
	public static ExperienceModel                             EXPERIENCE_LVLS = null;
	public static Map<String, DungeonGraphNode>               DUNGEON_GRAPH = null;
	public static Map<Integer, AnimationModel>                ANIMATIONS = null;
	public static Map<Integer, SetPieceModel>                 SETPIECES = null;
	public static Map<Integer, RealmEventModel>               REALM_EVENTS = null;

	private static void loadLootGroups(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Loot Groups...");
		GameDataManager.LOOT_GROUPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/loot-groups.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/loot-groups.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		LootGroupModel[] lootGroups = GameDataManager.JSON_MAPPER.readValue(text, LootGroupModel[].class);
		for (LootGroupModel lootGroup : lootGroups) {
			GameDataManager.LOOT_GROUPS.put(lootGroup.getLootGroupId(), lootGroup);
		}
		GameDataManager.log.info("Loading Loot Groups... DONE");
	}

	private static void loadLootTables(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Loot Tables...");
		GameDataManager.LOOT_TABLES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/loot-tables.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/loot-tables.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		LootTableModel[] lootTables = GameDataManager.JSON_MAPPER.readValue(text, LootTableModel[].class);
		for (LootTableModel lootTable : lootTables) {
			GameDataManager.LOOT_TABLES.put(lootTable.getEnemyId(), lootTable);
		}
		GameDataManager.log.info("Loading Loot Tables... DONE");
	}

	private static void loadCharacterClasses(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Character Classes...");
		GameDataManager.CHARACTER_CLASSES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/character-classes.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/character-classes.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		CharacterClassModel[] characterClasses = GameDataManager.JSON_MAPPER.readValue(text,
				CharacterClassModel[].class);
		for (CharacterClassModel characterClass : characterClasses) {
			GameDataManager.CHARACTER_CLASSES.put(characterClass.getClassId(), characterClass);
		}
		GameDataManager.log.info("Loading Character Classes... DONE");
	}

	private static void loadExperienceModel(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading ExperienceModel...");
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/exp-levels.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/exp-levels.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		ExperienceModel expModel = GameDataManager.JSON_MAPPER.readValue(text, ExperienceModel.class);
		expModel.parseMap();
		GameDataManager.EXPERIENCE_LVLS = expModel;
		GameDataManager.log.info("Loading ExperienceModel... DONE");
	}

	private static void loadPortals(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Portals...");
		GameDataManager.PORTALS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/portals.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/portals.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		PortalModel[] maps = GameDataManager.JSON_MAPPER.readValue(text, PortalModel[].class);
		for (PortalModel map : maps) {
			GameDataManager.PORTALS.put(map.getPortalId(), map);
		}
		GameDataManager.log.info("Loading Portals... DONE");
	}

	private static void loadDungeonGraph(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Dungeon Graph...");
		GameDataManager.DUNGEON_GRAPH = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/dungeon-graph.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/dungeon-graph.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		DungeonGraphNode[] nodes = GameDataManager.JSON_MAPPER.readValue(text, DungeonGraphNode[].class);
		for (DungeonGraphNode node : nodes) {
			GameDataManager.DUNGEON_GRAPH.put(node.getNodeId(), node);
		}
		GameDataManager.log.info("Loading Dungeon Graph... DONE ({} nodes)", GameDataManager.DUNGEON_GRAPH.size());
	}

	private static void loadTerrains(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Terrains...");
		GameDataManager.TERRAINS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/terrains.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/terrains.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		TerrainGenerationParameters[] maps = GameDataManager.JSON_MAPPER.readValue(text,
				TerrainGenerationParameters[].class);
		for (TerrainGenerationParameters map : maps) {
			GameDataManager.TERRAINS.put(map.getTerrainId(), map);
		}
		GameDataManager.log.info("Loading Terrains... DONE");
	}

	private static void loadMaps(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Maps... ");
		GameDataManager.MAPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/maps.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/maps.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		MapModel[] maps = GameDataManager.JSON_MAPPER.readValue(text, MapModel[].class);
		for (MapModel map : maps) {
			GameDataManager.MAPS.put(map.getMapId(), map);
		}
		GameDataManager.log.info("Loading Maps... DONE");
	}

	private static void loadTiles(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Tiles...");
		GameDataManager.TILES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/tiles.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/tiles.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		TileModel[] tiles = GameDataManager.JSON_MAPPER.readValue(text, TileModel[].class);
		for (TileModel tile : tiles) {
			GameDataManager.TILES.put(tile.getTileId(), tile);
		}
		GameDataManager.log.info("Loading Tiles... DONE");
	}

	private static void loadEnemies(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Enemies...");
		GameDataManager.ENEMIES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/enemies.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader().getResourceAsStream("data/enemies.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		EnemyModel[] enemies = GameDataManager.JSON_MAPPER.readValue(text, EnemyModel[].class);
		for (EnemyModel enemy : enemies) {
			GameDataManager.ENEMIES.put(enemy.getEnemyId(), enemy);
		}
		GameDataManager.log.info("Loading Enemies... DONE");
	}

	private static void loadProjectileGroups(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Projectile Groups...");

		GameDataManager.PROJECTILE_GROUPS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/projectile-groups.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/projectile-groups.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		ProjectileGroup[] projectileGroups = GameDataManager.JSON_MAPPER.readValue(text, ProjectileGroup[].class);

		for (ProjectileGroup group : projectileGroups) {
			if ((group.getAngleOffset() != null) && group.getAngleOffset().contains("{{")) {
				group.setAngleOffset(GameDataManager.replaceInjectVariables(group.getAngleOffset()));
			} else {
				group.setAngleOffset("0");
			}
			for (Projectile p : group.getProjectiles()) {
				if (p.getAngle().contains("{{")) {
					p.setAngle(GameDataManager.replaceInjectVariables(p.getAngle()));
				}
			}
			GameDataManager.PROJECTILE_GROUPS.put(group.getProjectileGroupId(), group);
		}
		GameDataManager.log.info("Loading Projectile Groups... DONE");

	}

	private static void loadAnimations(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Animations...");
		GameDataManager.ANIMATIONS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/animations.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/animations.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		AnimationModel[] animations = GameDataManager.JSON_MAPPER.readValue(text, AnimationModel[].class);
		for (AnimationModel anim : animations) {
			GameDataManager.ANIMATIONS.put(anim.getObjectId(), anim);
		}
		GameDataManager.log.info("Loading Animations... DONE ({} entries)", GameDataManager.ANIMATIONS.size());
	}

	private static void loadSetPieces(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading SetPieces...");
		GameDataManager.SETPIECES = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/setpieces.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/setpieces.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		SetPieceModel[] pieces = GameDataManager.JSON_MAPPER.readValue(text, SetPieceModel[].class);
		for (SetPieceModel piece : pieces) {
			GameDataManager.SETPIECES.put(piece.getSetPieceId(), piece);
		}
		GameDataManager.log.info("Loading SetPieces... DONE ({} entries)", GameDataManager.SETPIECES.size());
	}

	private static void loadRealmEvents(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Realm Events...");
		GameDataManager.REALM_EVENTS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/realm-events.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/realm-events.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		RealmEventModel[] events = GameDataManager.JSON_MAPPER.readValue(text, RealmEventModel[].class);
		for (RealmEventModel event : events) {
			GameDataManager.REALM_EVENTS.put(event.getEventId(), event);
		}
		GameDataManager.log.info("Loading Realm Events... DONE ({} entries)", GameDataManager.REALM_EVENTS.size());
	}

	private static void loadGameItems(final boolean remote) throws Exception {
		GameDataManager.log.info("Loading Game Items...");

		GameDataManager.GAME_ITEMS = new HashMap<>();
		String text = null;
		if (remote) {
			text = ServerGameLogic.DATA_SERVICE.executeGet("game-data/game-items.json", null);
		} else {
			InputStream inputStream = GameDataManager.class.getClassLoader()
					.getResourceAsStream("data/game-items.json");
			text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		GameItem[] gameItems = GameDataManager.JSON_MAPPER.readValue(text, GameItem[].class);

		for (GameItem item : gameItems) {
			GameDataManager.GAME_ITEMS.put(item.getItemId(), item);
		}
		GameDataManager.log.info("Loading Game Items... DONE");
	}

	public static Map<Integer, GameItem> getStartingEquipment(final CharacterClass characterClass) {
		final CharacterClassModel model = GameDataManager.CHARACTER_CLASSES.get(characterClass.classId);
		return model.getStartingEquipmentMap();
	}

	private static String replaceInjectVariables(String input) {
		String randomizeRegex = "\\{\\{(.*?)}}";
		Pattern pattern = Pattern.compile(randomizeRegex);
		Matcher matcher = pattern.matcher(input);
		while (matcher.find()) {
			String match = matcher.group(1);
			if (match.contains("PI/")) {
				String[] split = match.split("/");

				float multF = 1.0f;
				if (split[0].length() > 2) {
					int endIndex = split[0].indexOf("P");
					multF = Float.parseFloat(split[0].substring(0, endIndex));
				}
				float angle = (float) ((multF * Math.PI) / Float.parseFloat(split[1]));
				input = GameDataManager.replaceGen(input, match, angle + "");

			} else if (match.contains("PI")) {
				float angle = (float) Math.PI;
				input = GameDataManager.replaceGen(input, match, angle + "");
			}
		}
		return input;

	}

	public static DungeonGraphNode getEntryNode() {
		for (DungeonGraphNode node : DUNGEON_GRAPH.values()) {
			if (node.isEntryPoint()) return node;
		}
		return null;
	}

	public static DungeonGraphNode getNodeForPortal(String parentNodeId, int portalId) {
		DungeonGraphNode parent = DUNGEON_GRAPH.get(parentNodeId);
		if (parent == null) return null;
		for (Map.Entry<String, Integer> entry : parent.getPortalDropNodeMap().entrySet()) {
			if (entry.getValue() == portalId) {
				return DUNGEON_GRAPH.get(entry.getKey());
			}
		}
		return null;
	}

	public static String replaceGen(String source, String variable, String value) {
		final String text = source.replace("{{" + variable + "}}", value);
		return text;
	}

	public static void loadGameData(final boolean loadRemote) {
		GameDataManager.log.info("Loading Game Data from remote={}", loadRemote);
		// Load each data type independently so one failure doesn't prevent loading the rest
		Runnable[] loaders = {
			() -> { try { GameDataManager.loadProjectileGroups(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load projectile groups: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadGameItems(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load game items: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadEnemies(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load enemies: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadTiles(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load tiles: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadMaps(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load maps: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadTerrains(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load terrains: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadPortals(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load portals: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadDungeonGraph(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load dungeon graph: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadExperienceModel(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load experience model: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadCharacterClasses(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load character classes: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadLootTables(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load loot tables: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadLootGroups(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load loot groups: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadAnimations(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load animations: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadSetPieces(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load set pieces: {}", e.getMessage()); } },
			() -> { try { GameDataManager.loadRealmEvents(loadRemote); } catch (Exception e) { GameDataManager.log.error("Failed to load realm events: {}", e.getMessage()); } },
		};
		for (Runnable loader : loaders) {
			loader.run();
		}
		try {
			IOService.mapSerializableData();
		} catch (Exception e) {
			GameDataManager.log.error("Failed to map serializable data: {}", e.getMessage());
		}
	}
}
