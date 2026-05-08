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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.CombatModifiers;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
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
import com.openrealm.net.client.packet.UpdatePacket;

import com.openrealm.net.entity.NetTile;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.ClientSession;
import com.openrealm.net.server.NioServer;
import com.openrealm.net.server.ServerCommandHandler;
import com.openrealm.net.server.ServerGameLogic;
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

	// Thread-safe queue for pending realm joins. Worker threads (async login) push
	// here instead of mutating realm state directly, and the tick thread drains it
	// at the start of each tick to avoid race conditions with enqueueGameData().
	private final ConcurrentLinkedQueue<PendingRealmJoin> pendingRealmJoins = new ConcurrentLinkedQueue<>();
	// Thread-safe queue for async realm generation completions. Worker threads generate
	// the realm (heavy CPU), then enqueue here for tick-thread integration.
	private final ConcurrentLinkedQueue<PendingRealmTransition> pendingRealmTransitions = new ConcurrentLinkedQueue<>();
	// Delta cache for other-player UpdatePackets (keyed by viewerPlayerId -> targetPlayerId -> packet)
	private Map<Long, Map<Long, UpdatePacket>> otherPlayerUpdateState = new ConcurrentHashMap<>();
	
	private Map<Long, Long> playerAbilityState = new ConcurrentHashMap<>();
	private Map<Long, LoadPacket> playerLoadState = new ConcurrentHashMap<>();
	// Per-realm last-tick wall-clock used to compute bulletScale ONCE per
	// realm per tick instead of per-bullet — eliminates ~12K nanoTime
	// syscalls/sec when 200 bullets are in flight.
	private Map<Long, Long> lastBulletUpdateNanos = new ConcurrentHashMap<>();
	// Last wall-clock time we forced a full LoadPacket snapshot to the player.
	// Used to periodically refresh players/enemies/portals so a dropped
	// delta packet self-heals. WebSocket TCP makes drops rare so we don't
	// need a tight interval — 10s balances recovery time against the
	// per-cycle cost of repeatedly shipping the full N-player snapshot.
	private Map<Long, Long> playerLastFullSnapshotMs = new ConcurrentHashMap<>();
	private static final long FULL_SNAPSHOT_INTERVAL_MS = 10000L;
	private Map<Long, UpdatePacket> playerUpdateState = new ConcurrentHashMap<>();
	private Map<Long, PlayerStatePacket> playerStateState = new ConcurrentHashMap<>();
	private Map<Long, UpdatePacket> enemyUpdateState = new ConcurrentHashMap<>();
	private Map<Long, UnloadPacket> playerUnloadState = new ConcurrentHashMap<>();
	private Map<Long, LoadMapPacket> playerLoadMapState = new ConcurrentHashMap<>();
	private Map<Long, ObjectMovePacket> playerObjectMoveState = new ConcurrentHashMap<>();
	// Dead reckoning state: outer map = playerId, inner map = entityId -> motion state.
	// Tracks what each client believes each entity's position to be, so we only send
	// corrections when the server's actual state diverges beyond a threshold.
	private Map<Long, Map<Long, EntityMotionState>> playerDeadReckonState = new ConcurrentHashMap<>();
	private Map<Long, Long> playerGroundDamageState = new ConcurrentHashMap<>();
	private Map<Long, Long> playerLastHeartbeatTime = new ConcurrentHashMap<>();
	// Last sent global player positions per realm (for delta detection)
	private Map<Long, NetPlayerPosition[]> lastGlobalPositions = new ConcurrentHashMap<>();

	// Poison damage-over-time tracking
	private UnloadPacket lastUnload;
	// Potentially accessed by many threads many times a second.
	// marked volatile to make sure each time this queue is accessed
	// we are not looking at a cached version. Make a PR if my assumption is wrong :)
	private volatile Queue<Packet> outboundPacketQueue = new ConcurrentLinkedQueue<>();
	private volatile Map<Long, ConcurrentLinkedQueue<Packet>> playerOutboundPacketQueue = new ConcurrentHashMap<Long, ConcurrentLinkedQueue<Packet>>();
	private List<RealmDecoratorBase> realmDecorators = new ArrayList<>();
	private List<EnemyScriptBase> enemyScripts = new ArrayList<>();
	private List<UseableItemScriptBase> itemScripts = new ArrayList<>();
	// Note: realmLock is currently unnecessary — all realm access happens on the single tick thread.
	// Kept as a ReentrantLock for safety if threading model changes in the future.
	private final ReentrantLock realmLock = new ReentrantLock();
	private int currentTickCount = 0;
	private long tickSampleTime = 0;

	// Tiered update rate tick counter (increments every tick, wraps at 64)
	private int tickCounter = 0;

	// Tick rate divisors for tiered packet transmission (at 64 ticks/sec):
	// Dead reckoning: clients extrapolate using velocity, server only sends corrections
	// when actual position diverges from predicted. This allows much lower check rates
	// while maintaining visual fidelity via client-side interpolation.
	// LoadPacket: 16Hz - entity spawns/despawns aren't time-critical
	// UpdatePacket: 8Hz - stats/inventory/effects change slowly
	// LoadMapPacket: 4Hz - terrain barely changes
	// EnemyUpdatePacket: 8Hz - enemy health bars
	private static final int MOVE_TICK_DIVISOR = 2;       // Inner zone dead reckoning check at 32Hz
	private static final int MOVE_FULL_TICK_DIVISOR = 4;  // Full viewport dead reckoning check at 16Hz
	private static final int LOAD_TICK_DIVISOR = 2;       // Entity spawn/despawn at 32Hz — bullets need low latency to avoid burst effect
	private static final int UPDATE_TICK_DIVISOR = 8;     // Stats/inventory at 8Hz (was 16Hz — stats change slowly)
	private static final int LOADMAP_TICK_DIVISOR = 16;
	private static final int ENEMY_UPDATE_TICK_DIVISOR = 8; // Enemy health bars at 8Hz (was 16Hz)
	// Enemy AI tick divisor — staggered so 1/N of enemies get updated each
	// tick. MUST be a power of 2 (used as a bitmask). Value 2 gives each
	// enemy a 32 Hz effective AI rate; value 4 -> 16 Hz. 32 Hz is plenty
	// for chase/attack AI and halves the per-tick cost at 10K enemies.
	private static final int ENEMY_AI_TICK_DIVISOR = 2;
	// Movement stagger for awake-but-off-screen enemies. If no player has
	// the enemy in viewport, run tickMove every Nth tick. Visible enemies
	// still move every tick to avoid stutter. Power of 2 (bitmask).
	private static final int ENEMY_MOVE_FAR_DIVISOR = 4;
	// Viewport radius squared (10 tiles). Mirror the constant used by
	// LoadPacket / movement broadcast in RealmManagerServer.enqueueGameData
	// so the "is anyone watching this enemy?" check matches the visibility
	// the client actually sees.
	private static final float VIEWPORT_RADIUS_SQ =
		(10f * GlobalConstants.BASE_TILE_SIZE)
			* (10f * GlobalConstants.BASE_TILE_SIZE);

	// Hard cap on concurrent ENEMY bullets per realm. The 1000-enemy stress
	// test produced 15K live bullets (1.5 bullets/sec/enemy × ~10s lifetime)
	// — bullet.update() and bullet->player collision iterate the full bullet
	// map every tick, so 15K live bullets dominate CPU and crater TPS. With
	// the cap, excess enemy attacks fail-fast at addProjectile (return null,
	// no spatial-grid insert, no LoadPacket entry, no per-tick update). At
	// the cap, attacks distribute across enemies fairly via the natural
	// arrival ordering. PLAYER bullets always succeed regardless of cap so
	// player attack feel is preserved.
	private static final int MAX_ENEMY_BULLETS_PER_REALM = 1500;

	private boolean  isSetup = false;
	
	private long lastWriteSampleTime = Instant.now().toEpochMilli();
	private final AtomicLong bytesWritten = new AtomicLong(0);
	private final ConcurrentHashMap<String, AtomicLong> bytesWrittenByPacketType = new ConcurrentHashMap<>();
	// Inbound bandwidth (post-compression, on-the-wire). Mirrors the
	// outbound counters above so we can log a true read-rate that matches
	// the bytes actually crossing the network.
	private final AtomicLong bytesRead = new AtomicLong(0);
	private final ConcurrentHashMap<String, AtomicLong> bytesReadByPacketType = new ConcurrentHashMap<>();
	
	public RealmManagerServer() {
		// Probably dont want to auto start the server so migrating
		// this to be invoked from somewhere else (GameLauncher.class)
//		this.doRunServer();
	}
	
	public void doRunServer() {
		// TODO: Make the trade manager a class variable so we dont
		// have to do this whacky static assignment
		ServerTradeManager.mgr = this;
		
		// Spawn initial realm and add the global
		// save player shutdown hook to the Runtime
		this.doSetup();
		
		// Two core threads, the inbound connection listener
		// and the actual realm manager thread to handle game processing
		WorkerThread.submitAndForkRun(this.server);
		WorkerThread.submitAndForkRun(this);
	}
	
	private void doSetup() {
		if(this.isSetup) {
			log.warn("[SERVER] Server is already setup, ignoring extra call");
			return;
		}
		// Start listening for connections
		this.server = new NioServer(2222);

		// Start WebSocket server for browser-based clients
		try {
			final WebSocketGameServer wsServer =
				new WebSocketGameServer(2223, this.server);
			wsServer.start();
			log.info("[SERVER] WebSocket server started on port 2223");
		} catch (Exception e) {
			log.error("[SERVER] Failed to start WebSocket server: {}", e.getMessage());
		}

		this.registerRealmDecorators();
		this.registerEnemyScripts();
		this.registerPacketCallbacks();
		this.registerPacketCallbacksReflection();
		this.registerItemScripts();
		this.registerCommandHandlersReflection();
		this.beginPlayerSync();
		
		Realm realm = null;
		final DungeonGraphNode entryNode = GameDataManager.getEntryNode();
		try {
			if (entryNode != null) {
				log.info("[SERVER] Creating realm for entry node: {} (mapId={})", entryNode.getNodeId(), entryNode.getMapId());
				realm = new Realm(true, entryNode.getMapId(), entryNode.getNodeId());
				log.info("[SERVER] Realm created successfully for node: {}", entryNode.getNodeId());
			} else {
				log.warn("[SERVER] No dungeon graph entry node found, falling back to mapId=2");
				realm = new Realm(true, 2);
			}
		} catch (Exception e) {
			log.error("[SERVER] Failed to create entry realm (mapId={}). Falling back to mapId=2. Reason: {}",
					entryNode != null ? entryNode.getMapId() : "null", e.getMessage(), e);
			realm = new Realm(true, 2);
		}
		final var entryMapModel = GameDataManager.MAPS.get(realm.getMapId());
		final boolean isStaticMap = entryMapModel != null && entryMapModel.getTerrainId() < 0;

		realm.spawnRandomEnemies(realm.getMapId());

		// Overseer attachment is handled centrally in addRealm() based on
		// mapId, so no need to attach here.

		// Place set piece structures only for terrain-generated maps
		if (entryMapModel != null && entryMapModel.getTerrainId() >= 0 && GameDataManager.TERRAINS != null) {
			TerrainGenerationParameters terrainParams = GameDataManager.TERRAINS.get(entryMapModel.getTerrainId());
			if (terrainParams == null) {
				terrainParams = GameDataManager.TERRAINS.get(0);
			}
			if (terrainParams != null && terrainParams.getSetPieces() != null) {
				log.info("[SERVER] Placing set pieces for terrain '{}' ({} types defined)",
					terrainParams.getName(), terrainParams.getSetPieces().size());
				realm.placeSetPieces(terrainParams);
			}
		} else {
			log.info("[SERVER] Static map (mapId={}), skipping set piece placement", realm.getMapId());
		}
		this.addRealm(realm);
		
		Runtime.getRuntime().addShutdownHook(this.shutdownHook());
		
		this.isSetup = true;
	}

	// Adds a specified amount of random headless players
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

					final boolean up = random.nextBoolean();
					final boolean right = random.nextBoolean();

					if (up) {
						// player.setUp(true);
						player.setDy(-random.nextFloat());
					} else {
						// player.setDown(true);
						player.setDy(random.nextFloat());
					}
					if (right) {
						// player.setRight(true);
						player.setDx(random.nextFloat());
					} else {
						// player.setLeft(true);
						player.setDx(-random.nextFloat());
					}
					Thread.sleep(100);
					player.setHeadless(true);

					final long newId = targetRealm.addPlayer(player);
				} catch (Exception e) {
					RealmManagerServer.log.error("Failed to spawn test character of class type {}. Reason: {}",
							classToSpawn, e);
				}
			}
		};
		// Run this in a completely separate thread
		WorkerThread.submitAndForkRun(spawnTestPlayers);
	}

	@Override
	public void run() {
		RealmManagerServer.log.info("[SERVER] Starting OpenRealm Server");
		final Runnable tick = () -> {
			this.tick();
		};
		
		final TimedWorkerThread workerThread = new TimedWorkerThread(tick, 64);
		WorkerThread.submitAndForkRun(workerThread);
		RealmManagerServer.log.info("[SERVER] RealmManagerServer exiting run().");
	}

	// Tick-budget log: when one whole tick exceeds the 16ms budget (1 tick at
	// 64 Hz), emit a single line breaking down where the time went. Throttled
	// to once per second so a sustained slow-tick storm doesn't flood logs.
	private static final long TICK_BUDGET_NANOS = 16_000_000L; // 16ms
	private long lastSlowTickLogMs = 0L;
	// Sub-phase counters inside update() — populated per tick so the slow-tick
	// log can break "update=Xms" into player vs enemy vs bullet vs tail cost.
	private long updPlayersNanos = 0L;
	private long updEnemiesNanos = 0L;
	private long updBulletsNanos = 0L;
	private long updTailNanos = 0L;

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

			// update() runs BEFORE enqueueGameData so that enemy bullets spawned
			// during Enemy.update() are in the spatial grid when LoadPacket is built.
			// Previously, enqueueGameData ran first and missed same-tick enemy bullets,
			// causing them to appear 1-2 ticks late on the client.
			t0 = System.nanoTime();
			this.update(0);
			tUpdate = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.enqueueGameData();
			tEnqueue = System.nanoTime() - t0;

			t0 = System.nanoTime();
			this.sendGameData();
			tSend = System.nanoTime() - t0;

			// Tick all realm overseers (ecosystem management)
			t0 = System.nanoTime();
			for (Realm realm : this.realms.values()) {
				if (realm.getOverseer() != null) {
					realm.getOverseer().tick();
				}
			}
			tOverseer = System.nanoTime() - t0;

			if (Instant.now().toEpochMilli() - this.tickSampleTime > 1000) {
				this.tickSampleTime = Instant.now().toEpochMilli();
				log.info("[SERVER] ticks this second: {}", this.currentTickCount);
				this.currentTickCount = 0;
			} else {
				this.currentTickCount++;
			}
		} catch (Exception e) {
			RealmManagerServer.log.error("Failed to process server tick. Reason: {}", e);
		}
		final long tickTotal = System.nanoTime() - tickStart;
		if (tickTotal > TICK_BUDGET_NANOS) {
			final long nowMs = System.currentTimeMillis();
			if (nowMs - lastSlowTickLogMs >= 1000) {
				lastSlowTickLogMs = nowMs;
				int totalEnemies = 0, totalBullets = 0, totalPlayers = 0;
				for (Realm r : this.realms.values()) {
					totalEnemies += r.getEnemies().size();
					totalBullets += r.getBullets().size();
					totalPlayers += r.getPlayers().size();
				}
				log.warn("[SERVER] slow tick: total={}ms (joins={}, trans={}, pkts={}, update={}[plyrs={},enems={},blts={},tail={}], enqueue={}, send={}, overseer={}) — realms={}, players={}, enemies={}, bullets={}",
					tickTotal / 1_000_000,
					tJoins / 1_000_000, tTransitions / 1_000_000, tPackets / 1_000_000,
					tUpdate / 1_000_000,
					this.updPlayersNanos / 1_000_000, this.updEnemiesNanos / 1_000_000,
					this.updBulletsNanos / 1_000_000, this.updTailNanos / 1_000_000,
					tEnqueue / 1_000_000, tSend / 1_000_000, tOverseer / 1_000_000,
					this.realms.size(), totalPlayers, totalEnemies, totalBullets);
			}
		}
	}



	private void sendGameData() {
		long startNanos = System.nanoTime();
		final List<Packet> packetsToBroadcast = new ArrayList<>();
		// TODO: Possibly rework this queue as we dont usually send stuff globally
		while (!this.outboundPacketQueue.isEmpty()) {
			packetsToBroadcast.add(this.outboundPacketQueue.remove());
		}

		// Detect stale sessions
		final List<Map.Entry<String, ClientSession>> staleSessions = new ArrayList<>();
		for (final Map.Entry<String, ClientSession> client : this.server.getClients().entrySet()) {
			if (!client.getValue().isConnected() || client.getValue().isShutdownProcessing()) {
				staleSessions.add(client);
			}
		}
		staleSessions.forEach(entry -> {
			try {
				final boolean wasConnected = entry.getValue().isConnected();
				final boolean wasShutdown = entry.getValue().isShutdownProcessing();
				final String staleReason = !wasConnected ? "connection lost (isConnected=false)" : "shutdownProcessing flag already set";
				entry.getValue().setShutdownProcessing(true);
				// Remove the player from the realm before removing the client session
				final Long dcPlayerId = this.remoteAddresses.get(entry.getKey());
				if (dcPlayerId != null) {
					final Realm playerRealm = this.findPlayerRealm(dcPlayerId);
					if (playerRealm != null) {
						final Player dcPlayer = playerRealm.getPlayer(dcPlayerId);
						if (dcPlayer != null) {
							log.info("[SERVER] Cleaning up stale session for player {} — reason: {}", dcPlayer.getName(), staleReason);
							// Save vault chests if player is in vault
							if (playerRealm.getMapId() == 1) {
								try {
									final List<ChestDto> chestsToSave = playerRealm.serializeChestsForSave();
									if (chestsToSave != null) {
										final String acctUuid = dcPlayer.getAccountUuid();
										final String dcName = dcPlayer.getName();
										ServerGameLogic.DATA_SERVICE
												.executePostAsync("/data/account/" + acctUuid + "/chest",
														chestsToSave, PlayerAccountDto.class)
												.thenAccept(resp -> log.info("[SERVER] Saved vault chests for DC'd player {}", dcName))
												.exceptionally(ex -> {
													log.error("[SERVER] Failed to save vault on DC for {}. Reason: {}",
															dcName, ex.getMessage());
													return null;
												});
									}
								} catch (Exception e) {
									log.error("[SERVER] Failed to save vault on DC for {}. Reason: {}",
											dcPlayer.getName(), e.getMessage());
								}
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
					this.remoteAddresses.remove(entry.getKey());
				} else {
					log.info("[SERVER] Cleaning up stale session {} (no mapped player) — reason: {}", entry.getKey(), staleReason);
				}
				entry.getValue().close();
				this.server.getClients().remove(entry.getKey());
			} catch (Exception e) {
				log.error("[SERVER] Failed to remove stale session. Reason:  {}", e);
			}
		});

		for (final Map.Entry<String, ClientSession> client : this.server.getClients().entrySet()) {
			try {
				final ClientSession session = client.getValue();
				// Inject bandwidth counters so write thread can track stats.
				// All four counters track POST-COMPRESSION (true wire) bytes.
				if (session.getSharedBytesWritten() == null) {
					session.setSharedBytesWritten(this.bytesWritten);
					session.setSharedBytesPerType(this.bytesWrittenByPacketType);
					session.setSharedBytesRead(this.bytesRead);
					session.setSharedBytesReadPerType(this.bytesReadByPacketType);
				}
				final Player player = this.getPlayerByRemoteAddress(client.getKey());
				if (player == null) {
					continue;
				}

				// Enqueue broadcast packets for deferred serialization on write thread
				for (final Packet packet : packetsToBroadcast) {
					session.enqueuePacket(packet);
				}

				// Enqueue player-specific packets for deferred serialization on write thread
				final ConcurrentLinkedQueue<Packet> playerPacketsToSend = this.playerOutboundPacketQueue
						.get(player.getId());
				if (playerPacketsToSend != null) {
					Packet packet;
					while ((packet = playerPacketsToSend.poll()) != null) {
						session.enqueuePacket(packet);
					}
				}
			} catch (Exception e) {
				//RealmManagerServer.log.error("[SERVER] Failed to enqueue data to Client. Reason: {}", e);
			}
		}

		// Print server write + read rates (kbit/s) — both report TRUE
		// on-the-wire bytes (post-compression for write; raw inbound bytes
		// for read, which arrive already-compressed if the client used the
		// compression flag). Sampled once per second by draining the
		// AtomicLong counters.
		if (Instant.now().toEpochMilli() - this.lastWriteSampleTime > 1000) {
			this.lastWriteSampleTime = Instant.now().toEpochMilli();
			final long written = this.bytesWritten.getAndSet(0);
			final long read = this.bytesRead.getAndSet(0);
			RealmManagerServer.log.info("[SERVER] current write rate = {} kbit/s (wire), read rate = {} kbit/s (wire)",
					(float) (written / 1024.0f) * 8.0f,
					(float) (read / 1024.0f) * 8.0f);
			final StringBuilder sb = new StringBuilder("[SERVER] Outbound by packet type: ");
			for (var entry : this.bytesWrittenByPacketType.entrySet()) {
				final long typeBytes = entry.getValue().getAndSet(0);
				if (typeBytes > 0) {
					sb.append(entry.getKey()).append("=")
					  .append(String.format("%.1f", (typeBytes / 1024.0f) * 8.0f))
					  .append("kbit/s ");
				}
			}
			RealmManagerServer.log.info(sb.toString());
			final StringBuilder sbR = new StringBuilder("[SERVER] Inbound by packet type:  ");
			for (var entry : this.bytesReadByPacketType.entrySet()) {
				final long typeBytes = entry.getValue().getAndSet(0);
				if (typeBytes > 0) {
					sbR.append(entry.getKey()).append("=")
					   .append(String.format("%.1f", (typeBytes / 1024.0f) * 8.0f))
					   .append("kbit/s ");
				}
			}
			RealmManagerServer.log.info(sbR.toString());
		}
		long nanosDiff = System.nanoTime() - startNanos;
		log.debug("Game data broadcast in {} nanos ({}ms}", nanosDiff, ((double) nanosDiff / (double) 1000000l));
	}

	// Enqueues outbound game packets every tick using:
	// - Spatial hash grid for O(1) neighbor lookups
	// - Tiered update rates (movement=64Hz, load=32Hz, update=16Hz, map=4Hz)
	public void enqueueGameData() {
		long startNanos = System.nanoTime();
		// CRITICAL: acquire must be outside the try and release MUST be in a
		// finally — previously the acquire/release were both inside the try
		// block, so any exception in the tick work leaked the lock and
		// deadlocked every subsequent acquire (including the next tick).
		this.acquireRealmLock();
		try {
			this.tickCounter++;

			final boolean doMovement = (this.tickCounter % MOVE_TICK_DIVISOR) == 0;
			final boolean doFullMovement = (this.tickCounter % MOVE_FULL_TICK_DIVISOR) == 0;
			final boolean doLoad = (this.tickCounter % LOAD_TICK_DIVISOR) == 0;
			final boolean doUpdate = (this.tickCounter % UPDATE_TICK_DIVISOR) == 0;
			final boolean doLoadMap = (this.tickCounter % LOADMAP_TICK_DIVISOR) == 0;
			final boolean doEnemyUpdate = (this.tickCounter % ENEMY_UPDATE_TICK_DIVISOR) == 0;

			for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
				Realm realm = realmEntry.getValue();

				// Update spatial grid positions once per tick for this realm
				realm.updateSpatialGrid();
				// Reset per-tick caches so the first viewer in this realm
				// builds the shared instances and subsequent viewers reuse
				// them. Major win for nexus (40 players seeing each other
				// -> 40× fewer allocations per shared entity per tick).
				realm.clearTickMovementCache();
				realm.clearTickStrippedUpdateCache();

				final Map<Player, String> toRemoveReasons = new LinkedHashMap<>();
				final float viewportRadius = 10 * GlobalConstants.BASE_TILE_SIZE;

				// Snapshot teleport flags before packet building clears them
				final Set<Long> teleportedPlayers = new HashSet<>();
				for (final Player tp : realm.getPlayers().values()) {
					if (tp.getTeleported()) teleportedPlayers.add(tp.getId());
				}

				for (final Map.Entry<Long, Player> player : realm.getPlayers().entrySet()) {
					if (player.getValue().isHeadless()) {
						continue;
					}
					try {
						realm = this.findPlayerRealm(player.getKey());
						if (realm == null) continue; // player disappeared between snapshot and processing
						final Vector2f playerCenter = player.getValue().getPos();

						// --- LoadMapPacket (4 Hz) ---
						if (doLoadMap) {
							final NetTile[] netTilesForPlayer = realm.getTileManager().getLoadMapTiles(player.getValue());
							final LoadMapPacket newLoadMapPacket = LoadMapPacket.from(realm.getRealmId(),
									(short) realm.getMapId(), realm.getTileManager().getMapWidth(),
									realm.getTileManager().getMapHeight(), netTilesForPlayer);
							if (this.playerLoadMapState.get(player.getKey()) == null) {
								this.playerLoadMapState.put(player.getKey(), newLoadMapPacket);
								this.enqueueServerPacket(player.getValue(), newLoadMapPacket);
							} else {
								final LoadMapPacket oldLoadMapPacket = this.playerLoadMapState.get(player.getKey());
								if (!oldLoadMapPacket.equals(newLoadMapPacket)) {
									final LoadMapPacket loadMapDiff = oldLoadMapPacket.difference(newLoadMapPacket);
									this.playerLoadMapState.put(player.getKey(), newLoadMapPacket);
									if (loadMapDiff != null) {
										this.enqueueServerPacket(player.getValue(), loadMapDiff);
									}
								}
							}
						}

						// --- Self UpdatePacket (heavy: inventory/stats/XP/name) ---
						// Only sent when inventory, stats, XP, or name actually change.
						if (doUpdate) {
							final UpdatePacket updatePacket = realm.getPlayerAsPacket(player.getValue().getId());
							final UpdatePacket oldSelfUpdate = this.playerUpdateState.get(player.getKey());
							if (oldSelfUpdate == null) {
								this.playerUpdateState.put(player.getKey(), updatePacket);
								this.enqueueServerPacket(player.getValue(), updatePacket);
							} else if (!oldSelfUpdate.equals(updatePacket, false)) {
								this.playerUpdateState.put(player.getKey(), updatePacket);
								this.enqueueServerPacket(player.getValue(), updatePacket);
							}

							// Nearby other players — send their updates TO this player (only when changed).
							// Use the realm's per-tick stripped-UpdatePacket cache so 40 viewers
							// in nexus all reuse a single allocation per other-player. Without
							// the cache this loop ran ~6400 reflection-heavy inventory builds
							// per second at 40 players, dwarfing the rest of the tick budget.
							final Player[] otherPlayers = realm.getPlayersInRadiusFast(playerCenter, viewportRadius);
							final int maxOtherUpdates = Math.min(otherPlayers.length, 20);
							for (int opi = 0; opi < maxOtherUpdates; opi++) {
								final Player other = otherPlayers[opi];
								if (other.getId() == player.getKey()) continue;
								try {
									final UpdatePacket stripped = realm.getOrBuildStrippedUpdate(other);
									if (stripped == null) continue;
									// Delta check: only send if this player's view of the other player changed
									final Map<Long, UpdatePacket> viewerCache = this.otherPlayerUpdateState
										.computeIfAbsent(player.getKey(), k -> new ConcurrentHashMap<>());
									final UpdatePacket oldOtherUpdate = viewerCache.get(other.getId());
									if (oldOtherUpdate == null || !oldOtherUpdate.equals(stripped, false)) {
										viewerCache.put(other.getId(), stripped);
										this.enqueueServerPacket(player.getValue(), stripped);
									}
								} catch (Exception ex) {
									log.error("[SERVER] Failed to build other player UpdatePacket. Reason: {}", ex);
								}
							}
						}

						// --- Self PlayerStatePacket (HP/MP/effects) ---
						// Check for effect changes every tick (must be immediate for movement sync).
						// HP/MP-only changes throttled to 8Hz via doUpdate.
						{
							final PlayerStatePacket statePacket =
								PlayerStatePacket.from(player.getValue());
							final PlayerStatePacket oldState =
								this.playerStateState.get(player.getKey());
							if (oldState == null) {
								this.playerStateState.put(player.getKey(), statePacket);
								this.enqueueServerPacket(player.getValue(), statePacket);
							} else {
								boolean effectsChanged = !Arrays.equals(
									oldState.getEffectIds(), statePacket.getEffectIds());
								if (effectsChanged) {
									// Effects changed — send immediately (affects client movement prediction)
									this.playerStateState.put(player.getKey(), statePacket);
									this.enqueueServerPacket(player.getValue(), statePacket);
								} else if (doUpdate && !oldState.equalsState(statePacket)) {
									// HP/MP changed — throttle to 8Hz
									this.playerStateState.put(player.getKey(), statePacket);
									this.enqueueServerPacket(player.getValue(), statePacket);
								}
							}
						}

						// --- LoadPacket (32 Hz) - per-player spatial grid query ---
						// Fast path: when only bullets/loot changed, ship a tiny delta
						// (empty players/enemies/portals arrays). Slow path: full
						// snapshot when the entity set actually changed OR every
						// FULL_SNAPSHOT_INTERVAL_MS (~3s) for self-heal against drops.
						// Cuts ~90% of LoadPacket bandwidth in spam-shoot scenarios.
						if (doLoad) {
							// Pass player ID for soulbound loot filtering
							final LoadPacket loadPacket = realm.getLoadPacketCircularFast(playerCenter, viewportRadius, player.getKey());
							final long nowMs = System.currentTimeMillis();
							if (this.playerLoadState.get(player.getKey()) == null) {
								this.playerLoadState.put(player.getKey(), loadPacket);
								this.playerLastFullSnapshotMs.put(player.getKey(), nowMs);
								this.enqueueServerPacket(player.getValue(), loadPacket);
							} else {
								final LoadPacket oldLoad = this.playerLoadState.get(player.getKey());
								final Long lastFull = this.playerLastFullSnapshotMs.get(player.getKey());
								final boolean periodicFullDue = lastFull == null
									|| (nowMs - lastFull) >= FULL_SNAPSHOT_INTERVAL_MS;
								final boolean entitySetSame = oldLoad.entitySetEquals(loadPacket);
								if (entitySetSame && !periodicFullDue) {
									// FAST PATH — bullets/loot only.
									final LoadPacket bulletDelta = oldLoad.bulletAndLootDelta(loadPacket);
									if (!bulletDelta.isEmpty()) {
										this.enqueueServerPacket(player.getValue(), bulletDelta);
									}
									final UnloadPacket bulletUnload = oldLoad.bulletUnloadDifference(loadPacket);
									if (bulletUnload.isNotEmpty()) {
										this.enqueueServerPacket(player.getValue(), bulletUnload);
									}
									this.playerLoadState.put(player.getKey(), loadPacket);
								} else if (periodicFullDue) {
									// PERIODIC SELF-HEAL — full snapshot every 3s so a dropped
									// delta packet self-recovers within one cycle.
									final LoadPacket toSend = oldLoad.combine(loadPacket);
									this.playerLoadState.put(player.getKey(), loadPacket);
									this.playerLastFullSnapshotMs.put(player.getKey(), nowMs);
									if (!toSend.isEmpty()) {
										this.enqueueServerPacket(player.getValue(), toSend);
									}
									final UnloadPacket unloadDelta = filterUnloadAgainstRealm(
											oldLoad.difference(loadPacket), realm);
									if (unloadDelta.isNotEmpty()) {
										this.enqueueServerPacket(player.getValue(), unloadDelta);
										for (long unloadedEnemy : unloadDelta.getEnemies()) {
											this.enemyUpdateState.remove(unloadedEnemy);
										}
									}
								} else if (!oldLoad.equals(loadPacket)) {
									// ENTITY-SET CHANGE PATH — emit ONLY new entities (true delta)
									// instead of the full 11-player snapshot. Saves ~90% on slow-
									// path bandwidth when bots cross each other's visibility
									// boundaries; self-heal is preserved by the periodic refresh above.
									final LoadPacket toSend = oldLoad.combineDelta(loadPacket);
									this.playerLoadState.put(player.getKey(), loadPacket);
									if (!toSend.isEmpty()) {
										this.enqueueServerPacket(player.getValue(), toSend);
									}
									final UnloadPacket unloadDelta = filterUnloadAgainstRealm(
											oldLoad.difference(loadPacket), realm);
									if (unloadDelta.isNotEmpty()) {
										this.enqueueServerPacket(player.getValue(), unloadDelta);
										for (long unloadedEnemy : unloadDelta.getEnemies()) {
											this.enemyUpdateState.remove(unloadedEnemy);
										}
									}
								}
							}
						}

						// --- ObjectMovePacket: dead reckoning with tiered check rates ---
						// Inner zone checked at 32Hz, full viewport at 16Hz.
						// Only entities whose actual position diverges from the client's
						// predicted position (based on last-sent pos+vel) are transmitted.
						// Send PlayerPosAckPacket when moving (every tick) or periodically when idle
						// (~10Hz idle acks) so high-latency clients get stop confirmation.
						final boolean isMoving = player.getValue().getDx() != 0 || player.getValue().getDy() != 0;
						final boolean periodicIdleAck = !isMoving && (this.tickCounter % 6 == 0);
						if (isMoving || teleportedPlayers.contains(player.getKey()) || periodicIdleAck) {
							this.enqueueServerPacket(player.getValue(),
								PlayerPosAckPacket.from(
									player.getValue().getLastProcessedInputSeq(),
									player.getValue().getPos().x,
									player.getValue().getPos().y));
						}

						if (doMovement) {
							final float moveRadius = doFullMovement ? viewportRadius : viewportRadius * 0.5f;
							// Build a fresh ObjectMovePacket centered on THIS player. Same
							// reasoning as the LoadPacket: cells can hold multiple players
							// at different positions, and reusing one player's perspective
							// causes incorrect movement updates for others.
							final ObjectMovePacket movePacket =
									realm.getGameObjectsAsPacketsCircularFast(playerCenter, moveRadius);
							if (movePacket != null) {
								Map<Long, EntityMotionState> drState = this.playerDeadReckonState.get(player.getKey());
								if (drState == null) {
									drState = new HashMap<>();
									this.playerDeadReckonState.put(player.getKey(), drState);
								}

								final float tickDuration = 1.0f;

								final List<NetObjectMovement> corrections = new ArrayList<>();
								for (final NetObjectMovement m : movePacket.getMovements()) {
									// Skip local player — their position comes via PlayerPosAckPacket
									if (m.getEntityId() == player.getKey()
											&& !teleportedPlayers.contains(player.getKey())) {
										continue;
									}
									// Dead reckoning: skip ANY entity whose actual state matches the
									// client's velocity-extrapolated prediction. The webclient
									// now extrapolates non-local players + enemies by velocity
									// (dx*64*dt) every frame in updateInterpolation(), so steady-
									// velocity motion needs no per-tick server correction. Direction
									// changes / pos drift > 4px / 48-tick staleness still trigger sends.
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
									// Send as standard ObjectMovePacket for webclient compatibility.
									// TODO: send CompactMovePacket (packet 25) once webclient supports it
									// for an additional ~44% per-entity size reduction.
									final ObjectMovePacket correctionPacket = ObjectMovePacket.from(
										corrections.toArray(new NetObjectMovement[0]));
									this.enqueueServerPacket(player.getValue(), correctionPacket);
								}
							}

							// Enemy UpdatePackets (16 Hz) - extract enemy IDs from move packet
							if (doEnemyUpdate && movePacket != null) {
								final Set<Long> nearEnemyIds = new HashSet<>();
								for (NetObjectMovement m : movePacket.getMovements()) {
									if (m.getEntityType() == EntityType.ENEMY.getEntityTypeId()) {
										nearEnemyIds.add(m.getEntityId());
									}
								}
								for (Long enemyId : nearEnemyIds) {
									final UpdatePacket updatePacket0 = realm.getEnemyAsPacket(enemyId);
									final UpdatePacket oldState = this.enemyUpdateState.get(enemyId);
									boolean doSend = false;
									if (oldState == null) {
										this.enemyUpdateState.put(enemyId, updatePacket0);
										doSend = true;
									} else if (!oldState.equals(updatePacket0, true)) {
										this.enemyUpdateState.put(enemyId, updatePacket0);
										doSend = true;
									}
									if (doSend) {
										this.enqueueServerPacket(player.getValue(), updatePacket0);
									}
									// Send enemy PlayerStatePacket when HP or effects change (own diff, not tied to UpdatePacket)
									final Enemy nearEnemy = realm.getEnemy(enemyId);
									if (nearEnemy != null) {
										final PlayerStatePacket enemyState =
											PlayerStatePacket.from(nearEnemy);
										final PlayerStatePacket cachedEnemyState =
											this.playerStateState.get(enemyId);
										if (cachedEnemyState == null || !cachedEnemyState.equalsState(enemyState)) {
											this.playerStateState.put(enemyId, enemyState);
											this.enqueueServerPacket(player.getValue(), enemyState);
										}
									}
								}
							}
						}

						// Heartbeat timeout check (every tick)
						final Long playerLastHeartbeatTime = this.playerLastHeartbeatTime.get(player.getKey());
						if (playerLastHeartbeatTime != null
								&& ((Instant.now().toEpochMilli() - playerLastHeartbeatTime) > 10000)) {
							long elapsed = Instant.now().toEpochMilli() - playerLastHeartbeatTime;
							toRemoveReasons.put(player.getValue(), "heartbeat timeout (" + elapsed + "ms since last heartbeat)");
						}

					} catch (Exception e) {
						RealmManagerServer.log.error("[SERVER] Failed to build game data for Player {}. Reason: {}",
								player.getKey(), e);
					}
				}

				for (Map.Entry<Player, String> entry : toRemoveReasons.entrySet()) {
					this.disconnectPlayer(entry.getKey(), entry.getValue());
				}

				// Reset contentsChanged AFTER all players processed
				// (was inside player loop before — caused Player B to miss updates)
				for (LootContainer lc : realm.getLoot().values()) {
					lc.setContentsChanged(false);
				}
			}

			long nanosDiff = System.nanoTime() - startNanos;
			log.debug("[SERVER] Game data enqueued in {} nanos ({}ms)", nanosDiff,
					((double) nanosDiff / (double) 1000000l));
		} catch (Exception e) {
			log.error("[SERVER] Failed to enqueue game data. Reason: {}", e.getMessage(), e);
		} finally {
			// ALWAYS release the lock, even on exception, to prevent deadlock
			this.releaseRealmLock();
		}
	}

	// Maximum packets to process per tick across all clients.
	// Prevents ability spam from 25+ bots from starving the tick thread.
	private static final int MAX_PACKETS_PER_TICK = 200;

	// Packet types that get priority processing every tick — never throttled,
	// never dropped, regardless of how saturated the rest of the queue is.
	// CommandPacket is here so admin/console commands like /killbots stay
	// responsive even when the server is overloaded with player input
	// (otherwise a 40-player non-spam test floods the queue with moves and
	// the operator can't recover the box).
	private static final Set<Class<? extends Packet>> PRIORITY_PACKETS = Set.of(
			PlayerShootPacket.class,
			PlayerMovePacket.class,
			HeartbeatPacket.class,
			CommandPacket.class
	);

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
				// Player Disconnect routine. Was previously `return` which
				// aborted the entire packet-pump loop the moment we hit a
				// stale unmapped session — so a half-open WS that
				// disconnected mid-handshake (no remoteAddresses mapping
				// yet) would block every other client's packets indefinitely
				// until the dead session aged out, including a fresh login
				// from the same user trying to re-enter the game.
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
			}
		}

		// Pass 1: Process ALL priority packets (shoot/move/heartbeat/command —
		// always responsive, no cap).
		for (final Packet packet : priorityQueue) {
			processPacket(packet);
		}

		// Pass 2: Process normal packets up to the per-tick cap. Any packets
		// beyond the cap are DEFERRED to the next tick (re-queued back into
		// the session's inbound queue) instead of silently dropped — losing
		// inventory operations / trade requests etc. under load was what
		// made admin recovery impossible during a 40-player stress test.
		final int normalCap = Math.max(0, MAX_PACKETS_PER_TICK - priorityQueue.size());
		final int processed = Math.min(normalQueue.size(), normalCap);
		for (int i = 0; i < processed; i++) {
			processPacket(normalQueue.get(i));
		}
		if (processed < normalQueue.size()) {
			// Re-queue the overflow back to its source session so it lands
			// at the front of next tick's priority/normal split. Use the
			// packet's srcIp (set when the packet was parsed in
			// ClientSession.parsePackets) to find the right session.
			for (int i = processed; i < normalQueue.size(); i++) {
				final Packet overflow = normalQueue.get(i);
				final ClientSession session = this.server.getClients().get(overflow.getSrcIp());
				if (session != null && !session.isShutdownProcessing()) {
					session.getPacketQueue().add(overflow);
				}
			}
		}
	}

	private void processPacket(final Packet packet) {
		try {
			packet.setSrcIp(packet.getSrcIp());
			final List<MethodHandle> packetHandles = this.userPacketCallbacksServer.get(packet.getId());
			if (packetHandles != null) {
				for (MethodHandle handler : packetHandles) {
					try {
						handler.invokeExact(this, packet);
					} catch (Throwable e) {
						log.error("[SERVER] Failed to invoke packet callback. Reason: {}", e);
					}
				}
			}
			if (this.packetCallbacksServer.get(packet.getClass()) == null) {
				final List<MethodHandle> callBackHandles = this.userPacketCallbacksServer.get(packet.getId());
				if (callBackHandles != null) {
					for (MethodHandle callBack : callBackHandles) {
						try {
							callBack.invokeExact(this, packet);
						} catch (Throwable e) {
							log.error("[SERVER] Failed to invoke user packet callback. Reason: {}", e.getMessage());
						}
					}
				}
			} else {
				this.packetCallbacksServer.get(packet.getClass()).accept(this, packet);
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
				if (playerRealm.getMapId() == 1) {
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
			if (realm.isOverworld() && realm.getMapId() != 1) {
				return realm;
			}
		}
		// Last resort: return the first realm that isn't a vault
		for (final Realm realm : this.realms.values()) {
			if (realm.getMapId() != 1) {
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
		// Iterate the players map directly. The previous version queried the
		// unified spatial grid with the chaseRange limit and filtered for
		// players, but the grid holds players + enemies + bullets together —
		// at 5K enemies in a small cluster, queryRadius returned thousands
		// of candidates per call (2500 AI calls/tick × ~5K candidates each
		// = ~12M iterations/tick), dwarfing any savings versus the linear
		// scan. Player counts are bounded (~40 max), so a flat iteration is
		// strictly cheaper here.
		for (final Player player : targetRealm.getPlayers().values()) {
			// /hide: enemies don't target hidden admins.
			if (!includeHidden && player.isHiddenFromOthers()) continue;
			final float dx = player.getPos().x - pos.x;
			final float dy = player.getPos().y - pos.y;
			final float distSq = dx * dx + dy * dy;
			if (distSq < bestSq) {
				bestSq = distSq;
				bestPlayer = player;
			}
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
	}

	private void registerPacketCallback(final Class<? extends Packet> packetId, final BiConsumer<RealmManagerServer, Packet> callback) {
		this.packetCallbacksServer.put(packetId, callback);
	}

	// Updates all game objects on the server
	public void update(double time) {
		this.updPlayersNanos = 0L;
		this.updEnemiesNanos = 0L;
		this.updBulletsNanos = 0L;
		this.updTailNanos = 0L;
		// For each world on the server
		for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
			final Realm realm = realmEntry.getValue();
			// Update player specific game objects — run inline, these are fast per-player ops
			final long pStart = System.nanoTime();
			for (final Map.Entry<Long, Player> player : realm.getPlayers().entrySet()) {
				final Player p = realm.getPlayer(player.getValue().getId());
				if (p == null) {
					continue;
				}
				this.processBulletHit(realm.getRealmId(), p);
				p.update(time);
				p.removeExpiredEffects();
				this.movePlayer(realm.getRealmId(), p);
			}
			this.updPlayersNanos += System.nanoTime() - pStart;
			// Once per tick: update enemies, then bullets. Iterating the maps
			// directly avoids 200+ instanceof+cast dispatches per tick that
			// the old getAllGameObjects() path incurred. bulletScale is also
			// computed ONCE per realm tick (instead of per-bullet) so we drop
			// 12 800 nanoTime() syscalls/sec at 200 bullets in flight.
			final long nowNanos = System.nanoTime();
			final long lastNanos = this.lastBulletUpdateNanos.getOrDefault(realm.getRealmId(), nowNanos);
			final float bulletDt = Math.min((nowNanos - lastNanos) / 1_000_000_000.0f, 0.1f);
			this.lastBulletUpdateNanos.put(realm.getRealmId(), nowNanos);
			final float bulletScale = bulletDt * 64.0f;

			// Stagger enemy AI decisions across ticks. Each enemy gets its
			// AI processed once every ENEMY_AI_TICK_DIVISOR ticks (effective
			// AI rate 32 Hz), spread by entity id so per-tick load is even.
			// MOVEMENT APPLICATION (tickMove) still runs every tick (64 Hz)
			// using the dx/dy set by the most recent AI call — without this
			// split, staggering would cause enemies to visibly stutter
			// between AI ticks. removeExpiredEffects also runs every tick
			// (millisecond-scale timing, cheap length scan).
			//
			// SLEEP optimization: any enemy with no player inside its
			// chaseRange is "dormant" — we skip update() entirely and zero
			// its velocity (preventing drift). For 10K stationary enemies
			// with one player nearby, this turns 10K heavy AI calls/tick
			// into ~50 heavy + 9950 cheap dist checks. Snapshot the player
			// list ONCE per tick to avoid recreating an iterator per enemy.
			final long eStart = System.nanoTime();
			final int aiTick = this.tickCounter & (ENEMY_AI_TICK_DIVISOR - 1);
			final int moveFarTick = (int) (this.tickCounter & (ENEMY_MOVE_FAR_DIVISOR - 1));
			final Player[] activePlayers = realm.getPlayers().values().toArray(new Player[0]);
			final int playerCount = activePlayers.length;
			for (final Enemy enemy : realm.getEnemies().values()) {
				// Single distance pass classifies the enemy:
				//   visible — within viewport of ANY player -> full 64Hz move
				//   awake   — within chaseRange of ANY player but not visible ->
				//             AI staggered + movement only every Nth tick
				//   dormant — outside chaseRange of every player -> no work
				boolean awake = false;
				boolean visible = false;
				if (playerCount > 0) {
					final float ex = enemy.getPos().x;
					final float ey = enemy.getPos().y;
					final float chaseRangeSq = (float) enemy.getChaseRange() * enemy.getChaseRange();
					for (int i = 0; i < playerCount; i++) {
						final float pdx = activePlayers[i].getPos().x - ex;
						final float pdy = activePlayers[i].getPos().y - ey;
						final float dsq = pdx * pdx + pdy * pdy;
						if (dsq <= VIEWPORT_RADIUS_SQ) {
							awake = true;
							visible = true;
							break;
						}
						if (dsq <= chaseRangeSq) {
							awake = true;
							// don't break — keep looking for a viewport-close player
						}
					}
				}
				if (!awake) {
					if (enemy.getDx() != 0f || enemy.getDy() != 0f) {
						enemy.setDx(0);
						enemy.setDy(0);
					}
					enemy.removeExpiredEffects();
					continue;
				}
				if ((enemy.getId() & (ENEMY_AI_TICK_DIVISOR - 1)) == aiTick) {
					enemy.update(realm.getRealmId(), this, time);
				}
				// Visible enemies always move; off-screen awake enemies move
				// every ENEMY_MOVE_FAR_DIVISOR-th tick. Stutter doesn't matter
				// for entities no client is rendering.
				if (visible || (enemy.getId() & (ENEMY_MOVE_FAR_DIVISOR - 1)) == moveFarTick) {
					enemy.tickMove(realm);
				}
				enemy.removeExpiredEffects();
			}
			this.updEnemiesNanos += System.nanoTime() - eStart;
			final long bStart = System.nanoTime();
			for (final Bullet bullet : realm.getBullets().values()) {
				bullet.update(bulletScale);
			}
			this.updBulletsNanos += System.nanoTime() - bStart;
		}

		final long tailStart = System.nanoTime();
		this.removeExpiredBullets();
		this.removeExpiredLootContainers();
		this.removeExpiredPortals();
		for (final Realm realm : this.realms.values()) {
			realm.processPoisonThrows(this);
			realm.processTraps(this);
			realm.processPoisonDots(this);
			realm.processDecoys(this);
		}

		// Broadcast global player positions for minimap (1 Hz) — only if any position changed
		if (this.tickCounter % 64 == 0) {
			for (final Map.Entry<Long, Realm> realmEntry : this.realms.entrySet()) {
				final Realm realm = realmEntry.getValue();
				if (realm.getPlayers().isEmpty()) {
					this.lastGlobalPositions.remove(realmEntry.getKey());
					continue;
				}
				final NetPlayerPosition[] positions = realm.getPlayers().values().stream()
						.map(NetPlayerPosition::from)
						.toArray(NetPlayerPosition[]::new);
				// Delta check: skip broadcast if no position has changed
				final NetPlayerPosition[] lastPositions = this.lastGlobalPositions.get(realmEntry.getKey());
				boolean changed = (lastPositions == null) || (lastPositions.length != positions.length);
				if (!changed) {
					for (int i = 0; i < positions.length; i++) {
						if (positions[i].getPlayerId() != lastPositions[i].getPlayerId()
								|| positions[i].getX() != lastPositions[i].getX()
								|| positions[i].getY() != lastPositions[i].getY()
								|| positions[i].isTeleportable() != lastPositions[i].isTeleportable()) {
							changed = true;
							break;
						}
					}
				}
				if (changed) {
					this.lastGlobalPositions.put(realmEntry.getKey(), positions);
					final GlobalPlayerPositionPacket minimapPacket =
							GlobalPlayerPositionPacket.from(positions);
					for (final Player p : realm.getPlayers().values()) {
						this.enqueueServerPacket(p, minimapPacket);
					}
				}
			}
		}

		// Periodic enemy respawn in overworld (every 1920 ticks ~30s)
		if (this.tickCounter % 1920 == 0) {
			for (final Realm realm : this.realms.values()) {
				if (realm.isOverworld() && realm.getMapId() != 1 && !realm.getPlayers().isEmpty()) {
					realm.respawnEnemies(50);
				}
			}
		}

		// Periodic cleanup: remove empty dungeon/vault realms (every 128 ticks ~2s)
		if (this.tickCounter % 128 == 0) {
			// Collect realm IDs that are referenced as a sourceRealmId by any
			// active dungeon. These must stay alive so the boss-drop exit portal
			// can link back to them when the dungeon boss is killed.
			final Set<Long> referencedAsSource = new HashSet<>();
			for (final Realm r : this.realms.values()) {
				if (r.getSourceRealmId() != 0) {
					referencedAsSource.add(r.getSourceRealmId());
				}
			}

			final List<Long> realmIdsToRemove = new ArrayList<>();
			for (final Map.Entry<Long, Realm> entry : this.realms.entrySet()) {
				final Realm r = entry.getValue();
				if (!r.getPlayers().isEmpty()) continue;

				// Vault realms (mapId=1): always remove when empty
				if (r.getMapId() == 1) {
					realmIdsToRemove.add(entry.getKey());
				}
				// Non-shared realms (dungeon instances): remove when empty,
				// BUT keep alive if any child dungeon still references this
				// realm as its source (the player needs to return here via
				// the boss exit portal).
				else if (!r.isShared() && !referencedAsSource.contains(entry.getKey())) {
					realmIdsToRemove.add(entry.getKey());
				}
			}
			for (Long id : realmIdsToRemove) {
				log.info("[SERVER] Cleaning up empty realm {}", id);
				final Realm removed = this.realms.remove(id);
				if (removed != null) {
					removed.setShutdown(true);
				}
			}
		}
		this.updTailNanos += System.nanoTime() - tailStart;
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

		// Process ALL queued inputs this tick. At high ping, inputs arrive in
		// bursts — processing all of them prevents server position from drifting
		// behind the client. Capped at 8 per tick (125ms catch-up) to prevent abuse.
		if (p.getInputQueue() == null) p.setInputQueue(new ConcurrentLinkedQueue<>());
		while (!p.getInputQueue().isEmpty() && (int) p.getInputQueue().peek()[0] <= p.getLastProcessedInputSeq()) {
		    p.getInputQueue().poll();
		}

		final Realm targetRealm = this.realms.get(realmId);
		int processed = 0;
		while (!p.getInputQueue().isEmpty() && processed < 8) {
		    float[] nextInput = p.getInputQueue().poll();
		    p.setCurrentVx(nextInput[1]);
		    p.setCurrentVy(nextInput[2]);
		    p.setLastProcessedInputSeq((int) nextInput[0]);
		    this.applyMovementTick(targetRealm, p);
		    processed++;
		}
		// If no queued inputs, run one tick with the last (vx, vy) (coast)
		if (processed == 0) {
		    this.applyMovementTick(targetRealm, p);
		}

		// Calculate if we should apply ground damage
		if (targetRealm.getTileManager().collidesDamagingTile(p)) {
			final Long lastDamageTime = this.playerGroundDamageState.get(p.getId());
			if (lastDamageTime == null || (Instant.now().toEpochMilli() - lastDamageTime) > 450) {
				int damageToInflict = 30 + Realm.RANDOM.nextInt(15);
				this.sendTextEffectToPlayer(p, TextEffect.DAMAGE, "-" + damageToInflict);
				p.setHealth(p.getHealth() - damageToInflict);
				this.playerGroundDamageState.put(p.getId(), Instant.now().toEpochMilli());
			}
		}
	}

	/** Applies one movement tick for a player using their current (vx, vy). */
	private void applyMovementTick(final Realm targetRealm, final Player p) {
		float vx = p.getCurrentVx();
		float vy = p.getCurrentVy();

		// Defensively normalise: client SHOULD send a unit vector, but if it
		// sends a longer one we clamp magnitude to 1 so movement speed can't
		// exceed the configured per-tick step.
		final float mag = (float) Math.sqrt(vx * vx + vy * vy);
		if (mag > 1.0f) {
		    vx /= mag;
		    vy /= mag;
		}

		float tilesPerSec = 4.0f + 5.6f * (p.getComputedStats().getSpd() / 75.0f);
		if (p.hasEffect(StatusEffectType.SPEEDY)) tilesPerSec *= 1.5f;
		if (p.hasEffect(StatusEffectType.SLOWED)) tilesPerSec *= 0.5f;
		final float spd = tilesPerSec * 32.0f / 64.0f;

		// Unit-vector-driven movement gives full diagonal speed when intended:
		// a unit vector at 45° has |vx|=|vy|=√2/2, so applying spd to each
		// component produces the same total step length as a cardinal move.
		// The dirFlags-era explicit √2/2 diagonal scaling is no longer needed.
		final boolean moving = mag > 0.001f;
		p.setDx(moving ? vx * spd : 0f);
		p.setDy(moving ? vy * spd : 0f);

		// Animation/facing flags derived from vx/vy. Threshold at 0.1 keeps tiny
		// off-axis components (e.g. vx=0.07 from a 4° camera tilt) from flipping
		// the facing every frame.
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
	// desired location if applicable
	public void useAbility(final long realmId, final long playerId, final Vector2f pos) {
		final Realm targetRealm = this.realms.get(realmId);

		final Player player = targetRealm.getPlayer(playerId);
		if (player == null || player.getAbility() == null)
			return;
		final GameItem abilityItem = GameDataManager.GAME_ITEMS.get(player.getAbility().getItemId());
		if ((abilityItem == null))
			return;
		final Effect effect = abilityItem.getEffect();
		final Long lastAbilityUsage = this.playerAbilityState.get(playerId);
		if (lastAbilityUsage == null
				|| (Instant.now().toEpochMilli() - lastAbilityUsage >= effect.getCooldownDuration())) {
			this.playerAbilityState.put(playerId, Instant.now().toEpochMilli());
		} else {
			log.debug("Ability {} is on cooldown", abilityItem);
			return;
		}
		// Godmode (INVINCIBLE) = infinite mana
		if (!player.hasEffect(StatusEffectType.INVINCIBLE)) {
			if (player.getMana() < effect.getMpCost())
				return;
			player.setMana(player.getMana() - effect.getMpCost());
		}
		// If the ability is damaging (knight stun, archer arrow, wizard spell)
		// Resolve the projectile group if the item has a damage definition with a valid projectileGroupId.
		// Script-only abilities (e.g., scepter chain lightning, necromancer skull) may have no damage
		// or a damage with projectileGroupId -1, meaning no projectiles should be created.
		final ProjectileGroup group = (abilityItem.getDamage() != null)
				? GameDataManager.PROJECTILE_GROUPS.get(abilityItem.getDamage().getProjectileGroupId())
				: null;

		final CombatModifiers abilityCm =
				CombatModifiers.fromItem(player.getInventory()[1]);

		if (((abilityItem.getDamage() != null) && (abilityItem.getEffect() != null) && (group != null))) {

			final Vector2f dest = new Vector2f(pos.x, pos.y);

			Vector2f source = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			final float angle = Bullet.getAngle(source, dest);

			for (final Projectile p : group.getProjectiles()) {
				final short offset = (short) (p.getSize() / (short) 2);
				short rolledDamage = player.getInventory()[1].getDamage().getInRange();
				rolledDamage += player.getComputedStats().getAtt();
				rolledDamage = applyCombatDamageMods(rolledDamage, abilityCm);
				if (p.getPositionMode() != ProjectilePositionMode.TARGET_PLAYER) {
					source = dest;
				}
				// Symmetric fan around the aim line — see ServerGameLogic
				// shoot logic for the same fix. Aim hits the center of
				// the spread regardless of how many extra projectiles
				// the player has gemmed in.
				{
					final int totalBullets = 1 + abilityCm.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = angle + Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = applyCombatDamageMods(rolledDamage, abilityCm);
						spawnAbilityBullet(realmId, player, abilityItem.getDamage().getProjectileGroupId(), p,
								source.clone(-offset, -offset), baseA + deltaA, rolled, abilityCm);
					}
				}
			}
			// Apply self-effect if present (e.g., warrior helmet SPEEDY buff)
			if (effect.isSelf()) {
				player.addEffect(effect.getEffectId(), effect.getDuration());
			}

		} else if ((abilityItem.getDamage() != null) && (group != null)) {
			final Vector2f dest = new Vector2f(pos.x, pos.y);
			for (final Projectile p : group.getProjectiles()) {

				final short offset = (short) (p.getSize() / (short) 2);
				short rolledDamage = player.getInventory()[1].getDamage().getInRange();
				rolledDamage += player.getComputedStats().getAtt();
				rolledDamage = applyCombatDamageMods(rolledDamage, abilityCm);
				{
					final int totalBullets = 1 + abilityCm.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = applyCombatDamageMods(rolledDamage, abilityCm);
						spawnAbilityBullet(realmId, player, abilityItem.getDamage().getProjectileGroupId(), p,
								dest.clone(-offset, -offset), baseA + deltaA, rolled, abilityCm);
					}
				}
			}

			// If the ability is non damaging or script-only (rogue cloak, priest tome, sorcerer scepter)
		} else if (abilityItem.getEffect() != null) {
			// Special case for teleporting — validate destination with full hitbox check
			if (abilityItem.getEffect().getEffectId().equals(StatusEffectType.TELEPORT)
					&& !targetRealm.getTileManager().collidesAtPosition(pos, player.getSize())
					&& !targetRealm.getTileManager().isVoidTile(pos, 0, 0)) {
				player.setPos(pos);
			} else if (!abilityItem.getEffect().getEffectId().equals(StatusEffectType.TELEPORT)) {
				// Only apply effect to self if the effect is marked as self-targeting.
				// Non-self effects (e.g., Huntress trap) are handled by item scripts.
				if (abilityItem.getEffect().isSelf()) {
					player.addEffect(abilityItem.getEffect().getEffectId(), abilityItem.getEffect().getDuration());
				}
			}
		}
		// Invoke any item specific scripts
		final UseableItemScriptBase script = this.getItemScript(abilityItem.getItemId());
		if (script != null) {
			script.invokeItemAbility(targetRealm, player, abilityItem, pos);
		}
	}

	private static short applyCombatDamageMods(short base, CombatModifiers cm) {
		int dmg = base;
		if (cm.getDamagePct() != 0) dmg = dmg + (dmg * cm.getDamagePct()) / 100;
		if (cm.getCritChancePct() > 0) {
			final int roll = (int) (Math.random() * 100);
			if (roll < cm.getCritChancePct()) dmg = dmg * 2;
		}
		if (dmg < 0) dmg = 0;
		if (dmg > Short.MAX_VALUE) dmg = Short.MAX_VALUE;
		return (short) dmg;
	}

	private void spawnAbilityBullet(long realmId, Player player, int projectileGroupId, Projectile p,
			Vector2f src, float angle, short damage, CombatModifiers cm) {
		final Bullet b = this.addProjectile(realmId, 0L, player.getId(), projectileGroupId,
				p.getProjectileId(), src, angle, p.getSize(), p.getMagnitude(), p.getRange(),
				damage, false, p.getFlags(), p.getAmplitude(), p.getFrequency(), player.getId());
		if (b == null) return;
		final List<ProjectileEffect> merged = new ArrayList<>();
		if (p.getEffects() != null) merged.addAll(p.getEffects());
		if (cm != null) {
			for (CombatModifiers.OnHitEffect oh : cm.getOnHitEffects()) {
				final ProjectileEffect pe = new ProjectileEffect();
				pe.setEffectId((short) oh.getEffectId());
				pe.setDuration(oh.getDurationMs());
				merged.add(pe);
			}
		}
		if (!merged.isEmpty()) b.setEffects(merged);
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

		// Terrain hit FIRST: destroy bullets that enter walls before they can
		// hit entities on the other side
		this.proccessTerrainHit(realmId, p);

		// nearbyBullets was snapshot before proccessTerrainHit ran, so any bullet
		// whose center stepped into a collision tile this tick is still in the
		// list but has been removed from the realm. Skip those — otherwise we
		// apply damage from a bullet the client has already despawned (e.g. a
		// player hugging a tree gets hit by an "invisible" projectile).
		final Map<Long, Bullet> liveBullets = targetRealm.getBullets();

		// Pre-partition nearby bullets by source so we don't iterate the
		// "wrong half" against the wrong target. Previously we walked the
		// full bullet list against both players and enemies and let a
		// boolean flag filter inside proccessEnemyHit / processPlayerHit —
		// each call still paid the cost of a Map lookup + entity-state
		// check before bailing. Splitting upfront cuts collision-loop work
		// roughly in half during ability spam (where ~50% of nearby
		// bullets are enemy-side).
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

	
	/**
	 * Strip from an UnloadPacket any entity ids that are STILL ALIVE in the
	 * realm — those got dropped from the LoadPacket only because the per-
	 * packet cap was hit, not because they actually left visibility. Without
	 * this filter the client would explicitly delete cap-trimmed entities
	 * every tick and re-add them on the next slow-path snapshot, producing
	 * the visible "enemies and bullets flicker" artifact during dense
	 * stress tests (500+ enemies in a small area).
	 *
	 * Returns the same UnloadPacket reference with its arrays replaced —
	 * the upstream `enqueueServerPacket(...)` then ships only the entities
	 * that are truly gone. UnloadPacket fields are mutable via Lombok @Data.
	 */
	private static UnloadPacket filterUnloadAgainstRealm(UnloadPacket pkt, Realm realm) {
		if (pkt == null) return null;
		// Players: never trim — players that left a viewer's load really should be unloaded
		// (they may have walked far away). The client repopulates them via LoadPacket if they
		// come back. We don't have a "still in realm" backstop for players because moving out
		// of viewport is the normal case.
		final Long[] enemiesIn = pkt.getEnemies();
		if (enemiesIn != null && enemiesIn.length > 0) {
			final List<Long> kept = new ArrayList<>(enemiesIn.length);
			for (final Long id : enemiesIn) {
				if (realm.getEnemy(id) == null) kept.add(id);
			}
			if (kept.size() != enemiesIn.length) pkt.setEnemies(kept.toArray(new Long[0]));
		}
		final Long[] bulletsIn = pkt.getBullets();
		if (bulletsIn != null && bulletsIn.length > 0) {
			final List<Long> kept = new ArrayList<>(bulletsIn.length);
			for (final Long id : bulletsIn) {
				if (realm.getBullets().get(id) == null) kept.add(id);
			}
			if (kept.size() != bulletsIn.length) pkt.setBullets(kept.toArray(new Long[0]));
		}
		final Long[] containersIn = pkt.getContainers();
		if (containersIn != null && containersIn.length > 0) {
			final List<Long> kept = new ArrayList<>(containersIn.length);
			for (final Long id : containersIn) {
				if (realm.getLoot().get(id) == null) kept.add(id);
			}
			if (kept.size() != containersIn.length) pkt.setContainers(kept.toArray(new Long[0]));
		}
		final Long[] portalsIn = pkt.getPortals();
		if (portalsIn != null && portalsIn.length > 0) {
			final List<Long> kept = new ArrayList<>(portalsIn.length);
			for (final Long id : portalsIn) {
				if (realm.getPortals().get(id) == null) kept.add(id);
			}
			if (kept.size() != portalsIn.length) pkt.setPortals(kept.toArray(new Long[0]));
		}
		return pkt;
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
	private static boolean circleHit(final Bullet b, final GameObject e) {
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
	private static float difficultyDamageMult(final float difficulty, final boolean isDungeon) {
		final float threshold = isDungeon
				? GlobalConstants.DAMAGE_SCALE_DUNGEON_MIN_DIFFICULTY
				: GlobalConstants.DAMAGE_SCALE_MIN_DIFFICULTY;
		if (difficulty <= threshold) return 1.0f;
		final float knee = GlobalConstants.DAMAGE_SCALE_KNEE_DIFFICULTY;
		final float slope = GlobalConstants.DAMAGE_SCALE_PER_LEVEL;
		final float slopeAfter = GlobalConstants.DAMAGE_SCALE_PER_LEVEL_AFTER_KNEE;
		float mult;
		if (difficulty <= knee) {
			mult = 1.0f + slope * (difficulty - threshold);
		} else {
			mult = 1.0f + slope * (knee - threshold) + slopeAfter * (difficulty - knee);
		}
		return Math.min(mult, GlobalConstants.DAMAGE_SCALE_CAP);
	}

	private void processPlayerHit(final long realmId, final Bullet b, final Player p) {
		final Realm targetRealm = this.realms.get(realmId);
		final Player player = targetRealm.getPlayer(p.getId());
		if (player == null)
			return;
		if (circleHit(b, player) && b.isEnemy() && !b.isPlayerHit()) {
			final Stats stats = player.getComputedStats();
			b.setPlayerHit(true);
			// Apply difficulty damage scaling based on the source enemy's
			// stored difficulty. Dungeon instances get a one-level-earlier
			// threshold so a diff-2.0 dungeon hits harder than the diff-2.0
			// grasslands overworld (you asked for this).
			float rawDmg = b.getDamage();
			if (b.getSrcEntityId() != 0L) {
				final Enemy srcEnemy = targetRealm.getEnemies().get(b.getSrcEntityId());
				if (srcEnemy != null) {
					rawDmg *= difficultyDamageMult(srcEnemy.getDifficulty(),
							targetRealm.isDungeonInstance());
				}
			}
			final boolean armorPiercing = b.hasFlag(ProjectileFlag.ARMOR_PIERCING);
			final boolean armorBroken = player.hasEffect(StatusEffectType.ARMOR_BROKEN);
			// Defense can only mitigate 85% of incoming damage. Use Math.ceil
			// + a hard floor of 1 so low-damage projectiles (e.g. Pirate's 5
			// dmg) always chip for at least 1 — without ceil the short cast
			// would truncate 0.75 to 0 and a 7-def player would take nothing.
			final short minDmg = (short) Math.max(1, Math.ceil(rawDmg * 0.15f));
			short dmgToInflict;
			if (armorPiercing || armorBroken) {
				// Armor piercing/broken: full damage, ignore defense entirely
				dmgToInflict = (short) rawDmg;
			} else {
				dmgToInflict = (short) (rawDmg - stats.getDef());
				if (dmgToInflict < minDmg) {
					dmgToInflict = minDmg;
				}
			}

			// Cyan damage text when armor is pierced or broken
			final TextEffect dmgTextEffect = (armorPiercing || armorBroken) ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
			this.sendTextEffectToPlayer(player, dmgTextEffect, "-" + dmgToInflict);

			player.setHealth(player.getHealth() - dmgToInflict);
			targetRealm.getExpiredBullets().add(b.getId());
			targetRealm.removeBullet(b);
			// Apply on-hit status effects from projectile's effects list (data-driven with durations)
			if (b.getEffects() != null) {
				for (final ProjectileEffect pe : b.getEffects()) {
					final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
					if (effectType != null) {
						p.addEffect(effectType, pe.getDuration());
						if (effectType == StatusEffectType.PARALYZED) {
							p.setDx(0); p.setDy(0);
						}
						this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, effectType.name());
					}
				}
			}
			if (p.getDeath()) {
				this.playerDeath(targetRealm, player);

			}
		}
	}

	private void proccessEnemyHit(final long realmId, final Bullet b, final Enemy e) {
		final Realm targetRealm = this.realms.get(realmId);
		final EnemyModel model = GameDataManager.ENEMIES.get(e.getEnemyId());
		if (targetRealm.hasHitEnemy(b.getId(), e.getId()) || targetRealm.getExpiredEnemies().contains(e.getId()))
			return;
		// Enemies in STASIS or INVINCIBLE are invulnerable — all damage is nullified
		if (e.hasEffect(StatusEffectType.STASIS) || e.hasEffect(StatusEffectType.INVINCIBLE))
			return;
		if (circleHit(b, e) && !b.isEnemy()) {
			final boolean armorPiercing = b.hasFlag(ProjectileFlag.ARMOR_PIERCING);
			final boolean armorBroken = e.hasEffect(StatusEffectType.ARMOR_BROKEN);
			// 15% damage floor with Math.ceil + hard min of 1 so weak
			// shots still chip armored enemies — without ceil the short cast
			// truncated 0.75 to 0 and any def >= raw dealt nothing.
			final short minDmg = (short) Math.max(1, Math.ceil(b.getDamage() * 0.15));
			short dmgToInflict;
			if (armorPiercing || armorBroken) {
				// Armor piercing/broken: full damage, ignore defense entirely
				dmgToInflict = (short) b.getDamage();
			} else {
				dmgToInflict = (short) (b.getDamage() - model.getStats().getDef());
				if (dmgToInflict < minDmg) {
					dmgToInflict = minDmg;
				}
			}

			// Track damage for loot credit
			if (b.getSrcEntityId() != 0L && targetRealm.getOverseer() != null) {
				targetRealm.getOverseer().trackDamage(e.getId(), b.getSrcEntityId(), dmgToInflict);
			}

			if(b.getSrcEntityId() != 0l) {
				final Player fromPlayer = this.getPlayerById(b.getSrcEntityId());
				if(fromPlayer!=null &&  fromPlayer.hasEffect(StatusEffectType.DAMAGING)) {
					dmgToInflict = (short)(dmgToInflict * 1.5);
				}
			}
			// CURSED enemies take 25% more damage from all sources
			if (e.hasEffect(StatusEffectType.CURSED)) {
				dmgToInflict = (short)(dmgToInflict * 1.25);
			}

			targetRealm.hitEnemy(b.getId(), e.getId());
			e.setHealth(e.getHealth() - dmgToInflict);
			int maxHealth = (int) (model.getHealth() * e.getDifficulty());
			e.setHealthpercent((float) e.getHealth() / (float) maxHealth);
			// Lifesteal: heal the source player by % of dealt damage from any
			// gem-equipped item (weapon or ability slot). Capped at the player's
			// max HP via getComputedStats().
			if (b.getSrcEntityId() != 0L) {
				final Player healSrc = this.getPlayerById(b.getSrcEntityId());
				if (healSrc != null && dmgToInflict > 0) {
					int totalLifestealPct = 0;
					if (healSrc.getInventory()[0] != null) {
						totalLifestealPct += CombatModifiers
								.fromItem(healSrc.getInventory()[0]).getLifestealPct();
					}
					if (healSrc.getInventory()[1] != null) {
						totalLifestealPct += CombatModifiers
								.fromItem(healSrc.getInventory()[1]).getLifestealPct();
					}
					if (totalLifestealPct > 0) {
						final int heal = (dmgToInflict * totalLifestealPct) / 100;
						if (heal > 0) {
							final int maxHp = healSrc.getComputedStats().getHp();
							healSrc.setHealth(Math.min(maxHp, healSrc.getHealth() + heal));
						}
					}
				}
			}
			// Pierce: bows, quivers and stun shields pass through any number of
			// enemies, hitting each one once (hasHitEnemy de-dups). Without the
			// flag, the existing rule still applies — first enemy hit keeps the
			// bullet alive (one extra pierce), subsequent hits remove it.
			if (b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) {
				if (!b.isEnemyHit()) b.setEnemyHit(true);
			} else if (b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE) && !b.isEnemyHit()) {
				b.setEnemyHit(true);
			} else {
				targetRealm.getExpiredBullets().add(b.getId());
				targetRealm.removeBullet(b);
			}
			// Apply on-hit status effects from projectile's effects list (data-driven with durations)
			if (b.getEffects() != null) {
				for (final ProjectileEffect pe : b.getEffects()) {
					final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
					if (effectType != null) {
						e.addEffect(effectType, pe.getDuration());
						this.broadcastTextEffect(targetRealm, EntityType.ENEMY, e, TextEffect.PLAYER_INFO, effectType.name());
					}
				}
			}

			// Cyan damage text when armor is pierced or broken
			final TextEffect dmgTextEffect = (armorPiercing || armorBroken) ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
			this.broadcastTextEffect(targetRealm, EntityType.ENEMY, e, dmgTextEffect, "-" + dmgToInflict);
			if (e.getDeath()) {
				targetRealm.getExpiredBullets().add(b.getId());
				this.enemyDeath(targetRealm, e);
			}
		}
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

		if (!isEnemy && player != null) {
			damage = (short) (damage + player.getStats().getAtt());
		}

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

		final ProjectileGroup pg = GameDataManager.PROJECTILE_GROUPS.get(projectileGroupId);
		if (!isEnemy && player != null) {
			damage = (short) (damage + player.getStats().getAtt());
		}

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
			// Get players in the viewport of this enemy and increment their experience
			for (final Player player : targetRealm
					.getPlayersInBounds(targetRealm.getTileManager().getRenderViewPort(enemy))) {
				final int xpToGive = (int) (model.getXp() * enemy.getDifficulty());
				final long prevXp = player.getExperience();
				final boolean wasMaxLevel = GameDataManager.EXPERIENCE_LVLS.isMaxLvl(prevXp);
				final int prevLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(prevXp);
				final int levelsGained = player.incrementExperience(xpToGive);
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

			// Notify the overseer of the kill (handles taunts, event spawning)
			// NOTE: We get qualifying players BEFORE clearing damage tracking for soulbound loot
			final List<Long> qualifyingPlayerIds = (targetRealm.getOverseer() != null)
					? targetRealm.getOverseer().getQualifyingPlayers(enemy.getId())
					: new ArrayList<>();
			if (targetRealm.getOverseer() != null) {
				long killerId = targetRealm.getOverseer().getTopDamageDealer(enemy.getId());
				targetRealm.getOverseer().onEnemyKilled(enemy, killerId);
				targetRealm.getOverseer().clearDamageTracking(enemy.getId());
			}

			targetRealm.getExpiredEnemies().add(enemy.getId());
			targetRealm.clearHitMap();
			// Overseer handles repopulation now — skip legacy spawnRandomEnemy for overworld
			targetRealm.removeEnemy(enemy);

			// Boss-drop exit portal: runs BEFORE loot processing so it is never
			// skipped by an early return (e.g. missing loot table). Uses the
			// dungeonBossEnemyId stored on the realm at spawn time rather than
			// re-reading from MapModel, which is more reliable.
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

			// Try to get the loot model mapped by this enemyId
			final LootTableModel lootTable = GameDataManager.LOOT_TABLES.get(enemy.getEnemyId());
			if (lootTable == null) {
				log.warn("[SERVER] No loot table registered for enemy {}", enemy.getEnemyId());
				return;
			}

			// Soulbound loot system: each qualifying player gets their own loot roll.
			// Brown bags (consumables only) remain public and visible to all.
			// All other bag tiers (purple, cyan, white, boosted) are soulbound.
			final float diff = enemy.getDifficulty();
			final boolean upgradeEligible = diff > GlobalConstants.LOOT_TIER_UPGRADE_MIN_DIFFICULTY;
			final float upgradeChance = upgradeEligible
					? (GlobalConstants.LOOT_TIER_UPGRADE_BASE_PERCENT
							+ GlobalConstants.LOOT_TIER_UPGRADE_PER_DIFFICULTY * diff) / 100.0f
					: 0.0f;

			// If no qualifying players (e.g. solo kill or damage tracking disabled), 
			// use a single public drop (backwards compatible)
			if (qualifyingPlayerIds.isEmpty()) {
				dropLootForPlayer(targetRealm, enemy, lootTable, -1, diff, upgradeEligible, upgradeChance);
			} else {
				// Roll separate loot for each qualifying player
				for (Long playerId : qualifyingPlayerIds) {
					dropLootForPlayer(targetRealm, enemy, lootTable, playerId, diff, upgradeEligible, upgradeChance);
				}
			}

			// Portal drops: use dungeon graph if this realm has a nodeId.
			// Overworld/shared realms bypass graph filtering so that each
			// enemy's loot table directly controls which dungeon portals drop.
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
	private void playerDeath(final Realm targetRealm, final Player player) {
		try {
			final String remoteAddrDeath = this.getRemoteAddressMapReversed().get(player.getId());
			final boolean hasAmulet = player.getInventory()[3] != null
					&& player.getInventory()[3].getItemId() == 48;

			if (player.isHeadless() || player.isBot()) {
				// Bots/headless: drop grave and remove immediately
				if (hasAmulet) {
					player.getInventory()[3] = null;
					player.setHealth(1);
				} else {
					targetRealm.getExpiredPlayers().add(player.getId());
					final LootContainer graveLoot = new LootContainer(LootTier.GRAVE,
							player.getPos().clone(), player.getSlots(4, 12));
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
				player.getInventory()[3] = null;
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
						player.getPos().clone(), player.getSlots(4, 12));
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
		this.playerLoadState.remove(playerId);
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
		}
		// Clear cached provisions on disconnect
		ServerCommandHandler.PLAYER_PROVISION_CACHE.remove(playerId);
	}


	/**
	 * Data class for a deferred realm transition. The heavy realm generation
	 * (terrain, enemies, dungeon layout) runs on a worker thread. Once complete,
	 * the result is enqueued here and the tick thread integrates it: adds the
	 * realm, transfers the player, sends map/load packets.
	 */
	public static class PendingRealmTransition {
		public final Realm generatedRealm;
		public final Player player;
		public final Realm sourceRealm;
		public final Portal usedPortal;
		public PendingRealmTransition(Realm generatedRealm, Player player, Realm sourceRealm, Portal usedPortal) {
			this.generatedRealm = generatedRealm;
			this.player = player;
			this.sourceRealm = sourceRealm;
			this.usedPortal = usedPortal;
		}
	}

	public void enqueuePendingTransition(PendingRealmTransition transition) {
		this.pendingRealmTransitions.add(transition);
	}

	/**
	 * Drain completed realm generations and integrate them on the tick thread.
	 */
	public void processPendingTransitions() {
		PendingRealmTransition t;
		while ((t = this.pendingRealmTransitions.poll()) != null) {
			try {
				this.addRealm(t.generatedRealm);
				if (t.usedPortal != null) {
					t.usedPortal.setToRealmId(t.generatedRealm.getRealmId());
				}
				t.player.addEffect(StatusEffectType.INVINCIBLE, 4000);
				this.broadcastTextEffect(EntityType.PLAYER, t.player,
					TextEffect.PLAYER_INFO, "Invincible");
				t.generatedRealm.addPlayer(t.player);
				this.clearPlayerState(t.player.getId());
				this.invalidateRealmLoadState(t.generatedRealm);
				ServerGameLogic.sendImmediateLoadMap(this, t.generatedRealm, t.player);
				ServerGameLogic.onPlayerJoin(this, t.generatedRealm, t.player);
				log.info("[SERVER] Completed async realm transition for player {} -> realm {} (mapId={})",
					t.player.getName(), t.generatedRealm.getRealmId(), t.generatedRealm.getMapId());
			} catch (Exception e) {
				log.error("[SERVER] Failed to complete realm transition for player {}. Reason: {}",
					t.player.getName(), e.getMessage(), e);
			}
		}
	}

	/**
	 * Invalidate the LoadPacket cache for all players in a realm, forcing a full
	 * re-send on the next tick. Called when a player enters or leaves a realm so
	 * that existing clients immediately learn about the roster change.
	 */
	public void invalidateRealmLoadState(Realm realm) {
		for (final Long pid : realm.getPlayers().keySet()) {
			this.playerLoadState.remove(pid);
			this.playerLastFullSnapshotMs.remove(pid);
		}
	}

	/**
	 * Data class for a deferred realm-join operation. Worker threads create these
	 * after async authentication completes; the tick thread drains and executes
	 * them before building LoadPackets, guaranteeing no race with the delta logic.
	 */
	public static class PendingRealmJoin {
		public final Realm realm;
		public final Player player;
		public final String srcIp;
		public final ClientSession session;
		public final Packet loginResponse;
		public PendingRealmJoin(Realm realm, Player player, String srcIp, ClientSession session, Packet loginResponse) {
			this.realm = realm;
			this.player = player;
			this.srcIp = srcIp;
			this.session = session;
			this.loginResponse = loginResponse;
		}
	}

	/**
	 * Called by worker threads after async login auth completes.
	 * Queues the realm join to be processed on the tick thread.
	 */
	public void enqueuePendingJoin(PendingRealmJoin join) {
		this.pendingRealmJoins.add(join);
	}

	/**
	 * Called at the start of tick(), BEFORE processServerPackets / enqueueGameData.
	 * Drains all pending joins and adds players to their realms atomically on
	 * the tick thread, then invalidates load state so existing clients see them.
	 */
	public void processPendingJoins() {
		PendingRealmJoin join;
		while ((join = this.pendingRealmJoins.poll()) != null) {
			try {
				join.realm.addPlayer(join.player);
				this.invalidateRealmLoadState(join.realm);
				join.session.setHandshakeComplete(true);
				this.remoteAddresses.put(join.srcIp, join.player.getId());
				this.enqueueServerPacket(join.player, join.loginResponse);
				final Player toWelcome = join.player;
				final Realm welcomeRealm = join.realm;
				WorkerThread.runLater(() -> ServerGameLogic.onPlayerJoin(this, welcomeRealm, toWelcome), 2000);
				log.info("[SERVER] Processed pending realm join for player {}", join.player.getName());
			} catch (Exception e) {
				log.error("[SERVER] Failed to process pending realm join for player {}. Reason: {}",
					join.player.getName(), e.getMessage());
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

	// Adds a realm to the map of realms after trying to decorate
	// the realm terrain using any decorators, spawning static enemies and portals
	public void addRealm(final Realm realm) {
		this.tryDecorate(realm);
		realm.spawnStaticEnemies(realm.getMapId());
		// Spawn static portals defined in the map data
		final MapModel mapModel = GameDataManager.MAPS.get(realm.getMapId());
		if (mapModel != null && mapModel.getStaticPortals() != null) {
			for (final PortalModel sp : mapModel.getStaticPortals()) {
				try {
					final Portal portal = new Portal(Realm.RANDOM.nextLong(), (short) sp.getPortalId(),
							new Vector2f(sp.getX(), sp.getY()));
					portal.setNeverExpires();
					if (sp.getTargetNodeId() != null) {
						portal.setTargetNodeId(sp.getTargetNodeId());
						// If target is a shared node with an existing realm, link to it
						final DungeonGraphNode targetNode = GameDataManager.DUNGEON_GRAPH.get(sp.getTargetNodeId());
						if (targetNode != null && targetNode.isShared()) {
							this.findRealmForNode(sp.getTargetNodeId()).ifPresent(
									existing -> portal.setToRealmId(existing.getRealmId()));
						}
						// Non-shared nodes: toRealmId stays 0 — a new instance is created on first use
					}
					realm.addPortal(portal);
					log.info("[SERVER] Placed static portal {} at ({},{}) -> node '{}' in realm {}",
							sp.getPortalId(), sp.getX(), sp.getY(), sp.getTargetNodeId(), realm.getRealmId());
				} catch (Exception e) {
					log.error("[SERVER] Failed to place static portal. Reason: {}", e.getMessage());
				}
			}
		}
		// Overseer is ONLY attached to overworld map 2 (Beach). Each instance
		// gets its own overseer so multiple beach realms in the dungeon graph
		// have independent announcements / event spawning / population top-up.
		// Dungeon maps and static maps (nexus, vault) get no overseer.
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
		if (player.isHeadless() || player.isBot())
			return false;
		// Extra safety: never persist bot accounts even if flag wasn't set
		if (player.getAccountUuid() == null || player.getName() == null || player.getName().startsWith("Bot_"))
			return false;
		try {
			final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE
					.executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
			final Optional<CharacterDto> currentCharacter = account.getCharacters().stream()
					.filter(character -> character.getCharacterUuid().equals(player.getCharacterUuid())).findAny();
			if (currentCharacter.isPresent()) {
				final CharacterDto character = currentCharacter.get();
				final CharacterStatsDto newStats = player.serializeStats();
				final Set<GameItemRefDto> newItems = player.serializeItems();
				character.setItems(newItems);
				character.setStats(newStats);
				final CharacterDto savedStats = ServerGameLogic.DATA_SERVICE.executePost(
						"/data/account/character/" + character.getCharacterUuid(), character, CharacterDto.class);
				RealmManagerServer.log.info("[SERVER] Succesfully persisted user account {}",
						account.getAccountEmail());
			}
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to get player account. Reason: {}", e);
		}
		return true;
	}

	private void sendTextEffectToPlayer(final Player player, final TextEffect effect, final String text) {
		try {
			this.enqueueServerPacket(player, TextEffectPacket.from(EntityType.PLAYER, player.getId(), effect, text));
		} catch (Exception e) {
			RealmManagerServer.log.error("[SERVER] Failed to send TextEffect Packet to Player {}. Reason: {}",
					player.getId(), e);
		}
	}

	/**
	 * Broadcast a text effect to players near an entity. Finds the realm automatically.
	 * For callers that already have the realm, use the overload that accepts it directly.
	 */
	public void broadcastTextEffect(final EntityType entityType, final GameObject entity,
			final TextEffect effect, final String text) {
		final Realm realm = this.findPlayerRealm(entity.getId());
		if (realm == null) {
			// Entity might be an enemy — search all realms
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
		try {
			final TextEffectPacket packet = TextEffectPacket.from(entityType, entity.getId(), effect, text);
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

	/**
	 * Register a poison damage-over-time effect on an enemy.
	 * Delegates to the target realm's poison DoT system.
	 */
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

	/**
	 * Drops loot for a specific player (soulbound) or for everyone (public).
	 * 
	 * @param targetRealm The realm where loot will be dropped
	 * @param enemy The enemy that died
	 * @param lootTable The loot table to roll from
	 * @param soulboundPlayerId The player ID this loot is bound to, or -1 for public loot
	 * @param diff Enemy difficulty for tier upgrade chances
	 * @param upgradeEligible Whether tier upgrades are possible
	 * @param upgradeChance The chance for each item to be upgraded
	 */
	private void dropLootForPlayer(final Realm targetRealm, final Enemy enemy, 
			final LootTableModel lootTable, final long soulboundPlayerId,
			final float diff, final boolean upgradeEligible, final float upgradeChance) {
		
		// Roll loot drops from the loot table
		final List<GameItem> lootToDrop = lootTable.getLootDrop();
		
		// Guaranteed stat potion drops for dungeon bosses
		// Each qualifying player gets their own potion drops
		if (targetRealm.getDungeonBossEnemyId() > 0
				&& enemy.getEnemyId() == targetRealm.getDungeonBossEnemyId()) {
			final LootGroupModel statPotionGroup = GameDataManager.LOOT_GROUPS.get(0);
			if (statPotionGroup != null && !statPotionGroup.getPotentialDrops().isEmpty()) {
				for (int i = 0; i < 2; i++) {
					final int potionItemId = statPotionGroup.getPotentialDrops()
							.get(Realm.RANDOM.nextInt(statPotionGroup.getPotentialDrops().size()));
					final GameItem potion = GameDataManager.GAME_ITEMS.get(potionItemId);
					if (potion != null) {
						lootToDrop.add(potion);
					}
				}
			}
		}
		
		// Separate items into categories for bag determination
		final List<GameItem> consumableDrops = new ArrayList<>();  // Always public (brown bag)
		final List<GameItem> normalDrops = new ArrayList<>();      // Soulbound (various bags)
		final List<GameItem> boostedDrops = new ArrayList<>();     // Soulbound (boosted bag)
		
		for (final GameItem original : lootToDrop) {
			// Consumables go in public brown bags
			if (original.isConsumable()) {
				consumableDrops.add(original);
				continue;
			}
			
			GameItem toDrop = original;
			boolean wasUpgraded = false;
			if (upgradeEligible && Realm.RANDOM.nextFloat() < upgradeChance) {
				final GameItem upgraded = findUpgradedItem(original);
				if (upgraded != null) {
					toDrop = upgraded;
					wasUpgraded = true;
				}
			}
			if (wasUpgraded) {
				boostedDrops.add(toDrop);
			} else {
				normalDrops.add(toDrop);
			}
		}
		
		// Drop consumables in a public brown bag (visible to all)
		if (!consumableDrops.isEmpty()) {
			final LootContainer publicBag = new LootContainer(LootTier.BROWN,
					enemy.getPos().withNoise(64, 64),
					consumableDrops.toArray(new GameItem[0]));
			// Brown bags are always public - no soulbound
			targetRealm.addLootContainer(publicBag);
		}
		
		// Drop normal items in a soulbound bag (determineTier chooses PURPLE/CYAN/WHITE)
		if (!normalDrops.isEmpty()) {
			final LootContainer soulboundBag = new LootContainer(LootTier.BLUE,
					enemy.getPos().withNoise(64, 64),
					normalDrops.toArray(new GameItem[0]));
			soulboundBag.setSoulboundPlayerId(soulboundPlayerId);
			targetRealm.addLootContainer(soulboundBag);
		}
		
		// Drop boosted items in a separate soulbound boosted bag
		if (!boostedDrops.isEmpty()) {
			final LootContainer boostedBag = new LootContainer(LootTier.BOOSTED,
					enemy.getPos().withNoise(64, 64),
					boostedDrops.toArray(new GameItem[0]));
			boostedBag.setSoulboundPlayerId(soulboundPlayerId);
			targetRealm.addLootContainer(boostedBag);
			log.info("[SERVER] BOOSTED loot drop: {} upgraded item(s) from enemy {} (difficulty={}) for player {}",
					boostedDrops.size(), enemy.getEnemyId(), diff, 
					soulboundPlayerId == -1 ? "PUBLIC" : soulboundPlayerId);
		}
	}

	/**
	 * Attempts to find the same item type (slot + class) one tier higher.
	 * Returns null if no upgrade exists (already max tier, consumable, or untiered).
	 */
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
