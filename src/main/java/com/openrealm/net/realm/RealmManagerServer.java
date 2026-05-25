package com.openrealm.net.realm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityEffect;
import com.openrealm.game.model.ability.AbilityScaling;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.model.ability.PassiveTrigger;
import com.openrealm.net.client.packet.AbilityCastStartPacket;
import com.openrealm.net.client.packet.CreateEffectPacket;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.CharacterStatsDto;
import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.contants.PacketType;
import com.openrealm.game.contants.ProjectilePositionMode;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.CastState;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Entity;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.GemstoneRegistry;
import com.openrealm.game.entity.item.gem.ShotContext;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.metrics.MetricsDelta;
import com.openrealm.game.metrics.MetricsDeltaDto;
import com.openrealm.game.metrics.PlayerMetrics;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.model.LootGroupModel;
import com.openrealm.game.model.LootTableModel;
import com.openrealm.game.model.PortalModel;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileEffect;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.script.EnemyScriptBase;
import com.openrealm.game.script.item.Item153Script;
import com.openrealm.game.script.item.Item156Script;
import com.openrealm.game.script.item.Item157Script;
import com.openrealm.game.script.item.UseableItemScript;
import com.openrealm.game.script.item.UseableItemScriptBase;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileMap;
import com.openrealm.game.tile.decorators.Beach0Decorator;
import com.openrealm.game.tile.decorators.Grasslands0Decorator;
import com.openrealm.game.tile.decorators.RealmDecorator;
import com.openrealm.game.tile.decorators.RealmDecoratorBase;
import com.openrealm.net.Packet;
import com.openrealm.net.client.packet.LoadMapPacket;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.CompactMovePacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.client.packet.PlayerDeathPacket;
import com.openrealm.net.client.packet.TextEffectPacket;
import com.openrealm.net.client.packet.UnloadPacket;
import com.openrealm.net.client.packet.PartyUpdatePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.entity.NetPartyMember;
import com.openrealm.net.entity.NetStats;
import com.openrealm.net.party.PartyManager;

import com.openrealm.net.entity.NetTile;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.ClientSession;
import com.openrealm.net.server.CombatMath;
import com.openrealm.net.server.NioServer;
import com.openrealm.net.server.ServerAbilityHelper;
import com.openrealm.net.server.ServerCombatHelper;
import com.openrealm.net.server.ServerCommandHandler;
import com.openrealm.net.server.ServerGameLogic;
import com.openrealm.net.server.ServerPassiveTickHelper;
import com.openrealm.net.server.ServerTradeManager;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.HeartbeatPacket;
import com.openrealm.net.server.packet.ConsumeShardStackPacket;
import com.openrealm.net.server.packet.ForgeDisenchantPacket;
import com.openrealm.net.server.packet.ForgeEnchantPacket;
import com.openrealm.net.server.packet.InteractTilePacket;
import com.openrealm.net.server.packet.BuyFameItemPacket;
import com.openrealm.net.server.packet.MoveItemPacket;
import com.openrealm.net.server.packet.PlayerMovePacket;
import com.openrealm.net.server.packet.PotionStorageMovePacket;
import com.openrealm.net.server.packet.SplitStackPacket;
import com.openrealm.net.server.packet.PlayerShootPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.net.server.packet.UseAbilityPacket;
import com.openrealm.net.server.packet.UsePortalPacket;
import com.openrealm.net.client.packet.GlobalPlayerPositionPacket;
import com.openrealm.net.client.packet.PlayerPosAckPacket;
import com.openrealm.net.client.packet.PlayerStatePacket;
import com.openrealm.net.entity.NetPlayerPosition;
import com.openrealm.net.server.WebSocketGameServer;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.MapModel;
import com.openrealm.util.AdminRestrictedCommand;
import com.openrealm.util.CommandHandler;
import com.openrealm.util.PacketHandlerServer;
import com.openrealm.util.TimedWorkerThread;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
@EqualsAndHashCode(callSuper = false)
@SuppressWarnings("unused")
public class RealmManagerServer implements Runnable {
	
	private NioServer server;
	private boolean shutdown = false;
	private Reflections classPathScanner = new Reflections("com.openrealm", Scanners.SubTypes, Scanners.MethodsAnnotated);
	private MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
	private final Map<Class<? extends Packet>, BiConsumer<RealmManagerServer, Packet>> packetCallbacksServer = new HashMap<>();
	private final Map<Byte, List<MethodHandle>> userPacketCallbacksServer = new HashMap<>();

	private List<Vector2f> shotDestQueue = new ArrayList<>();
	private Map<Long, Realm> realms = new ConcurrentHashMap<>();
	private Map<String, Long> remoteAddresses = new ConcurrentHashMap<>();

	// Worker threads enqueue here; the tick thread drains at the top of each
	// tick so realm mutation stays single-threaded.
	private final ConcurrentLinkedQueue<PendingRealmJoin> pendingRealmJoins = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<PendingRealmTransition> pendingRealmTransitions = new ConcurrentLinkedQueue<>();

	private Map<Long, Map<Long, UpdatePacket>> otherPlayerUpdateState = new ConcurrentHashMap<>();
	private Map<Long, Long> playerAbilityState = new ConcurrentHashMap<>();
	private Map<Long, PlayerLoadLedger> playerLoadLedger = new ConcurrentHashMap<>();
	private final PartyManager partyManager = new PartyManager();

	private Map<Long, Long> lastBulletUpdateNanos = new ConcurrentHashMap<>();
	private Map<Long, Long> playerLastFullSnapshotMs = new ConcurrentHashMap<>();
	private Map<Long, Long> lastViewerUpdateRefreshMs = new ConcurrentHashMap<>();
	private static final long VIEWER_UPDATE_REFRESH_MS = 2000L;
	private static final long FULL_SNAPSHOT_INTERVAL_MS = 2000L;

	private Map<Long, UpdatePacket> playerUpdateState = new ConcurrentHashMap<>();
	private Map<Long, PlayerStatePacket> playerStateState = new ConcurrentHashMap<>();

	public void invalidatePlayerStateCache(long playerId) {
		this.playerStateState.remove(playerId);
	}

	private Map<Long, UpdatePacket> enemyUpdateState = new ConcurrentHashMap<>();
	private Map<Long, UnloadPacket> playerUnloadState = new ConcurrentHashMap<>();
	private Map<Long, LoadMapPacket> playerLoadMapState = new ConcurrentHashMap<>();
	private Map<Long, ObjectMovePacket> playerObjectMoveState = new ConcurrentHashMap<>();
	private Map<Long, Map<Long, EntityMotionState>> playerDeadReckonState = new ConcurrentHashMap<>();
	private Map<Long, Long> playerGroundDamageState = new ConcurrentHashMap<>();
	private Map<Long, Long> playerLastHeartbeatTime = new ConcurrentHashMap<>();
	private Map<Long, NetPlayerPosition[]> lastGlobalPositions = new ConcurrentHashMap<>();

	private UnloadPacket lastUnload;
	private volatile Queue<Packet> outboundPacketQueue = new ConcurrentLinkedQueue<>();
	private volatile Map<Long, ConcurrentLinkedQueue<Packet>> playerOutboundPacketQueue = new ConcurrentHashMap<>();
	private List<RealmDecoratorBase> realmDecorators = new ArrayList<>();
	private List<EnemyScriptBase> enemyScripts = new ArrayList<>();
	private List<UseableItemScriptBase> itemScripts = new ArrayList<>();
	private final ReentrantLock realmLock = new ReentrantLock();
	private int currentTickCount = 0;
	private long tickSampleTime = 0;
	private long tickTimeAccumNanos = 0L;
	private int tickCounter = 0;

	// Power-of-2 divisors used as bitmasks against tickCounter. Changing one
	// must preserve the (n & (DIVISOR - 1)) idiom in the consumers below.
	private static final int MOVE_TICK_DIVISOR = 2;
	private static final int MOVE_FULL_TICK_DIVISOR = 4;
	private static final int LOAD_TICK_DIVISOR = 2;
	private static final int UPDATE_TICK_DIVISOR = 8;
	private static final int LOADMAP_TICK_DIVISOR = 12;
	private static final int ENEMY_UPDATE_TICK_DIVISOR = 4;
	private static final int ENEMY_AI_TICK_DIVISOR = 2;
	private static final int ENEMY_MOVE_FAR_DIVISOR = 4;
	private static final float VIEWPORT_RADIUS_SQ =
		(10f * GlobalConstants.BASE_TILE_SIZE) * (10f * GlobalConstants.BASE_TILE_SIZE);

	private static final int MAX_ENEMY_BULLETS_PER_REALM = 10000;
	private static final int MAX_NEW_ENEMIES_PER_LOAD = 500;
	private static final int MAX_NEW_BULLETS_PER_LOAD = 1000;
	private static final float MAX_AWAKE_RADIUS = 720f;

	private static final int PROX_DORMANT = 0;
	private static final int PROX_AWAKE = 1;
	private static final int PROX_VISIBLE = 2;

	private static final float PHALANX_RADIUS = 128f;
	private static final float PHALANX_RADIUS_SQ = PHALANX_RADIUS * PHALANX_RADIUS;

	private static final int MAX_PACKETS_PER_TICK = 200;
	private static final Set<Class<? extends Packet>> PRIORITY_PACKETS = Set.of(
			PlayerShootPacket.class,
			PlayerMovePacket.class,
			HeartbeatPacket.class,
			CommandPacket.class
	);

	private static final long TICK_BUDGET_NANOS = 16_000_000L;
	private long lastSlowTickLogMs = 0L;
	private long updatePlayersNanos = 0L;
	private long updateEnemiesNanos = 0L;
	private long updateBulletsNanos = 0L;
	private long updateGlobalNanos = 0L;

	private boolean  isSetup = false;

	private long lastWriteSampleTime = Instant.now().toEpochMilli();
	private final AtomicLong bytesWritten = new AtomicLong(0);
	private final ConcurrentHashMap<String, AtomicLong> bytesWrittenByPacketType = new ConcurrentHashMap<>();
	private final AtomicLong bytesRead = new AtomicLong(0);
	private final ConcurrentHashMap<String, AtomicLong> bytesReadByPacketType = new ConcurrentHashMap<>();

	public RealmManagerServer() { }

	public void doRunServer() {
		ServerTradeManager.mgr = this;
		this.doSetup();
		WorkerThread.submitAndForkRun(this.server);
		WorkerThread.submitAndForkRun(this);
	}
	
	private void doSetup() {
		if (this.isSetup) {
			log.warn("[SERVER] Server is already setup, ignoring extra call");
			return;
		}
		this.server = new NioServer(2222);
		this.startWebSocketServer();
		this.registerRealmDecorators();
		this.registerEnemyScripts();
		this.registerPacketCallbacks();
		this.registerPacketCallbacksReflection();
		this.registerItemScripts();
		this.registerCommandHandlersReflection();
		this.beginPlayerSync();

		final Realm realm = this.createEntryRealm();
		realm.spawnRandomEnemies(realm.getMapId());
		this.placeSetPiecesIfTerrainMap(realm);
		this.addRealm(realm);
		Runtime.getRuntime().addShutdownHook(this.shutdownHook());
		this.isSetup = true;
	}

	private void startWebSocketServer() {
		try {
			final WebSocketGameServer wsServer = new WebSocketGameServer(2223, this.server);
			wsServer.start();
			log.info("[SERVER] WebSocket server started on port 2223");
		} catch (Exception e) {
			log.error("[SERVER] Failed to start WebSocket server: {}", e.getMessage());
		}
	}

	private Realm createEntryRealm() {
		final DungeonGraphNode entryNode = GameDataManager.getEntryNode();
		try {
			if (entryNode != null) {
				log.info("[SERVER] Creating realm for entry node: {} (mapId={})", entryNode.getNodeId(), entryNode.getMapId());
				return new Realm(true, entryNode.getMapId(), entryNode.getNodeId());
			}
			log.warn("[SERVER] No dungeon graph entry node found, falling back to mapId=2");
			return new Realm(true, 2);
		} catch (Exception e) {
			log.error("[SERVER] Failed to create entry realm (mapId={}). Falling back to mapId=2. Reason: {}",
					entryNode != null ? entryNode.getMapId() : "null", e.getMessage(), e);
			return new Realm(true, 2);
		}
	}

	private void placeSetPiecesIfTerrainMap(final Realm realm) {
		final var entryMapModel = GameDataManager.MAPS.get(realm.getMapId());
		if (entryMapModel == null || entryMapModel.getTerrainId() < 0 || GameDataManager.TERRAINS == null) {
			log.info("[SERVER] Static map (mapId={}), skipping set piece placement", realm.getMapId());
			return;
		}
		TerrainGenerationParameters terrainParams = GameDataManager.TERRAINS.get(entryMapModel.getTerrainId());
		if (terrainParams == null) terrainParams = GameDataManager.TERRAINS.get(0);
		if (terrainParams == null || terrainParams.getSetPieces() == null) return;
		log.info("[SERVER] Placing set pieces for terrain '{}' ({} types defined)",
				terrainParams.getName(), terrainParams.getSetPieces().size());
		realm.placeSetPieces(terrainParams);
	}

	public void spawnTestPlayers(final long realmId, final int count, final Vector2f pos) {
		final Realm targetRealm = this.realms.get(realmId);
		final Runnable spawnTestPlayers = () -> {
			final Random random = Realm.RANDOM;
			for (int i = 0; i < count; i++) {
				final CharacterClass classToSpawn = CharacterClass.getCharacterClasses()
						.get(random.nextInt(CharacterClass.getCharacterClasses().size()));
				try {
					final Vector2f spawnPos = pos.clone(50, 50);
					final Player player = new Player(Realm.RANDOM.nextLong(), spawnPos, GlobalConstants.PLAYER_SIZE,
							classToSpawn);
					String playerName = UUID.randomUUID().toString().replaceAll("-", "");
					playerName = playerName.substring(playerName.length() / 2);
					player.setName(playerName);
					player.equipSlots(GameDataManager.getStartingEquipment(classToSpawn));
					player.setCharacterUuid(UUID.randomUUID().toString());
					player.setAccountUuid(UUID.randomUUID().toString());

					player.setDy(random.nextBoolean() ? -random.nextFloat() : random.nextFloat());
					player.setDx(random.nextBoolean() ? random.nextFloat() : -random.nextFloat());
					Thread.sleep(100);
					player.setHeadless(true);
					targetRealm.addPlayer(player);
				} catch (Exception e) {
					log.error("Failed to spawn test character of class type {}. Reason: {}", classToSpawn, e);
				}
			}
		};
		WorkerThread.submitAndForkRun(spawnTestPlayers);
	}

	@Override
	public void run() {
		log.info("[SERVER] Starting OpenRealm Server");
		final TimedWorkerThread workerThread = new TimedWorkerThread(this::tick, 64);
		WorkerThread.submitAndForkRun(workerThread);
		log.info("[SERVER] RealmManagerServer exiting run().");
	}

	private void tick() {
		final long tickStart = System.nanoTime();
		long tJoins = 0, tTransitions = 0, tPackets = 0, tUpdate = 0, tEnqueue = 0, tSend = 0, tOverseer = 0;
		try {
			long t0 = System.nanoTime();
			this.processPendingJoins();
			tJoins = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.processPendingTransitions();
			tTransitions = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.processServerPackets();
			tPackets = System.nanoTime() - t0;

			// update() MUST run before enqueueGameData() so enemy bullets spawned
			// in Enemy.update() land in the spatial grid before LoadPacket is built.
			t0 = System.nanoTime();
			this.update(0);
			tUpdate = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.enqueueGameData();
			tEnqueue = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.sendGameData();
			tSend = System.nanoTime() - t0;

			t0 = System.nanoTime();
			for (final Realm realm : this.realms.values()) {
				if (realm.getOverseer() != null) realm.getOverseer().tick();
			}
			tOverseer = System.nanoTime() - t0;
		} catch (Exception e) {
			log.error("Failed to process server tick", e);
		}
		final long tickTotal = System.nanoTime() - tickStart;
		this.currentTickCount++;
		this.tickTimeAccumNanos += tickTotal;
		
		final long nowMs = Instant.now().toEpochMilli();
		if (nowMs - this.tickSampleTime >= 1000) {
			this.tickSampleTime += 1000;
			if (nowMs - this.tickSampleTime >= 1000) {
				this.tickSampleTime = nowMs;
			}
			final double avgMs = this.currentTickCount > 0
				? (this.tickTimeAccumNanos / (double) this.currentTickCount) / 1_000_000.0
				: 0.0;
			log.info("[SERVER] ticks this second: {} (avg tick time: {} ms)",
				this.currentTickCount, String.format("%.2f", avgMs));
			this.currentTickCount = 0;
			this.tickTimeAccumNanos = 0L;
		}
		if (tickTotal > TICK_BUDGET_NANOS) {
			final long slowLogNowMs = System.currentTimeMillis();
			if (slowLogNowMs - lastSlowTickLogMs >= 1000) {
				lastSlowTickLogMs = slowLogNowMs;
				int totalEnemies = 0, totalBullets = 0, totalPlayers = 0;
				for (Realm r : this.realms.values()) {
					totalEnemies += r.getEnemies().size();
					totalBullets += r.getBullets().size();
					totalPlayers += r.getPlayers().size();
				}
				log.warn("[SERVER] slow tick: total={}ms (joins={}, trans={}, pkts={}, update={}[plyrs={},enems={},blts={},global={}], enqueue={}, send={}, overseer={}) — realms={}, players={}, enemies={}, bullets={}",
					tickTotal / 1_000_000,
					tJoins / 1_000_000, tTransitions / 1_000_000, tPackets / 1_000_000,
					tUpdate / 1_000_000,
					this.updatePlayersNanos / 1_000_000, this.updateEnemiesNanos / 1_000_000,
					this.updateBulletsNanos / 1_000_000, this.updateGlobalNanos / 1_000_000,
					tEnqueue / 1_000_000, tSend / 1_000_000, tOverseer / 1_000_000,
					this.realms.size(), totalPlayers, totalEnemies, totalBullets);
			}
		}
	}



	private void sendGameData() {
		final long startNanos = System.nanoTime();
		final List<Packet> packetsToBroadcast = this.drainBroadcastQueue();
		this.reapStaleSessions();
		this.deliverPacketsToSessions(packetsToBroadcast);
		this.logBandwidthIfDue();
		final long nanosDiff = System.nanoTime() - startNanos;
		log.debug("Game data broadcast in {} nanos ({}ms}", nanosDiff, ((double) nanosDiff / 1_000_000.0));
	}

	private List<Packet> drainBroadcastQueue() {
		final List<Packet> packets = new ArrayList<>();
		while (!this.outboundPacketQueue.isEmpty()) {
			packets.add(this.outboundPacketQueue.remove());
		}
		return packets;
	}

	private void reapStaleSessions() {
		final List<Map.Entry<String, ClientSession>> stale = new ArrayList<>();
		for (final Map.Entry<String, ClientSession> client : this.server.getClients().entrySet()) {
			if (!client.getValue().isConnected() || client.getValue().isShutdownProcessing()) {
				stale.add(client);
			}
		}
		for (final Map.Entry<String, ClientSession> entry : stale) {
			try {
				this.reapStaleSession(entry);
			} catch (Exception e) {
				log.error("[SERVER] Failed to remove stale session. Reason: {}", e);
			}
		}
	}

	private void reapStaleSession(final Map.Entry<String, ClientSession> entry) {
		final ClientSession session = entry.getValue();
		final String staleReason = !session.isConnected()
				? "connection lost (isConnected=false)"
				: "shutdownProcessing flag already set";
		session.setShutdownProcessing(true);

		final Long dcPlayerId = this.remoteAddresses.get(entry.getKey());
		if (dcPlayerId == null) {
			log.info("[SERVER] Cleaning up stale session {} (no mapped player) — reason: {}", entry.getKey(), staleReason);
		} else {
			final Realm playerRealm = this.findPlayerRealm(dcPlayerId);
			if (playerRealm != null) {
				final Player dcPlayer = playerRealm.getPlayer(dcPlayerId);
				if (dcPlayer != null) {
					log.info("[SERVER] Cleaning up stale session for player {} — reason: {}", dcPlayer.getName(), staleReason);
					this.saveVaultAndStorageOnDisconnect(playerRealm, dcPlayer);
					if (playerRealm.getMapId() == Realm.VAULT_MAP_ID) {
						playerRealm.setShutdown(true);
						this.realms.remove(playerRealm.getRealmId());
					}
					this.persistPlayerAsync(dcPlayer);
					playerRealm.getExpiredPlayers().add(dcPlayerId);
					playerRealm.removePlayer(dcPlayer);
				}
			} else {
				log.info("[SERVER] Cleaning up stale session {} (no player in realm) — reason: {}", entry.getKey(), staleReason);
			}
			this.clearPlayerState(dcPlayerId);
			this.cleanupPartyOnDisconnect(dcPlayerId);
			this.remoteAddresses.remove(entry.getKey());
		}
		session.close();
		this.server.getClients().remove(entry.getKey());
	}

	private void saveVaultAndStorageOnDisconnect(final Realm playerRealm, final Player dcPlayer) {
		if (playerRealm.getMapId() != Realm.VAULT_MAP_ID) return;
		try {
			final String acctUuid = dcPlayer.getAccountUuid();
			final String dcName = dcPlayer.getName();
			final List<ChestDto> chestsToSave = playerRealm.serializeChestsForSave();
			if (chestsToSave != null) {
				ServerGameLogic.DATA_SERVICE
						.executePostAsync("/data/account/" + acctUuid + "/chest", chestsToSave, PlayerAccountDto.class)
						.thenAccept(resp -> log.info("[SERVER] Saved vault chests for DC'd player {}", dcName))
						.exceptionally(ex -> {
							log.error("[SERVER] Failed to save vault on DC for {}. Reason: {}", dcName, ex.getMessage());
							return null;
						});
			}
			final List<ChestDto> storageToSave = playerRealm.serializePotionStorageForSave(dcPlayer.getId());
			if (storageToSave != null) {
				ServerGameLogic.DATA_SERVICE
						.executePostAsync("/data/account/" + acctUuid + "/potion-storage", storageToSave, PlayerAccountDto.class)
						.thenAccept(resp -> log.info("[SERVER] Saved potion storage for DC'd player {}", dcName))
						.exceptionally(ex -> {
							log.error("[SERVER] Failed to save potion storage on DC for {}. Reason: {}", dcName, ex.getMessage());
							return null;
						});
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to save vault on DC for {}. Reason: {}", dcPlayer.getName(), e.getMessage());
		}
	}

	private void deliverPacketsToSessions(final List<Packet> packetsToBroadcast) {
		for (final Map.Entry<String, ClientSession> client : this.server.getClients().entrySet()) {
			try {
				final ClientSession session = client.getValue();
				if (session.getSharedBytesWritten() == null) {
					session.setSharedBytesWritten(this.bytesWritten);
					session.setSharedBytesPerType(this.bytesWrittenByPacketType);
					session.setSharedBytesRead(this.bytesRead);
					session.setSharedBytesReadPerType(this.bytesReadByPacketType);
				}
				final Player player = this.getPlayerByRemoteAddress(client.getKey());
				if (player == null) continue;
				for (final Packet packet : packetsToBroadcast) {
					session.enqueuePacket(packet);
				}
				final ConcurrentLinkedQueue<Packet> queued = this.playerOutboundPacketQueue.get(player.getId());
				if (queued != null) {
					Packet packet;
					while ((packet = queued.poll()) != null) {
						session.enqueuePacket(packet);
					}
				}
			} catch (Exception e) {
				// swallow — connection-state churn races are normal here
			}
		}
	}

	private void logBandwidthIfDue() {
		if (Instant.now().toEpochMilli() - this.lastWriteSampleTime <= 1000) return;
		this.lastWriteSampleTime = Instant.now().toEpochMilli();
		final long written = this.bytesWritten.getAndSet(0);
		final long read = this.bytesRead.getAndSet(0);
		log.info("[SERVER] current write rate = {} kbit/s (wire), read rate = {} kbit/s (wire)",
				(float) (written / 1024.0f) * 8.0f,
				(float) (read / 1024.0f) * 8.0f);
		log.info(formatPerTypeRates("[SERVER] Outbound by packet type: ", this.bytesWrittenByPacketType));
		log.info(formatPerTypeRates("[SERVER] Inbound by packet type:  ", this.bytesReadByPacketType));
	}

	private static String formatPerTypeRates(final String prefix,
			final ConcurrentHashMap<String, AtomicLong> counters) {
		final StringBuilder sb = new StringBuilder(prefix);
		for (final var entry : counters.entrySet()) {
			final long typeBytes = entry.getValue().getAndSet(0);
			if (typeBytes > 0) {
				sb.append(entry.getKey()).append("=")
				  .append(String.format("%.1f", (typeBytes / 1024.0f) * 8.0f))
				  .append("kbit/s ");
			}
		}
		return sb.toString();
	}

	// Enqueues outbound game packets every tick using:
	// - Spatial hash grid for O(1) neighbor lookups
	// - Tiered update rates (movement=64Hz, load=32Hz, update=16Hz, map=4Hz)
	// Tick scheduling flags resolved once per enqueueGameData() call and
	// passed down to per-player helpers so each helper makes the same gating
	// decision the original inline loop did.
	private static final class TickSchedule {
		final boolean doMovement;
		final boolean doFullMovement;
		final boolean doLoad;
		final boolean doUpdate;
		final boolean doLoadMap;
		final boolean doEnemyUpdate;
		TickSchedule(int tc) {
			this.doMovement     = (tc % MOVE_TICK_DIVISOR) == 0;
			this.doFullMovement = (tc % MOVE_FULL_TICK_DIVISOR) == 0;
			this.doLoad         = (tc % LOAD_TICK_DIVISOR) == 0;
			this.doUpdate       = (tc % UPDATE_TICK_DIVISOR) == 0;
			this.doLoadMap      = (tc % LOADMAP_TICK_DIVISOR) == 0;
			this.doEnemyUpdate  = (tc % ENEMY_UPDATE_TICK_DIVISOR) == 0;
		}
	}

	public void enqueueGameData() {
		final long startNanos = System.nanoTime();
		this.acquireRealmLock();
		try {
			this.tickCounter++;
			final TickSchedule sched = new TickSchedule(this.tickCounter);
			for (final Realm realm : this.realms.values()) {
				this.enqueueRealmGameData(realm, sched);
			}
			final long nanosDiff = System.nanoTime() - startNanos;
			log.debug("[SERVER] Game data enqueued in {} nanos ({}ms)", nanosDiff,
					((double) nanosDiff / 1_000_000.0));
		} catch (Exception e) {
			log.error("[SERVER] Failed to enqueue game data. Reason: {}", e.getMessage(), e);
		} finally {
			this.releaseRealmLock();
		}
	}

	private void enqueueRealmGameData(Realm realm, final TickSchedule sched) {
		realm.updateSpatialGrid();
		realm.clearTickMovementCache();
		realm.clearTickStrippedUpdateCache();

		final float viewportRadius = 10 * GlobalConstants.BASE_TILE_SIZE;
		final Set<Long> teleportedPlayers = snapshotTeleportedPlayers(realm);
		final Map<Player, String> toRemoveReasons = new LinkedHashMap<>();

		for (final Player player : realm.getPlayers().values()) {
			if (player.isHeadless()) continue;
			try {
				final Realm live = this.findPlayerRealm(player.getId());
				if (live == null) continue;
				this.enqueuePlayerGameData(live, player, sched, viewportRadius,
						teleportedPlayers, toRemoveReasons);
			} catch (Exception e) {
				log.error("[SERVER] Failed to build game data for Player {}. Reason: {}",
						player.getId(), e);
			}
		}

		for (final Map.Entry<Player, String> entry : toRemoveReasons.entrySet()) {
			this.disconnectPlayer(entry.getKey(), entry.getValue());
		}
		// Reset AFTER all players processed; clearing inside the loop made player B miss updates.
		for (final LootContainer lc : realm.getLoot().values()) {
			lc.setContentsChanged(false);
		}
	}

	private static Set<Long> snapshotTeleportedPlayers(final Realm realm) {
		final Set<Long> teleported = new HashSet<>();
		for (final Player tp : realm.getPlayers().values()) {
			if (tp.getTeleported()) teleported.add(tp.getId());
		}
		return teleported;
	}

	private void enqueuePlayerGameData(final Realm realm, final Player player, final TickSchedule sched,
			final float viewportRadius, final Set<Long> teleportedPlayers,
			final Map<Player, String> toRemoveReasons) throws Exception {
		final long playerId = player.getId();
		final Vector2f playerCenter = player.getPos();

		if (sched.doLoadMap)  this.enqueueLoadMapDiff(realm, player);
		if (sched.doUpdate)   this.enqueueSelfAndPeerUpdates(realm, player, viewportRadius);
		this.enqueuePlayerStateIfChanged(player, sched.doUpdate);
		if (sched.doLoad)     this.enqueueLoadAndUnloadDeltas(realm, player, playerCenter, viewportRadius);
		this.enqueuePosAckIfNeeded(player, teleportedPlayers);
		if (sched.doMovement) this.enqueueMovementAndEnemyUpdates(realm, player, viewportRadius, sched, teleportedPlayers);
		this.maybeReapOnHeartbeatTimeout(player, toRemoveReasons);
	}

	private void enqueueLoadMapDiff(final Realm realm, final Player player) throws Exception {
		final long pid = player.getId();
		final NetTile[] netTilesForPlayer = realm.getTileManager().getLoadMapTiles(player);
		final LoadMapPacket fresh = LoadMapPacket.from(realm.getRealmId(),
				(short) realm.getMapId(), realm.getTileManager().getMapWidth(),
				realm.getTileManager().getMapHeight(), netTilesForPlayer);
		final LoadMapPacket prev = this.playerLoadMapState.get(pid);
		if (prev == null) {
			this.playerLoadMapState.put(pid, fresh);
			this.enqueueServerPacket(player, fresh);
			return;
		}
		if (prev.equals(fresh)) return;
		final LoadMapPacket diff = prev.difference(fresh);
		this.playerLoadMapState.put(pid, fresh);
		if (diff != null) this.enqueueServerPacket(player, diff);
	}

	private void enqueueSelfAndPeerUpdates(final Realm realm, final Player player, final float viewportRadius) {
		final long pid = player.getId();
		final UpdatePacket selfUpdate = realm.getPlayerAsPacket(pid);
		final UpdatePacket prevSelf = this.playerUpdateState.get(pid);
		if (prevSelf == null || !prevSelf.equals(selfUpdate, false)) {
			this.playerUpdateState.put(pid, selfUpdate);
			this.enqueueServerPacket(player, selfUpdate);
		}

		// Force-refresh the per-viewer peer-update cache periodically so a freshly-loaded
		// viewer that raced a peer's first UpdatePacket can't stay blank for that peer.
		final long nowMs = System.currentTimeMillis();
		final Long lastRefresh = this.lastViewerUpdateRefreshMs.get(pid);
		if (lastRefresh == null || (nowMs - lastRefresh) >= VIEWER_UPDATE_REFRESH_MS) {
			final Map<Long, UpdatePacket> existing = this.otherPlayerUpdateState.get(pid);
			if (existing != null) existing.clear();
			this.lastViewerUpdateRefreshMs.put(pid, nowMs);
		}

		final Player[] otherPlayers = realm.getPlayersInRadiusFast(player.getPos(), viewportRadius);
		final int max = Math.min(otherPlayers.length, 20);
		for (int i = 0; i < max; i++) {
			final Player other = otherPlayers[i];
			if (other.getId() == pid) continue;
			try {
				final UpdatePacket stripped = realm.getOrBuildStrippedUpdate(other);
				if (stripped == null) continue;
				final Map<Long, UpdatePacket> viewerCache = this.otherPlayerUpdateState
						.computeIfAbsent(pid, k -> new ConcurrentHashMap<>());
				final UpdatePacket prev = viewerCache.get(other.getId());
				if (prev == null || !prev.equals(stripped, false)) {
					viewerCache.put(other.getId(), stripped);
					this.enqueueServerPacket(player, stripped);
				}
			} catch (Exception ex) {
				log.error("[SERVER] Failed to build other player UpdatePacket. Reason: {}", ex);
			}
		}
	}

	// Effects ship every tick because they gate client-side movement prediction;
	// HP/MP-only deltas are throttled to the 8Hz update cadence.
	private void enqueuePlayerStateIfChanged(final Player player, final boolean doUpdate) {
		final long pid = player.getId();
		final PlayerStatePacket fresh = PlayerStatePacket.from(player);
		final PlayerStatePacket prev = this.playerStateState.get(pid);
		if (prev == null) {
			this.playerStateState.put(pid, fresh);
			this.enqueueServerPacket(player, fresh);
			return;
		}
		final boolean effectsChanged = !Arrays.equals(prev.getEffectIds(), fresh.getEffectIds());
		if (effectsChanged) {
			this.playerStateState.put(pid, fresh);
			this.enqueueServerPacket(player, fresh);
		} else if (doUpdate && !prev.equalsState(fresh)) {
			this.playerStateState.put(pid, fresh);
			this.enqueueServerPacket(player, fresh);
		}
	}

	// Ledger-based delta sync: caps apply only to LOAD side, so cap-trimmed
	// IDs simply stay out of the ledger and the wire and get picked up on a
	// future tick — they never produce a spurious unload.
	private void enqueueLoadAndUnloadDeltas(final Realm realm, final Player player,
			final Vector2f playerCenter, final float viewportRadius) throws Exception {
		final long pid = player.getId();
		final long nowMs = System.currentTimeMillis();
		PlayerLoadLedger ledger = this.playerLoadLedger.get(pid);
		if (ledger == null) {
			ledger = new PlayerLoadLedger();
			this.playerLoadLedger.put(pid, ledger);
			this.playerLastFullSnapshotMs.put(pid, nowMs);
		}
		final VisibleIds desired = realm.getVisibleIdsCircularFast(playerCenter, viewportRadius, pid);

		final Long lastFull = this.playerLastFullSnapshotMs.get(pid);
		final boolean reconcileDue = lastFull == null || (nowMs - lastFull) >= FULL_SNAPSHOT_INTERVAL_MS;

		final Set<Long> playersToLoad    = setDiff(desired.getPlayers(),    ledger.players);
		final Set<Long> enemiesToLoad    = setDiff(desired.getEnemies(),    ledger.enemies);
		final Set<Long> bulletsToLoad    = setDiff(desired.getBullets(),    ledger.bullets);
		final Set<Long> containersToLoad = setDiff(desired.getContainers(), ledger.containers);
		final Set<Long> portalsToLoad    = setDiff(desired.getPortals(),    ledger.portals);

		final Set<Long> playersToUnload    = setDiff(ledger.players,    desired.getPlayers());
		final Set<Long> enemiesToUnload    = setDiff(ledger.enemies,    desired.getEnemies());
		final Set<Long> bulletsToUnload    = setDiff(ledger.bullets,    desired.getBullets());
		final Set<Long> containersToUnload = setDiff(ledger.containers, desired.getContainers());
		final Set<Long> portalsToUnload    = setDiff(ledger.portals,    desired.getPortals());

		// Re-send loot containers whose contents mutated this tick; client merges in place.
		for (final Long id : desired.getContainers()) {
			if (containersToLoad.contains(id)) continue;
			final LootContainer lc = realm.getLoot().get(id);
			if (lc != null && lc.getContentsChanged()) containersToLoad.add(id);
		}

		if (reconcileDue) {
			playersToLoad.clear();    playersToLoad.addAll(desired.getPlayers());
			enemiesToLoad.clear();    enemiesToLoad.addAll(desired.getEnemies());
			bulletsToLoad.clear();    bulletsToLoad.addAll(desired.getBullets());
			containersToLoad.clear(); containersToLoad.addAll(desired.getContainers());
			portalsToLoad.clear();    portalsToLoad.addAll(desired.getPortals());
			this.playerLastFullSnapshotMs.put(pid, nowMs);
		}

		final Set<Long> cappedEnemyLoad = capByDistance(
				enemiesToLoad, playerCenter, realm.getEnemies(), MAX_NEW_ENEMIES_PER_LOAD);
		final Set<Long> cappedBulletLoad = capBulletsWithPlayerPriority(
				bulletsToLoad, playerCenter, realm.getBullets(), MAX_NEW_BULLETS_PER_LOAD);

		final LoadPacket loadPkt = realm.buildLoadPacketForIds(
				playersToLoad, cappedEnemyLoad, cappedBulletLoad,
				containersToLoad, portalsToLoad, playerCenter);
		final UnloadPacket unloadPkt = UnloadPacket.from(
				playersToUnload.toArray(new Long[0]),
				bulletsToUnload.toArray(new Long[0]),
				enemiesToUnload.toArray(new Long[0]),
				containersToUnload.toArray(new Long[0]),
				portalsToUnload.toArray(new Long[0]));

		if (loadPkt != null && !loadPkt.isEmpty()) {
			this.enqueueServerPacket(player, loadPkt);
		}
		if (unloadPkt.isNotEmpty()) {
			this.enqueueServerPacket(player, unloadPkt);
			for (final Long unloadedEnemy : unloadPkt.getEnemies()) {
				this.enemyUpdateState.remove(unloadedEnemy);
			}
			final Map<Long, UpdatePacket> otherCache = this.otherPlayerUpdateState.get(pid);
			if (otherCache != null) {
				for (final Long unloadedPlayer : unloadPkt.getPlayers()) {
					otherCache.remove(unloadedPlayer);
				}
			}
		}

		ledger.players.addAll(playersToLoad);
		ledger.enemies.addAll(cappedEnemyLoad);
		ledger.bullets.addAll(cappedBulletLoad);
		ledger.containers.addAll(containersToLoad);
		ledger.portals.addAll(portalsToLoad);
		ledger.players.removeAll(playersToUnload);
		ledger.enemies.removeAll(enemiesToUnload);
		ledger.bullets.removeAll(bulletsToUnload);
		ledger.containers.removeAll(containersToUnload);
		ledger.portals.removeAll(portalsToUnload);
	}

	private void enqueuePosAckIfNeeded(final Player player, final Set<Long> teleportedPlayers) {
		final boolean isMoving = player.getDx() != 0 || player.getDy() != 0;
		final boolean periodicIdleAck = !isMoving && (this.tickCounter % 6 == 0);
		if (!(isMoving || teleportedPlayers.contains(player.getId()) || periodicIdleAck)) return;
		this.enqueueServerPacket(player, PlayerPosAckPacket.from(
				player.getLastProcessedInputSeq(), player.getPos().x, player.getPos().y));
	}

	private void enqueueMovementAndEnemyUpdates(final Realm realm, final Player player,
			final float viewportRadius, final TickSchedule sched, final Set<Long> teleportedPlayers) throws Exception {
		final long pid = player.getId();
		final float moveRadius = sched.doFullMovement ? viewportRadius : viewportRadius * 0.5f;
		final ObjectMovePacket movePacket = realm.getGameObjectsAsPacketsCircularFast(player.getPos(), moveRadius);
		if (movePacket != null) {
			this.enqueueDeadReckoningCorrections(player, movePacket, teleportedPlayers);
			if (sched.doEnemyUpdate) {
				this.enqueueEnemyUpdatesForViewer(realm, player, movePacket);
			}
		}
	}

	private void enqueueDeadReckoningCorrections(final Player player,
			final ObjectMovePacket movePacket, final Set<Long> teleportedPlayers) throws Exception {
		final long pid = player.getId();
		Map<Long, EntityMotionState> drState = this.playerDeadReckonState.get(pid);
		if (drState == null) {
			drState = new HashMap<>();
			this.playerDeadReckonState.put(pid, drState);
		}
		final float tickDuration = 1.0f;
		final List<NetObjectMovement> corrections = new ArrayList<>();
		for (final NetObjectMovement m : movePacket.getMovements()) {
			// Local player position arrives via PlayerPosAckPacket; only force-send if teleported.
			if (m.getEntityId() == pid && !teleportedPlayers.contains(pid)) continue;
			final EntityMotionState lastSent = drState.get(m.getEntityId());
			if (lastSent != null && !lastSent.needsUpdate(
					m.getPosX(), m.getPosY(), m.getVelX(), m.getVelY(),
					this.tickCounter, tickDuration)) {
				continue;
			}
			if (lastSent != null) {
				lastSent.markSent(m.getPosX(), m.getPosY(), m.getVelX(), m.getVelY(), this.tickCounter);
			} else {
				drState.put(m.getEntityId(), new EntityMotionState(
						m.getPosX(), m.getPosY(), m.getVelX(), m.getVelY(), this.tickCounter));
			}
			corrections.add(m);
		}
		if (!corrections.isEmpty()) {
			this.enqueueServerPacket(player,
					ObjectMovePacket.from(corrections.toArray(new NetObjectMovement[0])));
		}
	}

	private void enqueueEnemyUpdatesForViewer(final Realm realm, final Player player,
			final ObjectMovePacket movePacket) {
		final long pid = player.getId();
		final PlayerLoadLedger viewerLedger = this.playerLoadLedger.get(pid);
		final Set<Long> nearEnemyIds = new HashSet<>();
		for (final NetObjectMovement m : movePacket.getMovements()) {
			if (m.getEntityType() == EntityType.ENEMY.getEntityTypeId()) {
				nearEnemyIds.add(m.getEntityId());
			}
		}
		for (final Long enemyId : nearEnemyIds) {
			// Ledger is authoritative on what the client has loaded; never push
			// updates for an enemy the viewer doesn't have.
			if (viewerLedger == null || !viewerLedger.enemies.contains(enemyId)) continue;
			final UpdatePacket fresh = realm.getEnemyAsPacket(enemyId);
			final UpdatePacket prev = this.enemyUpdateState.get(enemyId);
			if (prev == null || !prev.equals(fresh, true)) {
				this.enemyUpdateState.put(enemyId, fresh);
				this.enqueueServerPacket(player, fresh);
			}
			final Enemy nearEnemy = realm.getEnemy(enemyId);
			if (nearEnemy == null) continue;
			final PlayerStatePacket enemyState = PlayerStatePacket.from(nearEnemy);
			final PlayerStatePacket cachedEnemyState = this.playerStateState.get(enemyId);
			if (cachedEnemyState == null || !cachedEnemyState.equalsState(enemyState)) {
				this.playerStateState.put(enemyId, enemyState);
				this.enqueueServerPacket(player, enemyState);
			}
		}
	}

	private void maybeReapOnHeartbeatTimeout(final Player player, final Map<Player, String> toRemoveReasons) {
		final Long last = this.playerLastHeartbeatTime.get(player.getId());
		if (last == null) return;
		final long elapsed = Instant.now().toEpochMilli() - last;
		if (elapsed > 60000) {
			toRemoveReasons.put(player, "heartbeat timeout (" + elapsed + "ms since last heartbeat)");
		}
	}

	public void processServerPackets() {
		// Two-pass processing: priority packets first, then everything else (capped)
		final List<Packet> priorityQueue = new ArrayList<>();
		final List<Packet> normalQueue = new ArrayList<>();

		for (final Map.Entry<String, ClientSession> entry : this.getServer().getClients().entrySet()) {
			if (!entry.getValue().isShutdownProcessing()) {
				while (!entry.getValue().getPacketQueue().isEmpty()) {
					final Packet packet = entry.getValue().getPacketQueue().remove();
					// Refresh connect time on any packet activity so pre-login
					// connections aren't killed while auth is still in progress
					this.getServer().getClientConnectTime().put(entry.getKey(), Instant.now().toEpochMilli());
					if (PRIORITY_PACKETS.contains(packet.getClass())) {
						priorityQueue.add(packet);
					} else {
						normalQueue.add(packet);
					}
				}
			} else {
				// `continue` (not `return`) — a stale unmapped session must not block
				// other clients' packet pumps until the dead session ages out.
				final Long dcPlayerId = this.getRemoteAddresses().get(entry.getKey());
				if (dcPlayerId == null) {
					log.info("[SERVER] Cleaning up unmapped stale session {} during packet pump", entry.getKey());
					entry.getValue().close();
					this.server.getClients().remove(entry.getKey());
					continue;
				}
				final Realm playerLocation = this.findPlayerRealm(dcPlayerId);
				if (playerLocation != null) {
					final Player dcPlayer = playerLocation.getPlayer(dcPlayerId);
					if (dcPlayer != null) {
						log.info("[SERVER] Cleaning up disconnected player {} (id={}) during packet pump",
							dcPlayer.getName(), dcPlayerId);
						this.persistPlayerAsync(dcPlayer);
						playerLocation.getExpiredPlayers().add(dcPlayerId);
						playerLocation.getPlayers().remove(dcPlayerId);
					}
				}
				entry.getValue().close();
				this.server.getClients().remove(entry.getKey());
				this.remoteAddresses.remove(entry.getKey());
				this.clearPlayerState(dcPlayerId);
				this.cleanupPartyOnDisconnect(dcPlayerId);
			}
		}

		for (final Packet packet : priorityQueue) {
			processPacket(packet);
		}

		// Overflow packets are re-queued, not dropped — losing inventory ops or
		// trade requests under load made admin recovery impossible at 40+ players.
		final int normalCap = Math.max(0, MAX_PACKETS_PER_TICK - priorityQueue.size());
		final int processed = Math.min(normalQueue.size(), normalCap);
		for (int i = 0; i < processed; i++) {
			processPacket(normalQueue.get(i));
		}
		if (processed < normalQueue.size()) {
			for (int i = processed; i < normalQueue.size(); i++) {
				final Packet overflow = normalQueue.get(i);
				final ClientSession session = this.server.getClients().get(overflow.getSrcIp());
				if (session != null && !session.isShutdownProcessing()) {
					session.getPacketQueue().add(overflow);
				}
			}
		}
	}

	// Reflection handlers take priority and short-circuit — running both paths
	// double-applied any packet that only had a reflection handler registered.
	private void processPacket(final Packet packet) {
		try {
			packet.setSrcIp(packet.getSrcIp());
			final List<MethodHandle> reflectionHandlers = this.userPacketCallbacksServer.get(packet.getId());
			if (reflectionHandlers != null && !reflectionHandlers.isEmpty()) {
				for (MethodHandle handler : reflectionHandlers) {
					try {
						handler.invokeExact(this, packet);
					} catch (Throwable e) {
						log.error("[SERVER] Failed to invoke packet callback. Reason: {}", e);
					}
				}
				return;
			}
			final BiConsumer<RealmManagerServer, Packet> hardcoded =
					this.packetCallbacksServer.get(packet.getClass());
			if (hardcoded != null) {
				hardcoded.accept(this, packet);
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to process packet {}. Reason: {}", packet.getClass().getSimpleName(), e);
		}
	}

	public Map.Entry<String, ClientSession> getPlayerSessionEntry(Player player) {
		Map.Entry<String, ClientSession> result = null;
		for (final Map.Entry<String, ClientSession> client : this.server.getClients().entrySet()) {
			final Long mappedId = this.remoteAddresses.get(client.getKey());
			if (mappedId != null && mappedId == player.getId()) {
				result = client;
			}
		}
		return result;
	}

	public Player getPlayerByRemoteAddress(String remoteAddr) {
		final Long playerId = this.remoteAddresses.get(remoteAddr);
		if (playerId == null) {
			return null;
		}
		final Player found = this.searchRealmsForPlayer(playerId);
		return found;
	}

	public ClientSession getPlayerSession(Player player) {
		final Map.Entry<String, ClientSession> entry = getPlayerSessionEntry(player);
		return entry != null ? entry.getValue() : null;
	}

	public String getPlayerRemoteAddress(Player player) {
		final Map.Entry<String, ClientSession> entry = getPlayerSessionEntry(player);
		return entry != null ? entry.getKey() : null;
	}

	public void disconnectPlayer(Player player, String reason) {
		log.info("[SERVER] Disconnecting Player {} — reason: {}", player.getName(), reason);

		// Step 1: Remove player from realm (most critical — prevents ghost players)
		try {
			final Realm playerRealm = this.findPlayerRealm(player.getId());
			if (playerRealm != null) {
				if (playerRealm.getMapId() == Realm.VAULT_MAP_ID) {
					try {
						// serializeChestsForSave returns null if setupChests
						// hasn't completed; skip the POST so a fast disconnect
						// during vault setup can't bulk-replace and wipe.
						// Async so disconnectPlayer doesn't block the calling
						// thread on the HTTP round-trip.
						final List<ChestDto> chestsToSave = playerRealm.serializeChestsForSave();
						if (chestsToSave != null) {
							final String acctUuid = player.getAccountUuid();
							final String userName = player.getName();
							ServerGameLogic.DATA_SERVICE
									.executePostAsync("/data/account/" + acctUuid + "/chest",
											chestsToSave, PlayerAccountDto.class)
									.thenAccept(resp -> log.info("[SERVER] Saved vault chests for disconnecting player {}", userName))
									.exceptionally(ex -> {
										log.error("[SERVER] Failed to save vault chests on disconnect for {}. Reason: {}",
												userName, ex.getMessage());
										return null;
									});
						}
						final List<ChestDto> storageToSave = playerRealm.serializePotionStorageForSave(player.getId());
						if (storageToSave != null) {
							final String acctUuid = player.getAccountUuid();
							final String userName = player.getName();
							ServerGameLogic.DATA_SERVICE
									.executePostAsync("/data/account/" + acctUuid + "/potion-storage",
											storageToSave, PlayerAccountDto.class)
									.thenAccept(resp -> log.info("[SERVER] Saved potion storage for disconnecting player {}", userName))
									.exceptionally(ex -> {
										log.error("[SERVER] Failed to save potion storage on disconnect for {}. Reason: {}",
												userName, ex.getMessage());
										return null;
									});
						}
					} catch (Exception e) {
						log.error("[SERVER] Failed to save vault chests on disconnect for {}. Reason: {}",
								player.getName(), e.getMessage());
					}
					playerRealm.setShutdown(true);
					this.realms.remove(playerRealm.getRealmId());
				}
				playerRealm.getExpiredPlayers().add(player.getId());
				playerRealm.removePlayer(player);
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to remove player {} from realm. Reason: {}", player.getName(), e);
		}

		// Step 2: Persist player data
		try {
			this.persistPlayerAsync(player);
		} catch (Exception e) {
			log.error("[SERVER] Failed to persist player {} on disconnect. Reason: {}", player.getName(), e);
		}

		// Step 3: Clear player packet/state queues
		try {
			this.clearPlayerState(player.getId());
		} catch (Exception e) {
			log.error("[SERVER] Failed to clear state for player {}. Reason: {}", player.getName(), e);
		}

		// Step 3b: Tear down party membership. Without this, the disconnected
		// player's id leaks in PartyManager forever (Player ids are randomly
		// regenerated per login, so the stale entry is unreachable after
		// reconnect). 2-player parties dissolve via the dissolve-on-1 path;
		// 3-4 player parties just lose this member.
		try {
			this.cleanupPartyOnDisconnect(player.getId());
		} catch (Exception e) {
			log.error("[SERVER] Failed to clean up party state for player {}. Reason: {}", player.getName(), e);
		}

		// Step 4: Close network session and clean up address mappings
		try {
			final Map.Entry<String, ClientSession> sessionEntry = this.getPlayerSessionEntry(player);
			if (sessionEntry != null) {
				sessionEntry.getValue().setShutdownProcessing(true);
				sessionEntry.getValue().close();
				this.server.getClients().remove(sessionEntry.getKey());
				this.remoteAddresses.remove(sessionEntry.getKey());
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to close session for player {}. Reason: {}", player.getName(), e);
		}
	}

	public Realm getTopRealm() {
		final DungeonGraphNode entryNode = GameDataManager.getEntryNode();
		// Prefer the shared overworld realm (the dungeon graph entry node)
		if (entryNode != null) {
			Optional<Realm> entry = this.findRealmForNode(entryNode.getNodeId());
			if (entry.isPresent()) return entry.get();
		}
		// Fallback: any non-vault realm (prefer by nodeId, then any)
		for (final Realm realm : this.realms.values()) {
			if (realm.isOverworld() && realm.getMapId() != Realm.VAULT_MAP_ID) {
				return realm;
			}
		}
		// Last resort: return the first realm that isn't a vault
		for (final Realm realm : this.realms.values()) {
			if (realm.getMapId() != Realm.VAULT_MAP_ID) {
				return realm;
			}
		}
		return null;
	}

	public Portal getClosestPortal(final long realmId, final Vector2f pos, final float limit) {
		float best = Float.MAX_VALUE;
		Portal bestPortal = null;
		final Realm targetRealm = this.realms.get(realmId);
		for (final Portal portal : targetRealm.getPortals().values()) {
			float dist = portal.getPos().distanceTo(pos);
			if ((dist < best) && (dist <= limit)) {
				best = dist;
				bestPortal = portal;
			}
		}
		return bestPortal;
	}

	public Player getClosestPlayer(final long realmId, final Vector2f pos, final float limit) {
		return getClosestPlayer(realmId, pos, limit, false);
	}

	// includeHidden=true bypasses the /hide filter so friendly scripted NPCs
	// (e.g. Enemy67 vault healer) still see admins who toggled /hide on.
	// Hostile enemies should keep the default false so /hide retains its
	// "untargetable by enemies" guarantee.
	public Player getClosestPlayer(final long realmId, final Vector2f pos, final float limit, final boolean includeHidden) {
		final Realm targetRealm = this.realms.get(realmId);
		if (targetRealm == null) return null;
		// Squared distance comparison — avoids 10K+ Math.sqrt() calls per tick
		// at high enemy density (each enemy.update() calls this once).
		final float limitSq = limit * limit;
		float bestSq = limitSq;
		Player bestPlayer = null;
		// Two-pass: prefer the closest TAUNT_TARGET player in range. Only fall
		// back to the closest untaunted player when no taunted target exists.
		// (Knight Taunt — pulls enemy aggro for the buff duration.)
		float bestTauntedSq = limitSq;
		Player bestTaunted = null;
		for (final Player player : targetRealm.getPlayers().values()) {
			if (!includeHidden && player.isHiddenFromOthers()) continue;
			final float dx = player.getPos().x - pos.x;
			final float dy = player.getPos().y - pos.y;
			final float distSq = dx * dx + dy * dy;
			if (player.hasEffect(StatusEffectType.TAUNT_TARGET)) {
				if (distSq < bestTauntedSq) {
					bestTauntedSq = distSq;
					bestTaunted = player;
				}
			}
			if (distSq < bestSq) {
				bestSq = distSq;
				bestPlayer = player;
			}
		}
		if (bestTaunted != null) {
			bestPlayer = bestTaunted;
			bestSq = bestTauntedSq;
		}
		// Decoys still use linear distance (existing API takes float). Pass
		// sqrt of best squared distance so decoys can compete on equal terms.
		final float bestDist = (bestPlayer != null) ? (float) Math.sqrt(bestSq) : limit;
		final Player decoyProxy = targetRealm.getClosestDecoyTarget(pos, bestDist);
		if (decoyProxy != null) {
			bestPlayer = decoyProxy;
		}
		return bestPlayer;
	}

	/**
	 * Find the closest loot container to the given position within the limit.
	 * Legacy overload that ignores soulbound status (shows all loot).
	 */
	public LootContainer getClosestLootContainer(final long realmId, final Vector2f pos, final float limit) {
		return getClosestLootContainer(realmId, pos, limit, -1);
	}

	/**
	 * Find the closest loot container to the given position within the limit,
	 * filtering by soulbound visibility.
	 * 
	 * @param realmId The realm to search in
	 * @param pos The position to search from
	 * @param limit Maximum distance to search
	 * @param playerId The player requesting loot; soulbound loot not belonging
	 *        to this player will be filtered out. Use -1 to show all.
	 */
	public LootContainer getClosestLootContainer(final long realmId, final Vector2f pos, final float limit, final long playerId) {
		float best = Float.MAX_VALUE;
		LootContainer bestLoot = null;
		final Realm targetRealm = this.realms.get(realmId);
		for (final LootContainer lootContainer : targetRealm.getLoot().values()) {
			// Check soulbound visibility: skip if not visible to this player
			if (!lootContainer.isVisibleToPlayer(playerId)) {
				continue;
			}
			float dist = lootContainer.getPos().distanceTo(pos);
			if ((dist < best) && (dist <= limit)) {
				best = dist;
				bestLoot = lootContainer;
			}
		}
		return bestLoot;
	}

	private UnloadPacket getUnloadPacket(long realmId) throws Exception {
		final Realm targetRealm = this.realms.get(realmId);
		final Long[] expiredBullets = targetRealm.getExpiredBullets().toArray(new Long[0]);
		final Long[] expiredEnemies = targetRealm.getExpiredEnemies().toArray(new Long[0]);
		final Long[] expiredPlayers = targetRealm.getExpiredPlayers().toArray(new Long[0]);
		targetRealm.getExpiredPlayers().clear();
		targetRealm.getExpiredBullets().clear();
		targetRealm.getExpiredEnemies().clear();
		final List<Long> lootContainers = targetRealm.getLoot().values().stream()
				.filter(lc -> lc.isExpired() || lc.isEmpty()).map(LootContainer::getLootContainerId)
				.collect(Collectors.toList());
		final List<Long> portals = targetRealm.getPortals().values().stream().filter(Portal::isExpired)
				.map(Portal::getId).collect(Collectors.toList());
		for (final Long lcId : lootContainers) {
			targetRealm.getLoot().remove(lcId);
		}
		for (final Long pId : portals) {
			targetRealm.getPortals().remove(pId);
		}
		return UnloadPacket.from(expiredPlayers, lootContainers.toArray(new Long[0]), expiredBullets, expiredEnemies,
				portals.toArray(new Long[0]));
	}

	public void tryDecorate(final Realm realm) {
		final RealmDecorator decorator = this.getRealmDecorator(realm.getMapId());
		if (decorator != null) {
			decorator.decorate(realm);
		}
	}

	private RealmDecorator getRealmDecorator(int mapId) {
		RealmDecorator result = null;
		for (final RealmDecorator decorator : this.realmDecorators) {
			if (decorator.getTargetMapId() == mapId) {
				result = decorator;
			}
		}
		return result;
	}

	public EnemyScriptBase getEnemyScript(int enemyId) {
		EnemyScriptBase result = null;
		for (final EnemyScriptBase enemyScript : this.enemyScripts) {
			if (enemyScript.getTargetEnemyId() == enemyId) {
				result = enemyScript;
			}
		}
		return result;
	}

	public UseableItemScriptBase getItemScript(int itemId) {
		UseableItemScriptBase result = null;
		for (final UseableItemScriptBase itemScript : this.itemScripts) {
			if (itemScript.handles(itemId)) {
				result = itemScript;
			}
		}
		return result;
	}

	// Register any custom realm type decorators.
	// Each time a realm of the target type is added, an instance
	// of it will be passed to the decorator for post processing
	// (generate static enemies, terrain, events)
	private void registerRealmDecorators() {
		this.registerRealmDecoratorsReflection();
	}

	private void registerRealmDecoratorsReflection() {
		final Set<Class<? extends RealmDecoratorBase>> subclasses = this.classPathScanner
				.getSubTypesOf(RealmDecoratorBase.class);
		for (Class<? extends RealmDecoratorBase> clazz : subclasses) {
			try {
				final RealmDecoratorBase realmDecoratorInstance = clazz.getDeclaredConstructor(RealmManagerServer.class)
						.newInstance(this);
				this.realmDecorators.add(realmDecoratorInstance);
			} catch (Exception e) {
				log.error("[SERVER] Failed to register realm decorator for script {}. Reason: {}", clazz, e.getMessage());
			}
		}
	}

	private void registerEnemyScripts() {
		this.registerEnemyScriptsReflection();
	}

	private void registerEnemyScriptsReflection() {
		final Set<Class<? extends EnemyScriptBase>> subclasses = this.classPathScanner
				.getSubTypesOf(EnemyScriptBase.class);
		for (Class<? extends EnemyScriptBase> clazz : subclasses) {
			try {
				final EnemyScriptBase realmDecoratorInstance = clazz.getDeclaredConstructor(RealmManagerServer.class)
						.newInstance(this);
				this.enemyScripts.add(realmDecoratorInstance);
			} catch (Exception e) {
				log.error("[SERVER] Failed to register enemy script for script {}. Reason: {}", clazz, e.getMessage());
			}
		}
	}

	private void registerItemScripts() {
		this.registerItemScriptsReflection();
	}

	private void registerItemScriptsReflection() {
		final Set<Class<? extends UseableItemScriptBase>> subclasses = this.classPathScanner
				.getSubTypesOf(UseableItemScriptBase.class);
		for (Class<? extends UseableItemScriptBase> clazz : subclasses) {
			try {
				final UseableItemScriptBase realmDecoratorInstance = clazz
						.getDeclaredConstructor(RealmManagerServer.class).newInstance(this);
				this.itemScripts.add(realmDecoratorInstance);
			} catch (Exception e) {
				log.error("[SERVER] Failed to register useable item script for script {}. Reason: {}", clazz, e.getMessage());
			}
		}
	}

	private void registerCommandHandlersReflection() {
		// Target method signature. ex. public static void myMethod(RealmManagerServer,
		// Player, ServerCommandMessage)
		final MethodType mt = MethodType.methodType(void.class, RealmManagerServer.class, Player.class,
				ServerCommandMessage.class);
		final Set<Method> subclasses = this.classPathScanner.getMethodsAnnotatedWith(CommandHandler.class);
		for (final Method method : subclasses) {
			try {
				// Get the annotation on the method
				final CommandHandler commandToHandle = method.getDeclaredAnnotation(CommandHandler.class);
				final AdminRestrictedCommand isAdminRestricted = method
						.getDeclaredAnnotation(AdminRestrictedCommand.class);

				// Find the static method with given name in the target class
				MethodHandle handlerMethod = null;
				try {
					handlerMethod = this.publicLookup.findStatic(ServerCommandHandler.class, method.getName(), mt);
				} catch (Exception e) {
					handlerMethod = this.publicLookup.findStatic(ServerTradeManager.class, method.getName(), mt);
				}
				if (handlerMethod != null) {
					ServerCommandHandler.COMMAND_CALLBACKS.put(commandToHandle.value(), handlerMethod);
					ServerCommandHandler.COMMAND_DESCRIPTIONS.put(commandToHandle.value(), commandToHandle);
					log.info("[SERVER] Registered Command handler in {}. Method: {}{}", method.getDeclaringClass(),
							method.getName(), mt.toString());
					if (isAdminRestricted != null) {
						ServerCommandHandler.RESTRICTED_COMMAND_PROVISIONS.put(commandToHandle.value(), isAdminRestricted.provisions());
						log.info("[SERVER] Command {} registered as restricted (requires {})", commandToHandle.value(),
								Arrays.toString(isAdminRestricted.provisions()));
					}
				}
			} catch (Exception e) {
				log.error("[SERVER] Failed to get MethodHandle to method {}. Reason: {}", method.getName(), e);
			}
		}
	}

	// Registers any user defined packet callbacks with the server
	private void registerPacketCallbacksReflection() {
		log.info("[SERVER] Registering packet handlers using reflection");
		final MethodType mt = MethodType.methodType(void.class, RealmManagerServer.class, Packet.class);

		final Set<Method> subclasses = this.classPathScanner.getMethodsAnnotatedWith(PacketHandlerServer.class);
		for (final Method method : subclasses) {
			try {
				final PacketHandlerServer packetToHandle = method.getDeclaredAnnotation(PacketHandlerServer.class);
				MethodHandle handleToHandler = null;
				try {
					handleToHandler = this.publicLookup.findStatic(ServerGameLogic.class, method.getName(), mt);
				} catch (Exception e) {
					handleToHandler = this.publicLookup.findStatic(ServerTradeManager.class, method.getName(), mt);
				}

				if (handleToHandler != null) {
					final Entry<Byte, Class<? extends Packet>> targetPacketType = PacketType.valueOf(packetToHandle.value());
					List<MethodHandle> existing = this.userPacketCallbacksServer.get(targetPacketType.getKey());
					if (existing == null) {
						existing = new ArrayList<>();
					}
					existing.add(handleToHandler);
					log.info("[SERVER] Added new packet handler for packet {}. Handler method: {}", targetPacketType,
							handleToHandler.toString());
					this.userPacketCallbacksServer.put(targetPacketType.getKey(), existing);
				}
			} catch (Exception e) {
				log.error("[SERVER] Failed to get MethodHandle to method {}. Reason: {}", method.getName(), e);
			}
		}
	}

	// For packet callbacks requiring high performance we will invoke them in a
	// functional manner using hashmap to store the references.
	// The server operator is encouraged to add auxiliary packet handling
	// functionality using the @PacketHandler annotation
	private void registerPacketCallbacks() {
		this.registerPacketCallback(PlayerMovePacket.class, ServerGameLogic::handlePlayerMoveServer);
		this.registerPacketCallback(PlayerShootPacket.class, ServerGameLogic::handlePlayerShootServer);
		this.registerPacketCallback(HeartbeatPacket.class, ServerGameLogic::handleHeartbeatServer);
		this.registerPacketCallback(TextPacket.class, ServerGameLogic::handleTextServer);
		this.registerPacketCallback(CommandPacket.class, ServerGameLogic::handleCommandServer);
		// this.registerPacketCallback(PacketType.LOAD_MAP.getPacketId(),
		// ServerGameLogic::handleLoadMapServer);
		this.registerPacketCallback(UseAbilityPacket.class, ServerGameLogic::handleUseAbilityServer);
		this.registerPacketCallback(MoveItemPacket.class, ServerGameLogic::handleMoveItemServer);
		this.registerPacketCallback(UsePortalPacket.class, ServerGameLogic::handleUsePortalServer);
		this.registerPacketCallback(ConsumeShardStackPacket.class, ServerGameLogic::handleConsumeShardStackServer);
		this.registerPacketCallback(InteractTilePacket.class, ServerGameLogic::handleInteractTileServer);
		this.registerPacketCallback(ForgeEnchantPacket.class, ServerGameLogic::handleForgeEnchantServer);
		this.registerPacketCallback(ForgeDisenchantPacket.class, ServerGameLogic::handleForgeDisenchantServer);
		this.registerPacketCallback(BuyFameItemPacket.class, ServerGameLogic::handleBuyFameItemServer);
		this.registerPacketCallback(PotionStorageMovePacket.class, ServerGameLogic::handlePotionStorageMoveServer);
		this.registerPacketCallback(SplitStackPacket.class, ServerGameLogic::handleSplitStackServer);
	}

	private void registerPacketCallback(final Class<? extends Packet> packetId, final BiConsumer<RealmManagerServer, Packet> callback) {
		this.packetCallbacksServer.put(packetId, callback);
	}

	// Updates all game objects on the server
	public void update(double time) {
		this.updatePlayersNanos = 0L;
		this.updateEnemiesNanos = 0L;
		this.updateBulletsNanos = 0L;
		this.updateGlobalNanos = 0L;

		for (final Realm realm : this.realms.values()) {
			if (realm.getPlayers().isEmpty()) {
				// Skip all processing if no players are present. No need to update that which no one can see
				this.parkIdleRealmEnemies(realm);
				continue;
			}
			this.tickRealmPlayers(realm, time);
			this.tickRealmEnemies(realm, time);
			this.tickRealmBullets(realm);
		}

		final long globalStart = System.nanoTime();
		this.tickGlobal();
		this.updateGlobalNanos += System.nanoTime() - globalStart;
	}

	private void parkIdleRealmEnemies(final Realm realm) {
		if (realm.getEnemies().isEmpty()) return;
		for (final Enemy enemy : realm.getEnemies().values()) {
			if (enemy.getDx() != 0f || enemy.getDy() != 0f) {
				enemy.setDx(0);
				enemy.setDy(0);
			}
		}
	}

	private void tickRealmPlayers(final Realm realm, final double time) {
		final long start = System.nanoTime();
		for (final Player p : realm.getPlayers().values()) {
			final Player live = realm.getPlayer(p.getId());
			if (live == null) continue;
			this.processBulletHit(realm.getRealmId(), live);
			live.update(time);
			live.removeExpiredEffects();
			this.movePlayer(realm.getRealmId(), live);
			this.resolveCompletedCast(realm, live);
		}
		this.updatePlayersNanos += System.nanoTime() - start;
	}

	// Clear currentCast BEFORE re-entering useAbility so the resolution path
	// sees isCasting()==false — otherwise the input-rejection gate at the top
	// of useAbility refuses our own resolution.
	private void resolveCompletedCast(final Realm realm, final Player p) {
		final CastState cs = p.getCurrentCast();
		if (cs == null || cs.getEndTickMs() > Instant.now().toEpochMilli()) return;
		p.setCurrentCast(null);
		this.useAbility(realm.getRealmId(), p.getId(),
				new Vector2f(cs.getWorldTargetX(), cs.getWorldTargetY()),
				(byte) cs.getSlot(), true);
	}

	private void tickRealmEnemies(final Realm realm, final double time) {
		final long start = System.nanoTime();

		final long nowNanos = System.nanoTime();
		final long lastNanos = this.lastBulletUpdateNanos.getOrDefault(realm.getRealmId(), nowNanos);
		final float bulletDt = Math.min((nowNanos - lastNanos) / 1_000_000_000.0f, 0.1f);
		this.lastBulletUpdateNanos.put(realm.getRealmId(), nowNanos);
		realm.setBulletScaleThisTick(bulletDt * 64.0f);

		final int aiTick = this.tickCounter & (ENEMY_AI_TICK_DIVISOR - 1);
		final int moveFarTick = this.tickCounter & (ENEMY_MOVE_FAR_DIVISOR - 1);
		final Player[] activePlayers = realm.getPlayers().values().toArray(new Player[0]);

		final Set<Long> candidates = collectAwakeCandidates(realm, activePlayers);
		for (final long cid : candidates) {
			final Enemy enemy = realm.getEnemies().get(cid);
			if (enemy == null) continue;
			tickAwakeEnemy(realm, enemy, activePlayers, time, aiTick, moveFarTick);
		}
		this.updateEnemiesNanos += System.nanoTime() - start;
	}

	private static Set<Long> collectAwakeCandidates(final Realm realm, final Player[] activePlayers) {
		final Set<Long> candidates = new HashSet<>();
		for (final Player p : activePlayers) {
			candidates.addAll(realm.queryEnemiesNear(p.getPos().x, p.getPos().y, MAX_AWAKE_RADIUS));
		}
		return candidates;
	}

	private void tickAwakeEnemy(final Realm realm, final Enemy enemy, final Player[] activePlayers,
			final double time, final int aiTick, final int moveFarTick) {
		final int classification = classifyEnemyProximity(enemy, activePlayers);
		if (classification == PROX_DORMANT) {
			if (enemy.getDx() != 0f || enemy.getDy() != 0f) {
				enemy.setDx(0);
				enemy.setDy(0);
			}
			return;
		}
		if ((enemy.getId() & (ENEMY_AI_TICK_DIVISOR - 1)) == aiTick) {
			enemy.update(realm.getRealmId(), this, time);
		}
		final boolean visible = classification == PROX_VISIBLE;
		if (visible || (enemy.getId() & (ENEMY_MOVE_FAR_DIVISOR - 1)) == moveFarTick) {
			enemy.tickMove(realm);
		}
		enemy.removeExpiredEffects();
		final EnemyScriptBase tickScript = this.getEnemyScript(enemy.getEnemyId());
		if (tickScript != null) {
			try {
				tickScript.tick(realm, enemy);
			} catch (Exception ex) {
				log.error("Enemy script tick() failed for enemyId={}: {}", enemy.getEnemyId(), ex);
			}
		}
	}

	private static int classifyEnemyProximity(final Enemy enemy, final Player[] activePlayers) {
		final float ex = enemy.getPos().x;
		final float ey = enemy.getPos().y;
		final float chaseRangeSq = (float) enemy.getChaseRange() * enemy.getChaseRange();
		int best = PROX_DORMANT;
		for (final Player p : activePlayers) {
			final float pdx = p.getPos().x - ex;
			final float pdy = p.getPos().y - ey;
			final float dsq = pdx * pdx + pdy * pdy;
			if (dsq <= VIEWPORT_RADIUS_SQ) return PROX_VISIBLE;
			if (dsq <= chaseRangeSq) best = PROX_AWAKE;
		}
		return best;
	}

	private void tickRealmBullets(final Realm realm) {
		final long start = System.nanoTime();
		final float bulletScale = realm.getBulletScaleThisTick();
		for (final Bullet bullet : realm.getBullets().values()) {
			bullet.update(bulletScale);
		}
		this.updateBulletsNanos += System.nanoTime() - start;
	}

	private void tickGlobal() {
		this.removeExpiredBullets();
		this.removeExpiredLootContainers();
		this.removeExpiredPortals();
		for (final Realm realm : this.realms.values()) {
			realm.processPoisonThrows(this);
			realm.processTraps(this);
			realm.processPoisonDots(this);
			realm.processDecoys(this);
			realm.processClones(this);
		}
		ServerPassiveTickHelper.tick(this, this.tickCounter);
		this.tickPartyRefresh();
		this.tickPhalanxDomes();
		this.tickMinimapBroadcast();
		this.tickPeriodicEnemyRespawn();
		this.tickEmptyRealmCleanup();
	}

	private void tickPartyRefresh() {
		if (this.tickCounter % 32 != 0) return;
		final Set<Long> disbandedInviters = this.partyManager.evictExpiredInvites();
		for (final Long inviterId : disbandedInviters) {
			final Player p = this.getPlayerById(inviterId);
			if (p != null) this.sendEmptyPartyUpdate(p);
		}
		final Set<Long> sent = new HashSet<>();
		for (final Player p : this.getPlayers()) {
			final long pid = this.partyManager.getPartyId(p.getId());
			if (pid == 0L) continue;
			if (sent.add(pid)) this.broadcastPartyUpdate(pid);
		}
	}

	private void tickPhalanxDomes() {
		final boolean refreshDomeVisual = (this.tickCounter % 12 == 0);
		for (final Realm realm : this.realms.values()) {
			if (realm.getPlayers().isEmpty()) continue;
			for (final Player p : realm.getPlayers().values()) {
				if (!p.hasEffect(StatusEffectType.PHALANX_DOME)) continue;
				tickPhalanxDomeFor(realm, p, refreshDomeVisual);
			}
		}
	}

	private void tickPhalanxDomeFor(final Realm realm, final Player p, final boolean refreshVisual) {
		final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
		final List<Long> killedIds = new ArrayList<>();
		for (final Bullet b : realm.getBullets().values()) {
			if (!b.isEnemy()) continue;
			final float dx = b.getPos().x - pc.x;
			final float dy = b.getPos().y - pc.y;
			if (dx * dx + dy * dy <= PHALANX_RADIUS_SQ) {
				killedIds.add(b.getId());
			}
		}
		for (final long bid : killedIds) {
			final Bullet b = realm.getBullets().get(bid);
			if (b != null) {
				realm.getExpiredBullets().add(bid);
				realm.removeBullet(b);
			}
		}
		if (refreshVisual) {
			this.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_SHIELD_DOME, pc.x, pc.y, PHALANX_RADIUS, (short) 240, (byte) 1));
		}
	}

	private void tickMinimapBroadcast() {
		if (this.tickCounter % 64 != 0) return;
		for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
			final Realm realm = realmEntry.getValue();
			if (realm.getPlayers().isEmpty()) {
				this.lastGlobalPositions.remove(realmEntry.getKey());
				continue;
			}
			final NetPlayerPosition[] positions = realm.getPlayers().values().stream()
					.map(NetPlayerPosition::from)
					.toArray(NetPlayerPosition[]::new);
			if (!positionsChanged(this.lastGlobalPositions.get(realmEntry.getKey()), positions)) continue;
			this.lastGlobalPositions.put(realmEntry.getKey(), positions);
			final GlobalPlayerPositionPacket minimapPacket = GlobalPlayerPositionPacket.from(positions);
			for (final Player p : realm.getPlayers().values()) {
				this.enqueueServerPacket(p, minimapPacket);
			}
		}
	}

	private static boolean positionsChanged(final NetPlayerPosition[] last, final NetPlayerPosition[] curr) {
		if (last == null || last.length != curr.length) return true;
		for (int i = 0; i < curr.length; i++) {
			if (curr[i].getPlayerId() != last[i].getPlayerId()
					|| curr[i].getX() != last[i].getX()
					|| curr[i].getY() != last[i].getY()
					|| curr[i].isTeleportable() != last[i].isTeleportable()) {
				return true;
			}
		}
		return false;
	}

	private void tickPeriodicEnemyRespawn() {
		if (this.tickCounter % 1920 != 0) return;
		for (final Realm realm : this.realms.values()) {
			if (realm.isOverworld() && realm.getMapId() != Realm.VAULT_MAP_ID && !realm.getPlayers().isEmpty()) {
				realm.respawnEnemies(50);
			}
		}
	}

	// Dungeon source realms must outlive their child while the boss-drop exit
	// portal could still teleport players back to them.
	private void tickEmptyRealmCleanup() {
		if (this.tickCounter % 128 != 0) return;
		final Set<Long> referencedAsSource = new HashSet<>();
		for (final Realm r : this.realms.values()) {
			if (r.getSourceRealmId() != 0) referencedAsSource.add(r.getSourceRealmId());
		}
		final List<Long> realmIdsToRemove = new ArrayList<>();
		for (final Map.Entry<Long, Realm> entry : this.realms.entrySet()) {
			final Realm r = entry.getValue();
			if (!r.getPlayers().isEmpty()) continue;
			if (r.getMapId() == Realm.VAULT_MAP_ID) {
				realmIdsToRemove.add(entry.getKey());
			} else if (!r.isShared() && !referencedAsSource.contains(entry.getKey())) {
				realmIdsToRemove.add(entry.getKey());
			}
		}
		for (Long id : realmIdsToRemove) {
			log.info("[SERVER] Cleaning up empty realm {}", id);
			final Realm removed = this.realms.remove(id);
			if (removed != null) removed.setShutdown(true);
		}
	}

	private void movePlayer(final long realmId, final Player p) {
		// If the player is paralyzed, stop them and return.
        if (p.hasEffect(StatusEffectType.PARALYZED)) {
            p.setCurrentVx(0f);
            p.setCurrentVy(0f);
            p.setDx(0); p.setDy(0);
            p.setUp(false);
            p.setDown(false);
            p.setRight(false);
            p.setLeft(false);
            // Still increment lastProcessedInputSeq so acks stay in sync
            if (p.getInputQueue() == null) p.setInputQueue(new ConcurrentLinkedQueue<>());
            while (!p.getInputQueue().isEmpty()) {
                float[] queued = p.getInputQueue().poll();
                p.setLastProcessedInputSeq((int) queued[0]);
            }
            return;
        }

		// Process up to 32 queued inputs this tick so a burst from a high-ping
		// client doesn't leave server position drifting behind for many ticks.
		if (p.getInputQueue() == null) p.setInputQueue(new ConcurrentLinkedQueue<>());
		while (!p.getInputQueue().isEmpty() && (int) p.getInputQueue().peek()[0] <= p.getLastProcessedInputSeq()) {
		    p.getInputQueue().poll();
		}

		final Realm targetRealm = this.realms.get(realmId);
		int processed = 0;
		while (!p.getInputQueue().isEmpty() && processed < 32) {
		    float[] nextInput = p.getInputQueue().poll();
		    p.setCurrentVx(nextInput[1]);
		    p.setCurrentVy(nextInput[2]);
		    p.setLastProcessedInputSeq((int) nextInput[0]);
		    this.applyMovementTick(targetRealm, p);
		    processed++;
		}
		if (processed == 0) {
		    this.applyMovementTick(targetRealm, p);
		}

		if (targetRealm.getTileManager().collidesDamagingTile(p)) {
			final Long lastDamageTime = this.playerGroundDamageState.get(p.getId());
			if (lastDamageTime == null || (Instant.now().toEpochMilli() - lastDamageTime) > 450) {
				int damageToInflict = 30 + Realm.RANDOM.nextInt(15);
				this.sendTextEffectToPlayer(p, TextEffect.DAMAGE, "-" + damageToInflict);
				p.setHealth(p.getHealth() - damageToInflict);
				this.playerGroundDamageState.put(p.getId(), Instant.now().toEpochMilli());
				// Ground damage doesn't go through normal hit pipeline, so call playerDeath explicitly.
				if (p.getDeath()) {
					this.playerDeath(targetRealm, p);
				}
			}
		}
	}

	private void applyMovementTick(final Realm targetRealm, final Player p) {
		float vx = p.getCurrentVx();
		float vy = p.getCurrentVy();

		// Clamp magnitude to 1 so a misbehaving client can't outpace the configured step.
		final float mag = (float) Math.sqrt(vx * vx + vy * vy);
		if (mag > 1.0f) {
		    vx /= mag;
		    vy /= mag;
		}

		float tilesPerSec = 4.0f + 5.6f * (p.getComputedStats().getSpd() / 75.0f);
		if (p.hasEffect(StatusEffectType.SPEEDY)) tilesPerSec *= 1.5f;
		if (p.hasEffect(StatusEffectType.SLOWED)) tilesPerSec *= 0.5f;
		final float spd = tilesPerSec * 32.0f / 64.0f;

		final boolean moving = mag > 0.001f;
		p.setDx(moving ? vx * spd : 0f);
		p.setDy(moving ? vy * spd : 0f);

		p.setLeft (moving && vx < -0.1f);
		p.setRight(moving && vx >  0.1f);
		p.setUp   (moving && vy < -0.1f);
		p.setDown (moving && vy >  0.1f);

		p.setLastInputSeq(p.getLastInputSeq() + 1);

		final float slow = targetRealm.getTileManager().collidesSlowTile(p) ? 3.0f : 1.0f;
		final float effDx = p.getDx() / slow;
		final float effDy = p.getDy() / slow;
		final float origX = p.getPos().x;
		final float origY = p.getPos().y;

		boolean xBlocked = targetRealm.getTileManager().collisionTile(p, effDx, 0)
				|| targetRealm.getTileManager().collidesXLimit(p, effDx)
				|| targetRealm.getTileManager().isVoidTile(p.getPos().clone(p.getSize() / 2, p.getSize() / 2), effDx, 0);
		boolean yBlocked = targetRealm.getTileManager().collisionTile(p, 0, effDy)
				|| targetRealm.getTileManager().collidesYLimit(p, effDy)
				|| targetRealm.getTileManager().isVoidTile(p.getPos().clone(p.getSize() / 2, p.getSize() / 2), 0, effDy);

		if (!xBlocked && !yBlocked && effDx != 0 && effDy != 0) {
			boolean diagBlocked = targetRealm.getTileManager().collisionTile(p, effDx, effDy)
					|| targetRealm.getTileManager().isVoidTile(p.getPos().clone(p.getSize() / 2, p.getSize() / 2), effDx, effDy);
			if (diagBlocked) {
				if (Math.abs(effDx) >= Math.abs(effDy)) yBlocked = true;
				else xBlocked = true;
			}
		}

		if (!xBlocked) { p.xCol = false; p.getPos().x = origX + effDx; }
		else { p.xCol = true; }
		if (!yBlocked) { p.yCol = false; p.getPos().y = origY + effDy; }
		else { p.yCol = true; }
	}

	// Invokes an ability usage server side for the given player at the
	// desired location if applicable. abilityIndex selects which hotbar slot
	// (0..3) was pressed; default 0 maps to the legacy/Q ability so existing
	// right-click clients keep working.
	public void useAbility(final long realmId, final long playerId, final Vector2f pos) {
		this.useAbility(realmId, playerId, pos, (byte) 0);
	}

	public void useAbility(final long realmId, final long playerId, final Vector2f pos, final byte abilityIndex) {
		this.useAbility(realmId, playerId, pos, abilityIndex, false);
	}

	/**
	 * Internal entry point.
	 *
	 * @param isCastResolution true when this invocation is finishing a previously
	 *     started cast (server tick has fired the cast timer). When true we
	 *     bypass the MP-cost / cooldown / cast-gate checks since those were
	 *     already paid at cast-start; the call only runs the effect dispatch.
	 */
	/** Ability resolution — see {@link ServerAbilityHelper#useAbility}. */
	private void useAbility(final long realmId, final long playerId, final Vector2f pos,
			final byte abilityIndex, final boolean isCastResolution) {
		ServerAbilityHelper.useAbility(this, realmId, playerId, pos, abilityIndex, isCastResolution);
	}


	public void removeExpiredBullets() {
		// Use the realm's tickCounter for the lifetime check so we don't run
		// Instant.now().toEpochMilli() once per bullet per tick (~12 K
		// syscalls/sec at 200 bullets in flight).
		final long currentTick = this.tickCounter;
		for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
			final Realm realm = realmEntry.getValue();

			final List<Bullet> toRemove = new ArrayList<>();
			for (final Bullet b : realm.getBullets().values()) {
				if (b.remove(currentTick)) {
					toRemove.add(b);
				}
			}
			toRemove.forEach(bullet -> {
				realm.getExpiredBullets().add(bullet.getId());
				realm.removeBullet(bullet);
			});
		}
	}

	public void removeExpiredLootContainers() {
		for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
			final Realm realm = realmEntry.getValue();

			final List<LootContainer> toRemove = new ArrayList<>();
			for (final LootContainer lc : realm.getLoot().values()) {
				if (lc.isExpired() || lc.isEmpty()) {
					toRemove.add(lc);
				}
			}
			// fight me for using both kinds of loops
			toRemove.forEach(lc -> {
				realm.removeLootContainer(lc);
			});
		}
	}

	public void removeExpiredPortals() {
		for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
			final Realm realm = realmEntry.getValue();

			final List<Portal> toRemove = new ArrayList<>();
			for (final Portal portal : realm.getPortals().values()) {
				if (portal.isExpired()) {
					toRemove.add(portal);
				}
			}
			toRemove.forEach(portal -> {
				realm.removePortal(portal);
			});
		}
	}

	public void processBulletHit(final long realmId, final Player p) {
		final Realm targetRealm = this.realms.get(realmId);
		final Player player = targetRealm.getPlayer(p.getId());
		if (player == null) return;

		// Use spatial grid for O(cells) instead of O(all_entities) brute-force
		final float collisionRadius = 10 * GlobalConstants.BASE_TILE_SIZE;
		final Vector2f center = player.getPos();

		// Collect bullets and enemies near this player using spatial grid
		final List<Bullet> nearbyBullets = new ArrayList<>();
		final List<Enemy> nearbyEnemies = new ArrayList<>();

		if (targetRealm.getSpatialGrid() != null) {
			final float radiusSq = collisionRadius * collisionRadius;
			final List<Long> candidates = targetRealm.getSpatialGrid().queryRadius(center.x, center.y, collisionRadius);
			for (int i = 0; i < candidates.size(); i++) {
				final long id = candidates.get(i);
				final Bullet b = targetRealm.getBullets().get(id);
				if (b != null) {
					float dx = b.getPos().x - center.x, dy = b.getPos().y - center.y;
					if (dx * dx + dy * dy <= radiusSq) nearbyBullets.add(b);
					continue;
				}
				final Enemy e = targetRealm.getEnemies().get(id);
				if (e != null) {
					float dx = e.getPos().x - center.x, dy = e.getPos().y - center.y;
					if (dx * dx + dy * dy <= radiusSq) nearbyEnemies.add(e);
				}
			}
		} else {
			// Fallback: brute-force (only when no spatial grid)
			final Rectangle viewport = targetRealm.getTileManager().getRenderViewPort(player);
			for (final Bullet b : targetRealm.getBullets().values()) {
				if (b.getBounds().intersect(viewport)) nearbyBullets.add(b);
			}
			for (final Enemy e : targetRealm.getEnemies().values()) {
				if (e.getBounds().intersect(viewport)) nearbyEnemies.add(e);
			}
		}

		// Terrain hit must run first so wall-eaten bullets don't apply damage
		// to entities that the bullet would never have reached on the client.
		this.proccessTerrainHit(realmId, p);

		// nearbyBullets was snapshot before proccessTerrainHit; re-check against
		// the live map so we don't apply damage from an already-despawned bullet.
		final Map<Long, Bullet> liveBullets = targetRealm.getBullets();

		List<Bullet> enemyBullets = null;
		List<Bullet> playerBullets = null;
		for (int i = 0; i < nearbyBullets.size(); i++) {
			final Bullet b = nearbyBullets.get(i);
			if (b.isEnemy()) {
				if (enemyBullets == null) enemyBullets = new ArrayList<>(nearbyBullets.size());
				enemyBullets.add(b);
			} else {
				if (playerBullets == null) playerBullets = new ArrayList<>(nearbyBullets.size());
				playerBullets.add(b);
			}
		}

		// Player-bullet collision (enemy bullets hitting player)
		if (enemyBullets != null && !player.hasEffect(StatusEffectType.INVINCIBLE)) {
			for (int i = 0; i < enemyBullets.size(); i++) {
				final Bullet b = enemyBullets.get(i);
				if (!liveBullets.containsKey(b.getId())) continue;
				this.processPlayerHit(realmId, b, player);
			}
		}

		// Bullet-enemy collision (player bullets hitting enemies)
		if (playerBullets != null) {
			for (final Enemy enemy : nearbyEnemies) {
				for (int i = 0; i < playerBullets.size(); i++) {
					final Bullet b = playerBullets.get(i);
					if (!liveBullets.containsKey(b.getId())) continue;
					this.proccessEnemyHit(realmId, b, enemy);
				}
			}
		}
	}

	
	private static Set<Long> setDiff(final Set<Long> a, final Set<Long> b) {
		if (a.isEmpty()) return new HashSet<>();
		if (b.isEmpty()) return new HashSet<>(a);
		final Set<Long> out = new HashSet<>(a.size());
		for (final Long id : a) if (!b.contains(id)) out.add(id);
		return out;
	}

	private static Set<Long> capByDistance(final Set<Long> ids, final Vector2f center,
			final Map<Long, ? extends GameObject> source, final int cap) {
		if (ids.size() <= cap) return ids;
		final List<long[]> ranked = new ArrayList<>(ids.size());
		for (final Long id : ids) {
			final GameObject g = source.get(id);
			if (g == null) continue;
			final float dx = g.getPos().x - center.x;
			final float dy = g.getPos().y - center.y;
			ranked.add(new long[] { id, Float.floatToRawIntBits(dx * dx + dy * dy) });
		}
		ranked.sort((u, v) -> Float.compare(
				Float.intBitsToFloat((int) u[1]),
				Float.intBitsToFloat((int) v[1])));
		final Set<Long> out = new HashSet<>(Math.min(cap, ranked.size()));
		for (int i = 0, n = Math.min(cap, ranked.size()); i < n; i++) {
			out.add(ranked.get(i)[0]);
		}
		return out;
	}

	// Player-owned bullets always fit first so enemy spam can't starve the
	// player's view of their own projectiles.
	private static Set<Long> capBulletsWithPlayerPriority(final Set<Long> ids, final Vector2f center,
			final Map<Long, Bullet> bullets, final int cap) {
		if (ids.size() <= cap) return ids;
		final List<long[]> playerOwned = new ArrayList<>();
		final List<long[]> enemyOwned = new ArrayList<>();
		for (final Long id : ids) {
			final Bullet b = bullets.get(id);
			if (b == null) continue;
			final float dx = b.getPos().x - center.x;
			final float dy = b.getPos().y - center.y;
			final long encoded = Float.floatToRawIntBits(dx * dx + dy * dy);
			(b.isEnemy() ? enemyOwned : playerOwned).add(new long[] { id, encoded });
		}
		final Comparator<long[]> byDist = (u, v) -> Float.compare(
				Float.intBitsToFloat((int) u[1]),
				Float.intBitsToFloat((int) v[1]));
		playerOwned.sort(byDist);
		enemyOwned.sort(byDist);
		final Set<Long> out = new HashSet<>(cap);
		for (int i = 0; i < playerOwned.size() && out.size() < cap; i++) {
			out.add(playerOwned.get(i)[0]);
		}
		for (int i = 0; i < enemyOwned.size() && out.size() < cap; i++) {
			out.add(enemyOwned.get(i)[0]);
		}
		return out;
	}

	public void enqueueServerPacket(final Packet packet) {
		this.outboundPacketQueue.add(packet);
	}

	/**
	 * Send a packet to all non-headless players in a specific realm.
	 * Use this instead of the global broadcast for effects, visuals, etc.
	 * that should only be seen by players in the same realm.
	 */
	public void enqueueServerPacketToRealm(final Realm realm, final Packet packet) {
		if (realm == null || packet == null) return;
		for (final Player p : realm.getPlayers().values()) {
			if (!p.isHeadless()) {
				this.enqueueServerPacket(p, packet);
			}
		}
	}

	public void enqueueServerPacket(final Player player, final Packet packet) {
		if (player == null || packet == null)
			return;
		this.playerOutboundPacketQueue
				.computeIfAbsent(player.getId(), k -> new ConcurrentLinkedQueue<>())
				.add(packet);
	}

	/**
	 * Circle-vs-circle hit test for a bullet against an entity. Centers come from
	 * (pos.x + size/2, pos.y + size/2). Radii are size * HIT_RADIUS_FACTOR. The
	 * client mirrors this exactly in game.js — if you change the formula here,
	 * change it there too.
	 */
	public static boolean circleHit(final Bullet b, final GameObject e) {
		final float br = b.getSize() * GlobalConstants.HIT_RADIUS_FACTOR;
		final float er = e.getSize() * GlobalConstants.HIT_RADIUS_FACTOR;
		final float bcx = b.getPos().x + b.getSize() * 0.5f;
		final float bcy = b.getPos().y + b.getSize() * 0.5f;
		final float ecx = e.getPos().x + e.getSize() * 0.5f;
		final float ecy = e.getPos().y + e.getSize() * 0.5f;
		final float dx = bcx - ecx;
		final float dy = bcy - ecy;
		final float rsum = br + er;
		return (dx * dx + dy * dy) < (rsum * rsum);
	}

	private void proccessTerrainHit(final long realmId, final Player p) {
		final Realm targetRealm = this.realms.get(realmId);

		final List<Bullet> toRemove = new ArrayList<>();
		final TileMap currentMap = targetRealm.getTileManager().getCollisionLayer();
		if (currentMap == null)
			return;
		// Look up the tile under each bullet's center directly. The previous
		// approach iterated an 11x11 grid around the player, which missed
		// bullets 6+ tiles away — the render viewport is 20 tiles wide, so
		// distant bullets passed through walls untouched on the server while
		// the client correctly de-rendered them. Mirrors game.js:1156-1169.
		final Tile[][] blocks = currentMap.getBlocks();
		final int ts = currentMap.getTileSize();
		final int mapW = currentMap.getWidth();
		final int mapH = currentMap.getHeight();
		for (final Bullet b : this.getBullets(realmId, p)) {
			if (b.remove()) {
				toRemove.add(b);
				continue;
			}
			if (b.hasFlag(ProjectileFlag.PASS_THROUGH_TERRAIN)) continue;
			final Vector2f bulletPosCenter = b.getCenteredPosition();
			final int btx = (int) (bulletPosCenter.x / ts);
			final int bty = (int) (bulletPosCenter.y / ts);
			if (btx < 0 || btx >= mapW || bty < 0 || bty >= mapH) continue;
			final Tile tile = blocks[bty][btx];
			if (tile != null && !tile.isVoid()) {
				b.setRange(0);
				toRemove.add(b);
			}
		}
		toRemove.forEach(bullet -> {
			targetRealm.getExpiredBullets().add(bullet.getId());
			targetRealm.removeBullet(bullet);
		});
	}

	/**
	 * Difficulty-based damage scaler applied at hit time. Curve:
	 *   - difficulty <= threshold: 1.0 (no scaling)
	 *   - threshold < difficulty <= knee: 1.0 + PER_LEVEL * (difficulty - threshold)
	 *   - difficulty > knee: pre-knee value + PER_LEVEL_AFTER_KNEE * (difficulty - knee)
	 *   - hard-capped at CAP
	 * Dungeon instances use a 1.0-lower threshold than overworld zones so a
	 * difficulty-2.0 dungeon hits harder than the grasslands (diff 2.0) overworld.
	 */
	// difficultyDamageMult moved to com.openrealm.net.server.CombatMath

	/**
	 * Phase 3 — Knight Deflect: returns true if the incoming bullet was
	 * reflected back at its source enemy (damage skipped, bullet kept alive
	 * as a player projectile aimed at the attacker). Generic over any
	 * passive whose trigger is {@code ON_PROJECTILE_HIT_SELF} and whose
	 * condition is a {@code PROC_CHANCE} scaling — DEF for Knight, but any
	 * stat works.
	 */
	/** Knight Deflect — see {@link ServerCombatHelper#tryDeflect}. */
	public boolean tryDeflect(final Realm targetRealm, final Bullet b, final Player player) {
		return ServerCombatHelper.tryDeflect(this, targetRealm, b, player);
	}

	/** Kage Bunshin clone proc (orphaned legacy passive 11013) — see
	 *  {@link ServerCombatHelper#trySpawnBunshinClone}. */
	public boolean trySpawnBunshinClone(final Realm targetRealm, final Bullet b, final Player player) {
		return ServerCombatHelper.trySpawnBunshinClone(this, targetRealm, b, player);
	}

	/** Helper — Stats lookup by the same index used in AbilityScaling.statIndex(). */
	public static int statByIndex(Stats s, int idx) {
		if (s == null) return 0;
		switch (idx) {
			case 0: return s.getVit();
			case 1: return s.getWis();
			case 2: return s.getHp();
			case 3: return s.getMp();
			case 4: return s.getStr();
			case 5: return s.getDef();
			case 6: return s.getSpd();
			case 7: return s.getDex();
			default: return 0;
		}
	}


	/** Enemy bullet → player. See {@link ServerCombatHelper#processPlayerHit}. */
	private void processPlayerHit(final long realmId, final Bullet b, final Player p) {
		ServerCombatHelper.processPlayerHit(this, realmId, b, p);
	}

	/** Player bullet → enemy. See {@link ServerCombatHelper#processEnemyHit}. */
	private void proccessEnemyHit(final long realmId, final Bullet b, final Enemy e) {
		ServerCombatHelper.processEnemyHit(this, realmId, b, e);
	}


	public Bullet addProjectile(final long realmId, final long id, final long targetPlayerId, final int projectileId,
			final int projectileGroupId, final Vector2f src, final Vector2f dest, final short size,
			final float magnitude, final float range, short damage, final boolean isEnemy, final List<Short> flags, long srcEntityId) {
		final Realm targetRealm = this.realms.get(realmId);
		final Player player = targetRealm.getPlayer(targetPlayerId);
		if (player == null && !isEnemy)
			return null;

		// Drop new enemy bullets when the realm is at its bullet cap. Player
		// bullets bypass the cap so attack feel stays consistent.
		if (isEnemy && targetRealm.getBullets().size() >= MAX_ENEMY_BULLETS_PER_REALM) {
			return null;
		}

		// Player-bullet STR scaling lives at the firing site now (basic-attack
		// uses weapon.scalingStat; ability damage uses Ability scalings or the
		// abilityItem's scalingStat). addProjectile is item-agnostic so it
		// must not assume STR — see comment in the ability-data damage path.

		final long idToUse = id == 0l ? Realm.RANDOM.nextLong() : id;
		final Bullet b = new Bullet(idToUse, projectileId, src, dest, size, magnitude, range, damage, isEnemy);
		b.setSrcEntityId(srcEntityId);
		b.setFlags(flags);
		b.setCreatedTick(this.tickCounter);
		targetRealm.addBullet(b);
		return b;
	}

	public Bullet addProjectile(final long realmId, final long id, final long targetPlayerId, final int projectileId,
			final int projectileGroupId, final Vector2f src, final float angle, final short size, final float magnitude,
			final float range, short damage, final boolean isEnemy, final List<Short> flags, final short amplitude,
			final short frequency, long srcEntityId) {
		final Realm targetRealm = this.realms.get(realmId);
		final Player player = targetRealm.getPlayer(targetPlayerId);
		if (player == null && !isEnemy)
			return null;

		// Same enemy-bullet cap as the dest-targeted overload above.
		if (isEnemy && targetRealm.getBullets().size() >= MAX_ENEMY_BULLETS_PER_REALM) {
			return null;
		}

		// (No STR auto-add — see comment in the overload above.)

		final long idToUse = id == 0l ? Realm.RANDOM.nextLong() : id;
		final Bullet b = new Bullet(idToUse, projectileId, src, angle, size, magnitude, range, damage, isEnemy);
		b.setSrcEntityId(srcEntityId);
		b.setAmplitude(amplitude);
		b.setFrequency(frequency);
		b.setFlags(flags);
		b.setCreatedTick(this.tickCounter);
		targetRealm.addBullet(b);
		return b;
	}

	private List<Bullet> getBullets(final long realmId, final Player p) {
		final Realm targetRealm = this.realms.get(realmId);
		final GameObject[] gameObject = targetRealm
				.getGameObjectsInBounds(targetRealm.getTileManager().getRenderViewPort(p));

		final List<Bullet> results = new ArrayList<>();
		for (int i = 0; i < gameObject.length; i++) {
			if (gameObject[i] instanceof Bullet) {
				results.add((Bullet) gameObject[i]);
			}
		}
		return results;
	}

	// Invoked upon enemy death. Public so item scripts (e.g., Necromancer skull,
	// Sorcerer scepter) can trigger proper death handling with loot/XP.
	public void enemyDeath(final Realm targetRealm, final Enemy enemy) {
		final EnemyModel model = GameDataManager.ENEMIES.get(enemy.getEnemyId());
		try {
			// Party-shared XP: track every party id that has a member in the
			// viewport. Any other party members in the SAME realm — even if
			// off-screen — get a small share of the kill XP so playing as a
			// team is rewarded mechanically. Set-of-party-ids prevents
			// double-paying when multiple party members are in viewport.
			final Set<Long> partyIdsCredited = new HashSet<>();
			final Set<Long> viewportPlayerIds = new HashSet<>();

			// Get players in the viewport of this enemy and increment their experience
			for (final Player player : targetRealm
					.getPlayersInBounds(targetRealm.getTileManager().getRenderViewPort(enemy))) {
				viewportPlayerIds.add(player.getId());
				final long pid = this.partyManager.getPartyId(player.getId());
				if (pid != 0L) partyIdsCredited.add(pid);
				final int xpToGive = (int) (model.getXp() * enemy.getDifficulty());
				final long prevXp = player.getExperience();
				final boolean wasMaxLevel = GameDataManager.EXPERIENCE_LVLS.isMaxLvl(prevXp);
				final int prevLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(prevXp);
				final int levelsGained = player.incrementExperience(xpToGive);
				// Lifetime metrics — every player who was in viewport when the
				// enemy died gets a kill credit + xp-from-kill credit. Boss
				// flag is a stub (no canonical boss bit on EnemyModel today);
				// the per-enemyId breakdown lets us pivot on specific bosses
				// downstream without that flag being accurate.
				if (player.getMetrics() != null) {
					// Enemy id is int on the model but fits in short in
					// practice (enemy table size is well under 32k); cast
					// is safe and matches the metrics map key shape.
					player.getMetrics().recordKill((short) enemy.getEnemyId(), false);
					player.getMetrics().recordXp(xpToGive, true);
				}
				try {
					if (wasMaxLevel) {
						final long prevFame = GameDataManager.EXPERIENCE_LVLS.getBaseFame(prevXp);
						final long newFame = GameDataManager.EXPERIENCE_LVLS.getBaseFame(player.getExperience());
						if (newFame > prevFame) {
							this.enqueueServerPacket(player, TextEffectPacket.from(EntityType.PLAYER, player.getId(),
									TextEffect.PLAYER_INFO, "+" + (newFame - prevFame) + " Fame"));
						}
					} else {
						this.enqueueServerPacket(player, TextEffectPacket.from(EntityType.PLAYER, player.getId(),
								TextEffect.PLAYER_INFO, "+" + xpToGive + "xp"));
					}
					if (levelsGained > 0) {
						final int newLevel = prevLevel + levelsGained;
						this.enqueueServerPacket(player, TextEffectPacket.from(EntityType.PLAYER, player.getId(),
								TextEffect.HEAL, "Level Up! " + prevLevel + " \u2192 " + newLevel));
					}
				} catch (Exception ex) {
					RealmManagerServer.log.error("[SERVER] Failed to create player experience text effect. Reason: {}", ex);
				}
			}

			// Party-shared XP for OFF-VIEWPORT party members. For each party
			// that had at least one member in viewport, also award 50% of the
			// kill XP to every other in-realm party member who was NOT in
			// viewport themselves. Encourages multi-flank tactics — the
			// scout can be 20 tiles away rallying mobs while their teammate
			// burns down the boss and still get a credit.
			if (!partyIdsCredited.isEmpty()) {
				final int sharedXp = (int) (model.getXp() * enemy.getDifficulty() * 0.5);
				for (final Long pid : partyIdsCredited) {
					final List<Long> roster = this.partyManager.getPartyMembers(pickAnyRosterMember(pid));
					for (final Long memberId : roster) {
						if (viewportPlayerIds.contains(memberId)) continue;
						final Player member = this.getPlayerById(memberId);
						if (member == null) continue;
						final Realm mr = this.findPlayerRealm(memberId);
						if (mr == null || mr.getRealmId() != targetRealm.getRealmId()) continue;
						final long prevXp = member.getExperience();
						final int prevLevel = GameDataManager.EXPERIENCE_LVLS == null ? 0
								: GameDataManager.EXPERIENCE_LVLS.getLevel(prevXp);
						final int gained = member.incrementExperience(sharedXp);
						this.enqueueServerPacket(member, TextEffectPacket.from(EntityType.PLAYER, member.getId(),
								TextEffect.PLAYER_INFO, "+" + sharedXp + "xp (party)"));
						if (gained > 0) {
							final int newLevel = prevLevel + gained;
							this.enqueueServerPacket(member, TextEffectPacket.from(EntityType.PLAYER, member.getId(),
									TextEffect.HEAL, "Level Up! " + prevLevel + " → " + newLevel));
						}
					}
				}
			}

			// Snapshot qualifying players BEFORE clearing damage tracking, so soulbound rolls work.
			List<Long> qualifyingPlayerIds = (targetRealm.getOverseer() != null)
					? targetRealm.getOverseer().getQualifyingPlayers(enemy.getId())
					: new ArrayList<>();
			if (!qualifyingPlayerIds.isEmpty()) {
				final LinkedHashSet<Long> expanded = new LinkedHashSet<>(qualifyingPlayerIds);
				for (final Long qid : qualifyingPlayerIds) {
					final long pid = this.partyManager.getPartyId(qid);
					if (pid == 0L) continue;
					for (final Long memberId : this.partyManager.getPartyMembers(qid)) {
						if (memberId == null || memberId.equals(qid)) continue;
						final Realm mr = this.findPlayerRealm(memberId);
						if (mr != null && mr.getRealmId() == targetRealm.getRealmId()) {
							expanded.add(memberId);
						}
					}
				}
				qualifyingPlayerIds = new ArrayList<>(expanded);
			}
			if (targetRealm.getOverseer() != null) {
				long killerId = targetRealm.getOverseer().getTopDamageDealer(enemy.getId());
				targetRealm.getOverseer().onEnemyKilled(enemy, killerId);
				targetRealm.getOverseer().clearDamageTracking(enemy.getId());
			}

			targetRealm.getExpiredEnemies().add(enemy.getId());
			targetRealm.clearHitMap();
			targetRealm.removeEnemy(enemy);

			// Run boss-exit portal before loot processing so a missing loot table can't skip it.
			if (targetRealm.getSourceRealmId() != 0
					&& targetRealm.getDungeonBossEnemyId() > 0
					&& enemy.getEnemyId() == targetRealm.getDungeonBossEnemyId()) {
				final Realm sourceRealm = this.realms.get(targetRealm.getSourceRealmId());
				if (sourceRealm != null) {
					final Portal exitPortal = new Portal(Realm.RANDOM.nextLong(),
							(short) 3, enemy.getPos().clone());
					exitPortal.linkPortal(targetRealm, sourceRealm);
					exitPortal.setNeverExpires();
					targetRealm.addPortal(exitPortal);
					log.info("[SERVER] enemyDeath: BOSS EXIT portal spawned in realm {} at ({}, {}) -> source realm {}",
							targetRealm.getRealmId(), exitPortal.getPos().x, exitPortal.getPos().y,
							sourceRealm.getRealmId());
				}
			}

			final LootTableModel lootTable = GameDataManager.LOOT_TABLES.get(enemy.getEnemyId());
			if (lootTable == null) {
				log.warn("[SERVER] No loot table registered for enemy {}", enemy.getEnemyId());
				return;
			}

			final float diff = enemy.getDifficulty();
			final boolean upgradeEligible = diff > GlobalConstants.LOOT_TIER_UPGRADE_MIN_DIFFICULTY;
			float upgradeChance = upgradeEligible
					? (GlobalConstants.LOOT_TIER_UPGRADE_BASE_PERCENT
							+ GlobalConstants.LOOT_TIER_UPGRADE_PER_DIFFICULTY * diff) / 100.0f
					: 0.0f;
			if (enemy.hasEffect(StatusEffectType.MARKED_FOR_LOOT)) {
				upgradeChance = Math.min(1.0f, upgradeChance + 0.25f);
			}

			if (qualifyingPlayerIds.isEmpty()) {
				dropLootForPlayer(targetRealm, enemy, lootTable, -1, diff, upgradeEligible, upgradeChance);
			} else {
				for (Long playerId : qualifyingPlayerIds) {
					dropLootForPlayer(targetRealm, enemy, lootTable, playerId, diff, upgradeEligible, upgradeChance);
				}
			}

			// Overworld bypasses graph filtering; per-enemy loot tables drive portal drops there.
			final String currentNodeId = targetRealm.getNodeId();
			final DungeonGraphNode currentNode = (currentNodeId != null && GameDataManager.DUNGEON_GRAPH != null)
					? GameDataManager.DUNGEON_GRAPH.get(currentNodeId) : null;
			final boolean useGraphDrops = currentNode != null
					&& currentNode.getPortalDropNodeMap() != null
					&& !currentNode.getPortalDropNodeMap().isEmpty()
					&& !targetRealm.isOverworld();

			if (useGraphDrops) {
				// Graph-based portal drops: drop portals to child nodes (dungeons only)
				if (lootTable.getPortalDrops() != null) {
					final List<Integer> rolledPortals = lootTable.getPortalDrop();
					for (int portalId : rolledPortals) {
						// Find which child node this portalId leads to from the current node
						String targetNodeId = null;
						for (Map.Entry<String, Integer> entry : currentNode.getPortalDropNodeMap().entrySet()) {
							if (entry.getValue() == portalId) {
								targetNodeId = entry.getKey();
								break;
							}
						}
						if (targetNodeId == null) {
							continue;
						}

						PortalModel portalModel = GameDataManager.PORTALS.get(portalId);
						if (portalModel == null) continue;

						Portal portal = new Portal(Realm.RANDOM.nextLong(),
								(short) portalModel.getPortalId(), enemy.getPos().withNoise(64, 64));

						// Check if a realm for this node already exists
						Optional<Realm> existingRealm = this.findRealmForNode(targetNodeId);
						if (existingRealm.isPresent()) {
							portal.linkPortal(targetRealm, existingRealm.get());
						} else {
							portal.linkPortal(targetRealm, null);
						}
						// Store target node info on the portal for lazy realm creation
						portal.setTargetNodeId(targetNodeId);
						targetRealm.addPortal(portal);
						log.info("[SERVER] enemyDeath: SPAWNED portal {} -> node {} at ({}, {})",
								portalId, targetNodeId, portal.getPos().x, portal.getPos().y);
					}
				}
			} else {
				// Direct portal drops for overworld and non-graph realms.
				// Each enemy's loot table controls exactly what portals can drop.
				if (lootTable.getPortalDrops() != null) {
					for (int portalId : lootTable.getPortalDrop()) {
						PortalModel portalModel = GameDataManager.PORTALS.get(portalId);
						if (portalModel == null) continue;

						Portal portal = new Portal(Realm.RANDOM.nextLong(),
								(short) portalModel.getPortalId(), enemy.getPos().withNoise(64, 64));
						portal.linkPortal(targetRealm, null);
						targetRealm.addPortal(portal);
						log.info("[SERVER] enemyDeath: DIRECT portal {} spawned at ({}, {})",
								portalId, portal.getPos().x, portal.getPos().y);
					}
				}
			}

			// (Boss exit portal is handled above, before loot processing.)
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to handle dead Enemy {}. Reason: {}", enemy.getId(), e);
		}
	}

	// Invoked upon player death (permadeath)
	public void playerDeath(final Realm targetRealm, final Player player) {
		// Lifetime metric — record at the very top so it counts even when
		// the amulet-revive branch returns before bottom-of-method cleanup.
		if (player.getMetrics() != null) {
			player.getMetrics().recordDeath();
		}
		try {
			final String remoteAddrDeath = this.getRemoteAddressMapReversed().get(player.getId());
			// Ring slot moved from inv[3] to inv[4] in Phase 1B.
			final int ringSlot = 4;
			final boolean hasAmulet = player.getInventory()[ringSlot] != null
					&& player.getInventory()[ringSlot].getItemId() == 48;

			if (player.isHeadless() || player.isBot()) {
				// Bots/headless: drop grave and remove immediately
				if (hasAmulet) {
					player.getInventory()[ringSlot] = null;
					player.setHealth(1);
				} else {
					targetRealm.getExpiredPlayers().add(player.getId());
					final LootContainer graveLoot = new LootContainer(LootTier.GRAVE,
							player.getPos().clone(),
							player.getSlots(Player.EQUIPMENT_SLOT_COUNT, Player.EQUIPMENT_SLOT_COUNT + 8));
					targetRealm.addLootContainer(graveLoot);
					targetRealm.removePlayer(player);
					this.clearPlayerState(player.getId());
					if (remoteAddrDeath != null) {
						this.remoteAddresses.remove(remoteAddrDeath);
						final ClientSession botSession = this.server.getClients().get(remoteAddrDeath);
						if (botSession != null) {
							botSession.setShutdownProcessing(true);
							botSession.close();
							this.server.getClients().remove(remoteAddrDeath);
						}
					}
				}
				return;
			}

			// Both paths: player is removed from realm and sent to death/char select screen
			targetRealm.getExpiredPlayers().add(player.getId());
			this.enqueueServerPacket(player, PlayerDeathPacket.from(player.getId()));

			if (hasAmulet) {
				// Amulet saves the character: consume amulet, restore HP, persist.
				// Character is NOT deleted — player can re-login with it.
				TextPacket toBroadcast = TextPacket.create("SYSTEM", "",
						player.getName() + "'s Amulet of Resurrection shatters!");
				this.enqueueServerPacket(toBroadcast);
				player.getInventory()[ringSlot] = null;
				player.setHealth(player.getStats().getHp());
				this.persistPlayerAsync(player);
			} else {
				// Permadeath: drop grave (sync — must happen before the
				// player is gone from the realm), then bank-and-delete the
				// character on a worker thread so the tick doesn't stall on
				// remote HTTP. Compute the earned fame here from the live in-
				// memory xp so the bank uses fresh data regardless of when
				// the periodic 12s persist last ran. User-initiated deletes
				// from the character-select screen don't pass bankFame so
				// self-deletes still earn nothing.
				final LootContainer graveLoot = new LootContainer(LootTier.GRAVE,
						player.getPos().clone(),
						player.getSlots(Player.EQUIPMENT_SLOT_COUNT, Player.EQUIPMENT_SLOT_COUNT + 8));
				targetRealm.addLootContainer(graveLoot);
				final long earnedFame = GameDataManager.EXPERIENCE_LVLS.getBaseFame(player.getExperience());
				final String charUuid = player.getCharacterUuid();
				WorkerThread.doAsync(() -> {
					try {
						ServerGameLogic.DATA_SERVICE.executeDelete(
								"/data/account/character/" + charUuid
										+ "?bankFame=true&fameAmount=" + earnedFame,
								Object.class);
					} catch (Exception ex) {
						RealmManagerServer.log.error(
								"[SERVER] Async bank-and-delete failed for character {}: {}",
								charUuid, ex.getMessage());
					}
				});
			}
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to handle player death {}. Reason: {}", player.getId(), e);
		}
	}

	public void clearPlayerState(long playerId) {
		this.playerLoadLedger.remove(playerId);
		this.playerLastFullSnapshotMs.remove(playerId);
		this.playerUpdateState.remove(playerId);
		this.playerStateState.remove(playerId);
		this.playerUnloadState.remove(playerId);
		this.playerObjectMoveState.remove(playerId);
		this.playerDeadReckonState.remove(playerId);
		this.playerAbilityState.remove(playerId);
		this.playerLoadMapState.remove(playerId);
		this.playerLastHeartbeatTime.remove(playerId);
		this.playerGroundDamageState.remove(playerId);
		this.playerOutboundPacketQueue.remove(playerId);
		this.enemyUpdateState.remove(playerId);
		this.otherPlayerUpdateState.remove(playerId);
		// Clean up any poison state sourced by this player
		for (final Realm realm : this.realms.values()) {
			realm.removePlayerPoisonDots(playerId);
			realm.removePlayerPoisonThrows(playerId);
			realm.removePlayerTraps(playerId);
			realm.removePlayerDecoys(playerId);
			realm.removePlayerClones(playerId);
		}
		// Clear cached provisions on disconnect
		ServerCommandHandler.PLAYER_PROVISION_CACHE.remove(playerId);
	}


	public void enqueuePendingTransition(PendingRealmTransition transition) {
		this.pendingRealmTransitions.add(transition);
	}

	public void processPendingTransitions() {
		PendingRealmTransition t;
		while ((t = this.pendingRealmTransitions.poll()) != null) {
			try {
				final Realm generatedRealm = t.getGeneratedRealm();
				final Player player = t.getPlayer();
				this.addRealm(generatedRealm);
				if (t.getUsedPortal() != null) {
					t.getUsedPortal().setToRealmId(generatedRealm.getRealmId());
				}
				player.addEffect(StatusEffectType.INVINCIBLE, 4000);
				this.broadcastTextEffect(EntityType.PLAYER, player,
					TextEffect.PLAYER_INFO, "Invincible");
				generatedRealm.addPlayer(player);
				this.clearPlayerState(player.getId());
				this.invalidateRealmLoadState(generatedRealm);
				ServerGameLogic.sendImmediateLoadMap(this, generatedRealm, player);
				ServerGameLogic.onPlayerJoin(this, generatedRealm, player);
				log.info("[SERVER] Completed async realm transition for player {} -> realm {} (mapId={})",
					player.getName(), generatedRealm.getRealmId(), generatedRealm.getMapId());
			} catch (Exception e) {
				log.error("[SERVER] Failed to complete realm transition for player {}. Reason: {}",
					t.getPlayer().getName(), e.getMessage(), e);
			}
		}
	}

	/**
	 * Wipe every player's load ledger in this realm. Prefer
	 * {@link #invalidateLoadStateForPlayer(long)} for single-player joins —
	 * realm-wide wipes cost 150-300ms on the tick thread at 1500-enemy load.
	 */
	public void invalidateRealmLoadState(Realm realm) {
		for (final Long pid : realm.getPlayers().keySet()) {
			this.playerLoadLedger.remove(pid);
			this.playerLastFullSnapshotMs.remove(pid);
		}
	}

	public void invalidateLoadStateForPlayer(long playerId) {
		this.playerLoadLedger.remove(playerId);
		this.playerLastFullSnapshotMs.remove(playerId);
	}

	public void enqueuePendingJoin(PendingRealmJoin join) {
		this.pendingRealmJoins.add(join);
	}

	public void processPendingJoins() {
		PendingRealmJoin join;
		while ((join = this.pendingRealmJoins.poll()) != null) {
			try {
				final Realm welcomeRealm = join.getRealm();
				final Player toWelcome = join.getPlayer();
				welcomeRealm.addPlayer(toWelcome);
				// Joiner-only invalidation; neighbors discover via natural ledger delta.
				this.invalidateLoadStateForPlayer(toWelcome.getId());
				join.getSession().setHandshakeComplete(true);
				this.remoteAddresses.put(join.getSrcIp(), toWelcome.getId());
				this.enqueueServerPacket(toWelcome, join.getLoginResponse());
				WorkerThread.runLater(() -> ServerGameLogic.onPlayerJoin(this, welcomeRealm, toWelcome), 2000);
				log.info("[SERVER] Processed pending realm join for player {}", toWelcome.getName());
			} catch (Exception e) {
				log.error("[SERVER] Failed to process pending realm join for player {}. Reason: {}",
					join.getPlayer().getName(), e.getMessage());
			}
		}
	}

	public Map<Long, String> getRemoteAddressMapReversed() {
		final Map<Long, String> result = new HashMap<>();
		for (final Entry<String, Long> entry : this.remoteAddresses.entrySet()) {
			result.put(entry.getValue(), entry.getKey());
		}
		return result;
	}

	public void addRealm(final Realm realm) {
		this.tryDecorate(realm);
		realm.spawnStaticEnemies(realm.getMapId());
		final MapModel mapModel = GameDataManager.MAPS.get(realm.getMapId());
		if (mapModel != null && mapModel.getStaticPortals() != null) {
			for (final PortalModel sp : mapModel.getStaticPortals()) {
				try {
					final Portal portal = new Portal(Realm.RANDOM.nextLong(), (short) sp.getPortalId(),
							new Vector2f(sp.getX(), sp.getY()));
					portal.setNeverExpires();
					if (sp.getTargetNodeId() != null) {
						portal.setTargetNodeId(sp.getTargetNodeId());
						final DungeonGraphNode targetNode = GameDataManager.DUNGEON_GRAPH.get(sp.getTargetNodeId());
						if (targetNode != null && targetNode.isShared()) {
							this.findRealmForNode(sp.getTargetNodeId()).ifPresent(
									existing -> portal.setToRealmId(existing.getRealmId()));
						}
					}
					realm.addPortal(portal);
					log.info("[SERVER] Placed static portal {} at ({},{}) -> node '{}' in realm {}",
							sp.getPortalId(), sp.getX(), sp.getY(), sp.getTargetNodeId(), realm.getRealmId());
				} catch (Exception e) {
					log.error("[SERVER] Failed to place static portal. Reason: {}", e.getMessage());
				}
			}
		}
		// Overseer only attaches to map 2 (Beach overworld); each instance gets its own.
		if (realm.getMapId() == 2 && realm.getOverseer() == null) {
			realm.setOverseer(new RealmOverseer(realm, this));
			log.info("[SERVER] Attached RealmOverseer to realm {} (mapId=2)",
					realm.getRealmId());
		}
		this.realms.put(realm.getRealmId(), realm);
	}

	public Realm findPlayerRealm(long playerId) {
		Realm found = null;
		for (Map.Entry<Long, Realm> realm : this.realms.entrySet()) {
			for (Player player : realm.getValue().getPlayers().values()) {
				if (player.getId() == playerId) {
					found = realm.getValue();
				}
			}
		}
		return found;
	}


	public Optional<Realm> findRealmForNode(String nodeId) {
		if (nodeId == null) return Optional.empty();
		return this.getRealms().values().stream()
				.filter(realm -> nodeId.equals(realm.getNodeId()))
				.findAny();
	}

	public void enqueChunkedText(Player target, List<String> textLines) {
		for (String line : textLines) {
			TextPacket textPacket;
			try {
				textPacket = TextPacket.from("SYSTEM", target.getName(), line);
				this.enqueueServerPacket(target, textPacket);
			} catch (Exception e) {
				log.error("[SERVER] Failed to send text line {} to player {}. Reason: {}", line, target.getName(), e);
			}
		}
	}

	public Player findPlayerByName(String name) {
		Player result = null;
		for (Realm realm : this.getRealms().values()) {
			for (Player player : realm.getPlayers().values()) {
				if (player.getName().equalsIgnoreCase(name)) {
					result = player;
					break;
				}
			}
			if (result != null) {
				break;
			}
		}
		return result;
	}

	public Player searchRealmsForPlayer(String playerName) {
		Player found = null;
		final List<Player> allPlayers = this.getPlayers();
		for (Player player : allPlayers) {
			if (player.getName() != null && player.getName().equalsIgnoreCase(playerName)) {
				found = player;
			}
		}
		if (found == null) {
			log.info("[SERVER] searchRealmsForPlayer('{}') not found. Online players: {}",
				playerName, allPlayers.stream().map(p -> p.getName()).collect(Collectors.toList()));
		}
		return found;
	}

	public Player searchRealmsForPlayer(long playerId) {
		Player found = null;
		for (Player player : this.getPlayers()) {
			if (player.getId() == playerId) {
				found = player;
			}

		}
		return found;
	}

	// Background thread for persisting player data to DB
	private void beginPlayerSync() {
		final Runnable playerSync = () -> {
			try {
				while (!this.shutdown) {
					Thread.sleep(12000);
					RealmManagerServer.log.info("[SERVER] Performing asynchronous player data sync.");
					this.persistsPlayersAsync();
				}
			} catch (Exception e) {
				RealmManagerServer.log.error("[SERVER] Failed to perform player data sync. Reason: {}", e.getMessage());
			}
		};
		WorkerThread.submitAndForkRun(playerSync);
	}

	// Server shutdown task. Attempts to save all player data before JVM exit
	public Thread shutdownHook() {
		final Runnable shutdownTask = () -> {
			RealmManagerServer.log.info("[SERVER] Performing pre-shutdown player sync...");
			this.persistsPlayersAsync();
			RealmManagerServer.log.info("[SERVER] Shutdown player sync complete");
		};
		return new Thread(shutdownTask);
	}

	public void persistsPlayersAsync() {
		final Runnable persist = () -> {
			for (Player player : this.getPlayers()) {
				this.persistPlayer(player);
			}
		};
		WorkerThread.doAsync(persist);
	}

	public List<Player> getPlayers() {
		final List<Player> players = new ArrayList<>();
		for (final Map.Entry<Long, Realm> realm : this.realms.entrySet()) {
			for (final Player player : realm.getValue().getPlayers().values()) {
				players.add(player);
			}
		}
		return players;
	}

	public Player getPlayerById(long playerId) {
		return this.getPlayers().stream().filter(p -> p.getId() == playerId).findAny().orElse(null);
	}

	/** Resolve a player by case-insensitive name (whitespace trimmed).
	 *  Returns null on miss. Used by /party invite name lookup. */
	public Player getPlayerByName(String name) {
		if (name == null) return null;
		final String needle = name.trim();
		if (needle.isEmpty()) return null;
		for (final Player p : this.getPlayers()) {
			if (p.getName() != null && p.getName().equalsIgnoreCase(needle)) return p;
		}
		return null;
	}

	public void broadcastPartyUpdate(long partyId) {
		if (partyId == 0L) return;
		final List<Long> roster = this.partyManager.getPartyMembers(
				/*any member*/ pickAnyRosterMember(partyId));
		if (roster.isEmpty()) return;
		final List<NetPartyMember> members = new ArrayList<>(roster.size());
		for (final Long memberId : roster) {
			final Player p = this.getPlayerById(memberId);
			if (p == null) continue;
			final Stats st = p.getComputedStats();
			final NetPartyMember m = new NetPartyMember();
			m.setPlayerId(p.getId());
			m.setName(p.getName());
			m.setClassId(p.getClassId());
			m.setHealth(p.getHealth());
			m.setMaxHealth(st != null ? st.getHp() : p.getHealth());
			m.setMana(p.getMana());
			m.setMaxMana(st != null ? st.getMp() : p.getMana());
			m.setLevel(GameDataManager.EXPERIENCE_LVLS == null
					? 0 : GameDataManager.EXPERIENCE_LVLS.getLevel(p.getExperience()));
			final Realm r = this.findPlayerRealm(p.getId());
			m.setRealmId(r == null ? 0L : r.getRealmId());
			m.setEffectIds(p.getEffectIds() == null ? new Short[0] : p.getEffectIds().clone());
			final int[] hb = p.getHotbarBindings();
			final Integer[] hbBoxed = new Integer[4];
			for (int i = 0; i < 4; i++) hbBoxed[i] = (hb != null && i < hb.length) ? hb[i] : 0;
			m.setHotbarBindings(hbBoxed);
			final long[] cds = p.getAbilityCooldowns();
			final Long[] cdBoxed = new Long[4];
			for (int i = 0; i < 4; i++) cdBoxed[i] = (cds != null && i < cds.length) ? cds[i] : 0L;
			m.setAbilityCooldownEnds(cdBoxed);
			// Invested level per hotbar slot — needed for teammate ability tooltip parity.
			final Integer[] invBoxed = new Integer[4];
			for (int i = 0; i < 4; i++) {
				final int aid = (hb != null && i < hb.length) ? hb[i] : 0;
				invBoxed[i] = aid > 0 ? p.getSkillLevel(aid) : 0;
			}
			m.setHotbarInvested(invBoxed);
			// Carry computed stats so teammate ability tooltips can render
			// stat-scaled damage (STR/DEX/WIS contributions, SP×N lines)
			// against the OWNER's real numbers. We already have `st` from
			// the HP/MP-max plumbing above — reuse it instead of recomputing.
			m.setStats(NetStats.fromStats(st));
			members.add(m);
		}
		final PartyUpdatePacket pkt =
				new PartyUpdatePacket();
		pkt.setPartyId(partyId);
		pkt.setMembers(members.toArray(new NetPartyMember[0]));
		for (final Long memberId : roster) {
			final Player target = this.getPlayerById(memberId);
			if (target != null) this.enqueueServerPacket(target, pkt);
		}
	}

	/** Helper so broadcastPartyUpdate can resolve a roster from any partyId
	 *  without exposing a PartyManager.getRosters() accessor. */
	private long pickAnyRosterMember(long partyId) {
		for (final Player p : this.getPlayers()) {
			if (this.partyManager.getPartyId(p.getId()) == partyId) return p.getId();
		}
		return 0L;
	}

	/**
	 * Disconnect cleanup: pull the player out of PartyManager and notify the
	 * remaining members. If the dissolve-on-1 rule fires (2-player party
	 * loses one), push a {@code partyId=0} update to the lone survivor so
	 * their UI hides immediately. Otherwise broadcast a refreshed roster to
	 * the rest of the (3-4 player) party.
	 *
	 * Safe to call for players who weren't in a party — no-op in that case.
	 */
	public void cleanupPartyOnDisconnect(final long playerId) {
		final PartyManager.LeaveResult res = this.partyManager.handleDisconnect(playerId);
		if (res.partyId == 0L) return;
		if (res.evictedSurvivorId != 0L) {
			final Player survivor = this.getPlayerById(res.evictedSurvivorId);
			if (survivor != null) this.sendEmptyPartyUpdate(survivor);
		} else {
			this.broadcastPartyUpdate(res.partyId);
		}
	}

	/**
	 * Tell a single player their party has been torn down (or they're no
	 * longer in one). The client uses {@code partyId == 0} as the signal to
	 * hide the party UI rows.
	 */
	public void sendEmptyPartyUpdate(final Player to) {
		if (to == null) return;
		final PartyUpdatePacket pkt =
				new PartyUpdatePacket();
		pkt.setPartyId(0L);
		pkt.setMembers(new NetPartyMember[0]);
		this.enqueueServerPacket(to, pkt);
	}

	public void safeRemoveRealm(final Realm realm) {
		this.safeRemoveRealm(realm.getRealmId());
	}

	public void safeRemoveRealm(final long realmId) {
		this.acquireRealmLock();
		final Realm realm = this.realms.remove(realmId);
		realm.setShutdown(true);
		this.releaseRealmLock();
	}

	public void persistPlayerAsync(final Player player) {
		final Runnable persist = () -> {
			this.persistPlayer(player);
		};
		WorkerThread.doAsync(persist);
	}

	private boolean persistPlayer(final Player player) {
		if (player.isHeadless() || player.isBot()) return false;
		if (player.getAccountUuid() == null || player.getName() == null || player.getName().startsWith("Bot_"))
			return false;
		try {
			final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE
					.executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
			final Optional<CharacterDto> currentCharacter = account.getCharacters().stream()
					.filter(character -> character.getCharacterUuid().equals(player.getCharacterUuid())).findAny();
			if (currentCharacter.isPresent()) {
				final CharacterDto character = currentCharacter.get();
				// Skip soft-deleted characters; the data service rejects the write anyway.
				if (character.isDeleted()) {
					log.info("[SERVER] Skipping persist for character {} on account {} — already soft-deleted on data service.",
							character.getCharacterUuid(), account.getAccountEmail());
					return false;
				}
				character.setItems(player.serializeItems());
				character.setStats(player.serializeStats());
				ServerGameLogic.DATA_SERVICE.executePost(
						"/data/account/character/" + character.getCharacterUuid(), character, CharacterDto.class);
				log.info("[SERVER] Succesfully persisted user account {}", account.getAccountEmail());
				this.flushMetricsDelta(player, character.getCharacterUuid());
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to get player account. Reason: {}", e);
		}
		return true;
	}

	// On HTTP failure the delta is merged back into the in-memory counters so
	// the next window's events stack on top instead of losing the work.
	private void flushMetricsDelta(final Player player, final String characterUuid) {
		try {
			final PlayerMetrics m = player.getMetrics();
			if (m == null) return;
			final MetricsDelta delta = m.drainAndReset();
			if (delta == null) return;
			final MetricsDeltaDto dto = MetricsDeltaDto.from(delta);
			try {
				ServerGameLogic.DATA_SERVICE.executePost(
						"/data/account/character/" + characterUuid + "/metrics/delta", dto, Object.class);
			} catch (Exception postEx) {
				log.warn("[METRICS] Flush failed for {} — re-queueing delta. Reason: {}",
						characterUuid, postEx.getMessage());
				m.mergeBack(delta);
			}
		} catch (Exception metricsEx) {
			log.error("[METRICS] Unexpected error during flush for {}: {}", characterUuid, metricsEx.getMessage());
		}
	}

	public void sendTextEffectToPlayer(final Player player, final TextEffect effect, final String text) {
		try {
			this.enqueueServerPacket(player, TextEffectPacket.from(EntityType.PLAYER, player.getId(), effect, text));
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to send TextEffect Packet to Player {}. Reason: {}",
					player.getId(), e);
		}
	}

	public void applyStatusWithFeedback(final Realm realm, final Entity entity,
			final EntityType entityType, final StatusEffectType status, final long durationMs) {
		if (entity == null || status == null) return;
		entity.addEffect(status, durationMs);
		this.broadcastTextEffect(realm, entityType, entity, TextEffect.PLAYER_INFO, status.name());
	}

	public void broadcastTextEffect(final EntityType entityType, final GameObject entity,
			final TextEffect effect, final String text) {
		final Realm realm = this.findPlayerRealm(entity.getId());
		if (realm == null) {
			for (final Realm r : this.realms.values()) {
				if (r.getEnemy(entity.getId()) != null || r.getPlayer(entity.getId()) != null) {
					broadcastTextEffect(r, entityType, entity, effect, text);
					return;
				}
			}
			return;
		}
		broadcastTextEffect(realm, entityType, entity, effect, text);
	}

	public void broadcastTextEffect(final Realm realm, final EntityType entityType, final GameObject entity,
			final TextEffect effect, final String text) {
		broadcastTextEffect(realm, entityType, entity, effect, text, 0f, 0f);
	}

	// posX/posY pin damage text to the impact point so it doesn't follow the moving target.
	public void broadcastTextEffect(final Realm realm, final EntityType entityType, final GameObject entity,
			final TextEffect effect, final String text, final float posX, final float posY) {
		try {
			final TextEffectPacket packet = TextEffectPacket.from(entityType, entity.getId(), effect, text, posX, posY);
			final float viewRadius = 10 * GlobalConstants.BASE_TILE_SIZE;
			for (final Player p : realm.getPlayers().values()) {
				if (p.isHeadless()) continue;
				float dx = p.getPos().x - entity.getPos().x;
				float dy = p.getPos().y - entity.getPos().y;
				if (dx * dx + dy * dy <= viewRadius * viewRadius) {
					this.enqueueServerPacket(p, packet);
				}
			}
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to broadcast TextEffect Packet for Entity {}. Reason: {}",
					entity.getId(), e);
		}
	}

	public void registerPoisonDot(long realmId, long enemyId, int totalDamage, long duration, long sourcePlayerId) {
		final Realm realm = this.realms.get(realmId);
		if (realm != null) {
			realm.registerPoisonDot(enemyId, totalDamage, duration, sourcePlayerId);
		}
	}

	public void acquireRealmLock() {
		this.realmLock.lock();
	}

	public void releaseRealmLock() {
		this.realmLock.unlock();
	}

	// soulboundPlayerId == -1 means public loot.
	private void dropLootForPlayer(final Realm targetRealm, final Enemy enemy,
			final LootTableModel lootTable, final long soulboundPlayerId,
			final float diff, final boolean upgradeEligible, final float upgradeChance) {
		final List<GameItem> lootToDrop = lootTable.getLootDrop();

		if (targetRealm.getDungeonBossEnemyId() > 0
				&& enemy.getEnemyId() == targetRealm.getDungeonBossEnemyId()) {
			final LootGroupModel statPotionGroup = GameDataManager.LOOT_GROUPS.get(0);
			if (statPotionGroup != null && !statPotionGroup.getPotentialDrops().isEmpty()) {
				for (int i = 0; i < 2; i++) {
					final int potionItemId = statPotionGroup.getPotentialDrops()
							.get(Realm.RANDOM.nextInt(statPotionGroup.getPotentialDrops().size()));
					final GameItem potion = GameDataManager.GAME_ITEMS.get(potionItemId);
					if (potion != null) lootToDrop.add(potion);
				}
			}
		}

		final List<GameItem> consumableDrops = new ArrayList<>();
		final List<GameItem> normalDrops = new ArrayList<>();
		final List<GameItem> boostedDrops = new ArrayList<>();
		for (final GameItem original : lootToDrop) {
			if (original.isConsumable()) { consumableDrops.add(original); continue; }
			GameItem toDrop = original;
			boolean wasUpgraded = false;
			if (upgradeEligible && Realm.RANDOM.nextFloat() < upgradeChance) {
				final GameItem upgraded = findUpgradedItem(original);
				if (upgraded != null) { toDrop = upgraded; wasUpgraded = true; }
			}
			(wasUpgraded ? boostedDrops : normalDrops).add(toDrop);
		}

		if (!consumableDrops.isEmpty()) {
			targetRealm.addLootContainer(new LootContainer(LootTier.BROWN,
					enemy.getPos().withNoise(64, 64),
					consumableDrops.toArray(new GameItem[0])));
		}
		if (!normalDrops.isEmpty()) {
			final LootContainer bag = new LootContainer(LootTier.BLUE,
					enemy.getPos().withNoise(64, 64),
					normalDrops.toArray(new GameItem[0]));
			bag.setSoulboundPlayerId(soulboundPlayerId);
			targetRealm.addLootContainer(bag);
		}
		if (!boostedDrops.isEmpty()) {
			final LootContainer bag = new LootContainer(LootTier.BOOSTED,
					enemy.getPos().withNoise(64, 64),
					boostedDrops.toArray(new GameItem[0]));
			bag.setSoulboundPlayerId(soulboundPlayerId);
			targetRealm.addLootContainer(bag);
			log.info("[SERVER] BOOSTED loot drop: {} upgraded item(s) from enemy {} (difficulty={}) for player {}",
					boostedDrops.size(), enemy.getEnemyId(), diff,
					soulboundPlayerId == -1 ? "PUBLIC" : soulboundPlayerId);
		}
	}

	private static GameItem findUpgradedItem(GameItem item) {
		if (item == null || item.isConsumable() || item.getTier() < 0) return null;
		final byte nextTier = (byte) (item.getTier() + 1);
		final byte slot = item.getTargetSlot();
		final byte targetClass = item.getTargetClass();
		for (GameItem candidate : GameDataManager.GAME_ITEMS.values()) {
			if (candidate.getTier() == nextTier
					&& candidate.getTargetSlot() == slot
					&& candidate.getTargetClass() == targetClass
					&& !candidate.isConsumable()) {
				return candidate;
			}
		}
		return null;
	}
}
