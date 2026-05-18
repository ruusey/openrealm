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
import com.openrealm.game.entity.item.CombatModifiers;
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
	// Per-player authoritative ledger of which entity IDs the client has
	// loaded right now (replaces the old "last LoadPacket sent" snapshot).
	// Drives load/unload deltas: load = desired ∖ ledger, unload = ledger ∖
	// desired. Updated every tick by exactly what was enqueued, so a
	// cap-trimmed entity is simply "not yet loaded" — no spurious unloads,
	// no ghost entities. See PlayerLoadLedger for the full motivation.
	private Map<Long, PlayerLoadLedger> playerLoadLedger = new ConcurrentHashMap<>();
	// Phase 4 — party state (membership + pending invites). Exposed via
	// getPartyManager() so chat-command handlers and packet handlers can mutate.
	private final PartyManager partyManager = new PartyManager();

	// ─── Class passive tuning constants ───────────────────────────────────
	// Heavy Buffer "Guiding Light" — EMPOWERED bonus = floor(caster.WIS / N)
	// added to each of STR/SPD/DEX. Lower divisor = stronger aura. At 5 a
	// maxed (75 WIS) buffer grants +15 to all three offensive stats; at 10
	// it'd grant +7. Edit and rebuild to tune.
	private static final int GUIDING_LIGHT_WIS_DIVISOR = 5;
	// Heavy Debuffer "Precision Striker" — bullets from a player with this
	// passive ignore floor(caster.DEX / N) of the target's DEF before the
	// 15% damage floor is applied. ARMOR_PIERCING and ARMOR_BROKEN still
	// fully ignore DEF as before; this is partial pen that stacks beneath.
	// At 10 a maxed (75 DEX) striker pens 7 DEF.
	private static final int PRECISION_STRIKER_DEX_DIVISOR = 10;

	// Phase 4 — Necromancer Soul Harvest (#4 ult). Each cast spawns a vortex
	// field that lives for {@code expiresAt - now} ms; the tick loop drains
	// HP from enemies inside the field every {@code DRAIN_PERIOD_MS} and
	// heals allies in the same radius for the total HP sapped that tick.
	// One field per realm per caster — recasting replaces the previous.
	private static final class SoulHarvestField {
		final long realmId;
		final long casterId;
		final float x, y;
		final float radius;
		final long expiresAtMs;
		long lastTickMs;
		SoulHarvestField(long realmId, long casterId, float x, float y, float radius, long expiresAtMs) {
			this.realmId = realmId; this.casterId = casterId;
			this.x = x; this.y = y; this.radius = radius;
			this.expiresAtMs = expiresAtMs;
			this.lastTickMs = 0L;
		}
	}
	private final List<SoulHarvestField> soulHarvestFields = new ArrayList<>();

	// Ninja Blade Storm — visual-only orbiting shurikens around a player for
	// the buff duration. The DAMAGING status is what actually buffs damage;
	// this struct just keeps the renderer refreshed with the player's current
	// position so the blades follow them around as they move.
	private static final class BladeOrbitState {
		final long realmId;
		final long casterId;
		final long expiresAtMs;
		final byte tier;
		BladeOrbitState(long realmId, long casterId, long expiresAtMs, byte tier) {
			this.realmId = realmId; this.casterId = casterId;
			this.expiresAtMs = expiresAtMs; this.tier = tier;
		}
	}
	private final List<BladeOrbitState> bladeOrbitStates = new ArrayList<>();

	// Ninja Death Blossom — 5-second armor-piercing spiral at a cursor point.
	// Damage is split across 5 one-second ticks rather than instant. Visual
	// refreshes each tick so the spiral keeps spinning over the full duration.
	private static final class BladeBlenderField {
		final long realmId;
		final long casterId;
		final float x, y;
		final float radius;
		final long expiresAtMs;
		final int  damagePerTick;
		final byte tier;
		long lastTickMs;
		BladeBlenderField(long realmId, long casterId, float x, float y, float radius,
				long expiresAtMs, int damagePerTick, byte tier) {
			this.realmId = realmId; this.casterId = casterId;
			this.x = x; this.y = y; this.radius = radius;
			this.expiresAtMs = expiresAtMs;
			this.damagePerTick = damagePerTick;
			this.tier = tier;
			this.lastTickMs = 0L;
		}
	}
	private final List<BladeBlenderField> bladeBlenderFields = new ArrayList<>();
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
	/** Last time we force-cleared this viewer's per-other-player UpdatePacket
	 *  delta cache. Every {@link #VIEWER_UPDATE_REFRESH_MS} we wipe the map so
	 *  the next broadcast tick unconditionally re-sends the stripped
	 *  UpdatePacket for every nearby player. Self-heals the race where a
	 *  freshly-loaded viewer's first UpdatePacket landed before its
	 *  matching LoadPacket added the player (so getPlayer(id) returned null
	 *  and the update was silently dropped). Mirrors the periodic full
	 *  LoadPacket snapshot at {@link #FULL_SNAPSHOT_INTERVAL_MS}. */
	private Map<Long, Long> lastViewerUpdateRefreshMs = new ConcurrentHashMap<>();
	private static final long VIEWER_UPDATE_REFRESH_MS = 2000L;
	// Was 10s — bumped down to 2s so a freshly-joined client whose first
	// LoadPacket missed an entity (race against the per-viewer entitySetSame
	// delta gate) recovers within one cycle instead of staring at a blank
	// world for 10 full seconds. The webclient never noticed because it
	// receives entities via tile-stream chunks during LoadMap; the native
	// client's tile path is finished before LoadPacket starts streaming so
	// any delta gap is visible.
	private static final long FULL_SNAPSHOT_INTERVAL_MS = 2000L;
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
	// Running sum of tick durations (nanos) over the current 1-second sample
	// window. Divided by currentTickCount at the per-second log to report the
	// average tick time. Reset alongside currentTickCount.
	private long tickTimeAccumNanos = 0L;

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
	private static final int LOADMAP_TICK_DIVISOR = 12; // 64Hz / 12 ≈ 5.3Hz — was 16 (4Hz). Faster tile reveal as the player walks.
	private static final int ENEMY_UPDATE_TICK_DIVISOR = 4; // Enemy health bars at 16Hz — tightens hit-feedback latency (HP bar reacts within 1 frame of damage text). Profiled budget: ~1.2ms/tick at 500 enemies + 1500 bullets, plenty of room.
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
		} catch (Exception e) {
			// Throwable passed as the LAST arg with no {} placeholder so SLF4J
			// prints the full stack trace. Previous form ("Reason: {}", e) just
			// printed e.toString() → "ArrayIndexOutOfBoundsException: null"
			// with no clue what line threw.
			RealmManagerServer.log.error("Failed to process server tick", e);
		}
		final long tickTotal = System.nanoTime() - tickStart;
		this.currentTickCount++;
		this.tickTimeAccumNanos += tickTotal;
		if (Instant.now().toEpochMilli() - this.tickSampleTime > 1000) {
			this.tickSampleTime = Instant.now().toEpochMilli();
			final double avgMs = this.currentTickCount > 0
				? (this.tickTimeAccumNanos / (double) this.currentTickCount) / 1_000_000.0
				: 0.0;
			log.info("[SERVER] ticks this second: {} (avg tick time: {} ms)",
				this.currentTickCount, String.format("%.2f", avgMs));
			this.currentTickCount = 0;
			this.tickTimeAccumNanos = 0L;
		}
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
									final List<ChestDto> storageToSave = playerRealm.serializePotionStorageForSave(dcPlayer.getId());
									if (storageToSave != null) {
										final String acctUuid = dcPlayer.getAccountUuid();
										final String dcName = dcPlayer.getName();
										ServerGameLogic.DATA_SERVICE
												.executePostAsync("/data/account/" + acctUuid + "/potion-storage",
														storageToSave, PlayerAccountDto.class)
												.thenAccept(resp -> log.info("[SERVER] Saved potion storage for DC'd player {}", dcName))
												.exceptionally(ex -> {
													log.error("[SERVER] Failed to save potion storage on DC for {}. Reason: {}",
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
					this.cleanupPartyOnDisconnect(dcPlayerId);
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
							//
							// Self-heal: every VIEWER_UPDATE_REFRESH_MS we WIPE this viewer's
							// per-other-player delta cache so the next iteration unconditionally
							// re-sends every nearby UpdatePacket. Without this, a freshly-loaded
							// viewer who races a peer's first UpdatePacket (UPDATE arrived before
							// LOAD added the peer to the realm map → handleUpdate dropped it)
							// stayed permanently blank for that peer. The cache wipe forces a
							// fresh send within ~2s and the client's pending-update buffer
							// applies it correctly.
							final long nowMsForRefresh = System.currentTimeMillis();
							final Long lastRefresh = this.lastViewerUpdateRefreshMs.get(player.getKey());
							if (lastRefresh == null || (nowMsForRefresh - lastRefresh) >= VIEWER_UPDATE_REFRESH_MS) {
								final Map<Long, UpdatePacket> existingCache = this.otherPlayerUpdateState.get(player.getKey());
								if (existingCache != null) existingCache.clear();
								this.lastViewerUpdateRefreshMs.put(player.getKey(), nowMsForRefresh);
							}

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

						// --- LoadPacket (32 Hz) — per-player ledger-based delta sync ---
						// The ledger is the authoritative set of IDs the server believes
						// the client currently has. Each tick:
						//   desired   = uncapped visible IDs at this player's position
						//   toLoad    = desired ∖ ledger     (new entities)
						//   toUnload  = ledger  ∖ desired    (entities that left)
						// Caps apply ONLY to toLoad. Cap-trimmed IDs simply stay out of
						// the ledger and the wire — they get picked up on a future tick.
						// Reconcile (every FULL_SNAPSHOT_INTERVAL_MS) re-asserts the
						// full desired set so a dropped packet self-heals within one
						// cycle. No realm-existence filter — anything that left the
						// realm naturally falls out of `desired` and the diff emits a
						// correct unload.
						if (doLoad) {
							final long nowMs = System.currentTimeMillis();
							PlayerLoadLedger ledger = this.playerLoadLedger.get(player.getKey());
							if (ledger == null) {
								ledger = new PlayerLoadLedger();
								this.playerLoadLedger.put(player.getKey(), ledger);
								this.playerLastFullSnapshotMs.put(player.getKey(), nowMs);
							}
							final Realm.VisibleIds desired = realm.getVisibleIdsCircularFast(
									playerCenter, viewportRadius, player.getKey());

							final Long lastFull = this.playerLastFullSnapshotMs.get(player.getKey());
							final boolean reconcileDue = lastFull == null
									|| (nowMs - lastFull) >= FULL_SNAPSHOT_INTERVAL_MS;

							// Delta sets — start with strict set diff.
							final Set<Long> playersToLoad    = setDiff(desired.players,    ledger.players);
							final Set<Long> enemiesToLoad    = setDiff(desired.enemies,    ledger.enemies);
							final Set<Long> bulletsToLoad    = setDiff(desired.bullets,    ledger.bullets);
							final Set<Long> containersToLoad = setDiff(desired.containers, ledger.containers);
							final Set<Long> portalsToLoad    = setDiff(desired.portals,    ledger.portals);

							final Set<Long> playersToUnload    = setDiff(ledger.players,    desired.players);
							final Set<Long> enemiesToUnload    = setDiff(ledger.enemies,    desired.enemies);
							final Set<Long> bulletsToUnload    = setDiff(ledger.bullets,    desired.bullets);
							final Set<Long> containersToUnload = setDiff(ledger.containers, desired.containers);
							final Set<Long> portalsToUnload    = setDiff(ledger.portals,    desired.portals);

							// Re-send loot containers whose contents mutated this tick
							// (pickups/inserts). Client merges the new payload into the
							// existing entry — no unload+reload round-trip needed.
							for (final Long id : desired.containers) {
								if (containersToLoad.contains(id)) continue;
								final LootContainer lc = realm.getLoot().get(id);
								if (lc != null && lc.getContentsChanged()) containersToLoad.add(id);
							}

							if (reconcileDue) {
								// Periodic reconcile: treat the client as empty for loads
								// and emit a full snapshot of the desired set, plus
								// unloads for everything in the ledger no longer desired.
								// Survives any dropped delta within FULL_SNAPSHOT_INTERVAL_MS.
								playersToLoad.clear();    playersToLoad.addAll(desired.players);
								enemiesToLoad.clear();    enemiesToLoad.addAll(desired.enemies);
								bulletsToLoad.clear();    bulletsToLoad.addAll(desired.bullets);
								containersToLoad.clear(); containersToLoad.addAll(desired.containers);
								portalsToLoad.clear();    portalsToLoad.addAll(desired.portals);
								this.playerLastFullSnapshotMs.put(player.getKey(), nowMs);
							}

							// Apply caps to LOAD side only. Closest-first deterministic
							// trim. Cap-trimmed IDs stay out of the ledger and arrive on
							// a future tick — never produce a spurious unload.
							final Set<Long> cappedEnemyLoad = capByDistance(
									enemiesToLoad, playerCenter, realm.getEnemies(),
									MAX_NEW_ENEMIES_PER_LOAD);
							final Set<Long> cappedBulletLoad = capBulletsWithPlayerPriority(
									bulletsToLoad, playerCenter, realm.getBullets(),
									MAX_NEW_BULLETS_PER_LOAD);

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
								this.enqueueServerPacket(player.getValue(), loadPkt);
							}
							if (unloadPkt.isNotEmpty()) {
								this.enqueueServerPacket(player.getValue(), unloadPkt);
								for (final Long unloadedEnemy : unloadPkt.getEnemies()) {
									this.enemyUpdateState.remove(unloadedEnemy);
								}
								// Drop cached other-player UpdatePackets for any peer
								// that just left this viewer's load — so when they
								// re-enter later, the first UpdatePacket isn't
								// wrongly suppressed by a stale delta cache entry.
								final Map<Long, UpdatePacket> otherCache =
									this.otherPlayerUpdateState.get(player.getKey());
								if (otherCache != null) {
									for (final Long unloadedPlayer : unloadPkt.getPlayers()) {
										otherCache.remove(unloadedPlayer);
									}
								}
							}

							// Update the ledger by EXACTLY what we just shipped.
							// Loads recorded from the capped/intent sets — anything the
							// hydrator dropped (entity removed between query and build)
							// is effectively unloaded already, so a stale ledger entry
							// here is harmless: next tick's desired diff will re-emit a
							// no-op unload that the client ignores.
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

							// Enemy UpdatePackets (16 Hz) - extract enemy IDs from move packet.
							// Gated on the per-player load ledger so we never send an
							// update/state packet for an enemy this client doesn't have
							// loaded — the ledger is the only authority on what the
							// client is currently rendering.
							if (doEnemyUpdate && movePacket != null) {
								final PlayerLoadLedger viewerLedger =
									this.playerLoadLedger.get(player.getKey());
								final Set<Long> nearEnemyIds = new HashSet<>();
								for (NetObjectMovement m : movePacket.getMovements()) {
									if (m.getEntityType() == EntityType.ENEMY.getEntityTypeId()) {
										nearEnemyIds.add(m.getEntityId());
									}
								}
								for (Long enemyId : nearEnemyIds) {
									if (viewerLedger == null || !viewerLedger.enemies.contains(enemyId)) continue;
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

						// Heartbeat timeout check (every tick). Bumped from 10s to
						// 60s so the webclient's heartbeat (1 Hz setInterval)
						// surviving browser inactive-tab throttling doesn't
						// false-positive the timeout when the player alt-tabs
						// briefly. Browsers throttle setInterval to >=1 Hz in
						// inactive tabs but Chrome's "intensive throttling"
						// after ~5 min reduces that to ~1/min — the 60s
						// budget covers the common alt-tab case while still
						// reaping genuinely dead clients within a minute.
						final Long playerLastHeartbeatTime = this.playerLastHeartbeatTime.get(player.getKey());
						if (playerLastHeartbeatTime != null
								&& ((Instant.now().toEpochMilli() - playerLastHeartbeatTime) > 60000)) {
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
				this.cleanupPartyOnDisconnect(dcPlayerId);
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
			// Single dispatch path: reflection-registered @PacketHandlerServer
			// handlers take priority. If none exist for this packet class, fall
			// back to the hardcoded fast-path registry. The previous shape ran
			// reflection handlers TWICE for any packet that lacked a hardcoded
			// entry — every InvestSkillPoint cast was applied twice (orange
			// pip jumped 2 per right-click), and any other reflection-only
			// packet had the same bug.
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
				// Resolve any in-progress cast whose timer has elapsed. We
				// clear currentCast BEFORE re-entering useAbility so the
				// resolution path sees isCasting()==false (otherwise the
				// "reject input-driven re-casts while casting" gate at the
				// top of useAbility would refuse our own resolution).
				final CastState cs = p.getCurrentCast();
				if (cs != null && cs.getEndTickMs() <= Instant.now().toEpochMilli()) {
					p.setCurrentCast(null);
					this.useAbility(realm.getRealmId(), p.getId(),
							new Vector2f(cs.getWorldTargetX(), cs.getWorldTargetY()),
							(byte) cs.getSlot(), true);
				}
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
				// Friendly-aura hook (Enemy67 healer) — runs every tick for
				// every awake scripted enemy, sidestepping the AI stagger and
				// the attack()/processAttacks gates (DEX cooldown, attackRange,
				// closest-player INVISIBLE bail). Combat scripts leave this
				// as the default no-op.
				final EnemyScriptBase tickScript = this.getEnemyScript(enemy.getEnemyId());
				if (tickScript != null) {
					try {
						tickScript.tick(realm, enemy);
					} catch (Exception ex) {
						log.error("Enemy script tick() failed for enemyId={}: {}", enemy.getEnemyId(), ex);
					}
				}
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
			realm.processClones(this);
		}

		// Passive tick — runs every 8 ticks (~125ms). Refreshes:
		//   • Priest Protective Aura (11003): PROTECTED on every player in 5
		//     tiles of the priest, including the priest themselves.
		//   • Paladin Holy Resolve (11006): BRACED on the paladin while their
		//     HP is below 50% of max. The buff naturally drops when they heal
		//     back above the threshold because we stop refreshing it.
		// Both refresh with a short duration (~800ms) so the buff decays
		// cleanly when the condition stops holding.
		if (this.tickCounter % 8 == 0) {
			final float AURA_RADIUS = 320f;
			final float AURA_RADIUS_SQ = AURA_RADIUS * AURA_RADIUS;
			final long  REFRESH_MS = 800L;
			for (final Realm realm : this.realms.values()) {
				if (realm.getPlayers().isEmpty()) continue;
				for (final Player p : realm.getPlayers().values()) {
					final PassiveAbility pa = p.getClassPassive();
					if (pa == null) continue;
					if (pa.getId() == 11003) {
						// Priest Protective Aura
						final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
						for (final Player ally : realm.getPlayers().values()) {
							final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
							final float dx = ac.x - pc.x, dy = ac.y - pc.y;
							if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
							ally.addEffect(StatusEffectType.PROTECTED, REFRESH_MS);
						}
					} else if (pa.getId() == 11006) {
						// Paladin Holy Resolve — refresh BRACED while HP < 50%
						// of computed max. ARMORED/BRACED in Player.getComputedStats
						// is mutually exclusive (ARMORED > BRACED), so this won't
						// downgrade a Brace cast.
						final int maxHp = p.getComputedStats() != null ? p.getComputedStats().getHp() : p.getHealth();
						if (maxHp > 0 && p.getHealth() * 2 < maxHp) {
							p.addEffect(StatusEffectType.BRACED, REFRESH_MS);
						}
					} else if (pa.getId() == 11015) {
						// Heavy Buffer "Guiding Light" — refresh EMPOWERED_STR and
						// EMPOWERED_DEX on every party member within range INCLUDING
						// the buffer itself (realm.getPlayers() contains the caster).
						// Magnitude = floor(caster.WIS / GUIDING_LIGHT_WIS_DIVISOR).
						// Two parallel statuses so the UI stacks two icons above
						// the player ("Atk+10" and "Dex+10") instead of one combined
						// label — design call from the buffer playtest.
						final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
						final short bonus = (short) Math.max(0,
								p.getComputedStats().getWis() / GUIDING_LIGHT_WIS_DIVISOR);
						for (final Player ally : realm.getPlayers().values()) {
							final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
							final float dx = ac.x - pc.x, dy = ac.y - pc.y;
							if (dx * dx + dy * dy > AURA_RADIUS_SQ) continue;
							// Floating "+N STR" / "+N DEX" cast text — only on the
							// first application (transition from no-EMPOWERED to
							// has-EMPOWERED). Without this guard we'd flood the
							// screen with text every 125ms tick while standing
							// inside the aura. We check EMPOWERED_STR as the proxy
							// for "did this player just enter the aura" since both
							// statuses always apply together.
							if (bonus > 0 && !ally.hasEffect(StatusEffectType.EMPOWERED_STR)) {
								this.broadcastTextEffect(realm, EntityType.PLAYER, ally,
										TextEffect.HEAL, "+" + bonus + " STR");
								this.broadcastTextEffect(realm, EntityType.PLAYER, ally,
										TextEffect.HEAL, "+" + bonus + " DEX");
							}
							ally.addEffect(StatusEffectType.EMPOWERED_STR, REFRESH_MS, bonus);
							ally.addEffect(StatusEffectType.EMPOWERED_DEX, REFRESH_MS, bonus);
						}
					} else if (pa.getId() == 11008) {
						// Necromancer Necrotic Aura — continuously refreshes CURSED
						// (1.25× damage taken) on every enemy in range. Range is
						// ~1.6 tiles (≈1/3 of the priest aura), so the necro has
						// to actively kite into melee to use it instead of just
						// camping a pack from across the screen.
						final float NECRO_AURA_R = AURA_RADIUS / 3f;
						final float NECRO_AURA_SQ = NECRO_AURA_R * NECRO_AURA_R;
						final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
						for (final Enemy enemy : realm.getEnemies().values()) {
							if (enemy == null || enemy.getDeath()) continue;
							if (enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;
							final float dx = enemy.getPos().x - pc.x;
							final float dy = enemy.getPos().y - pc.y;
							if (dx * dx + dy * dy > NECRO_AURA_SQ) continue;
							enemy.addEffect(StatusEffectType.CURSED, REFRESH_MS);
						}
					}
				}
			}
		}

		// Necromancer Soul Harvest — vortex field tick. Every tick we evict
		// expired fields and refresh the visual; every ~1s we drain HP from
		// enemies inside and heal allies in radius for the total sapped.
		if (!this.soulHarvestFields.isEmpty()) {
			final long now = Instant.now().toEpochMilli();
			final long DRAIN_PERIOD_MS = 1000L;
			final int  DRAIN_PER_TICK  = 50;
			final Iterator<SoulHarvestField> it = this.soulHarvestFields.iterator();
			while (it.hasNext()) {
				final SoulHarvestField f = it.next();
				if (now >= f.expiresAtMs) { it.remove(); continue; }
				final Realm realm = this.realms.get(f.realmId);
				if (realm == null) { it.remove(); continue; }
				// Refresh persistent visual every 12 ticks (~190ms). Tier 6 =
				// purple/violet tint for the necro soul-drain look.
				if (this.tickCounter % 12 == 0) {
					this.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
							CreateEffectPacket.EFFECT_SOUL_VORTEX, f.x, f.y, f.radius, (short) 240, (byte) 6));
				}
				// 1Hz drain + heal pulse.
				if (now - f.lastTickMs < DRAIN_PERIOD_MS) continue;
				f.lastTickMs = now;
				final float rSq = f.radius * f.radius;
				int totalSapped = 0;
				final List<Enemy> dead = new ArrayList<>();
				for (final Enemy enemy : realm.getEnemies().values()) {
					if (enemy.getDeath()) continue;
					if (enemy.hasEffect(StatusEffectType.STASIS)
							|| enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;
					final float dx = enemy.getPos().x - f.x;
					final float dy = enemy.getPos().y - f.y;
					if (dx * dx + dy * dy > rSq) continue;
					// Armor-piercing — sapped damage ignores defense.
					final short dmg = (short) Math.min(Short.MAX_VALUE, DRAIN_PER_TICK);
					enemy.setHealth(enemy.getHealth() - dmg);
					this.broadcastTextEffect(realm, EntityType.ENEMY, enemy,
							TextEffect.ARMOR_BREAK, "-" + dmg);
					totalSapped += dmg;
					if (enemy.getHealth() <= 0) dead.add(enemy);
				}
				for (final Enemy e : dead) this.enemyDeath(realm, e);
				if (totalSapped > 0) {
					// Heal every ally in the AURA RING — outside the vortex
					// itself, within ~2× the vortex radius. Standing inside
					// the meat grinder is for the enemies; allies sit on the
					// edge soaking the souls being thrown outward.
					final float healOuterR  = f.radius * 2.5f;
					final float healOuterSq = healOuterR * healOuterR;
					for (final Player ally : realm.getPlayers().values()) {
						if (ally == null) continue;
						final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
						final float adx = ac.x - f.x, ady = ac.y - f.y;
						final float distSq = adx * adx + ady * ady;
						if (distSq < rSq) continue;                  // inside vortex — no heal
						if (distSq > healOuterSq) continue;          // too far away
						final int maxHp = ally.getComputedStats() != null ? ally.getComputedStats().getHp() : ally.getHealth();
						final int newHp = Math.min(maxHp, ally.getHealth() + totalSapped);
						final int actual = newHp - ally.getHealth();
						if (actual > 0) {
							ally.setHealth(newHp);
							this.broadcastTextEffect(realm, EntityType.PLAYER, ally,
									TextEffect.HEAL, "+" + actual);
							// Soul streamer — chain-lightning-style trail from
							// the vortex center to the ally being healed. Read
							// as "soul essence absorbed". EFFECT_LIFE_DRAIN is
							// already a red/violet drain visual (renderer case
							// 23), which fits the soul-harvest palette.
							this.enqueueServerPacketToRealm(realm, CreateEffectPacket.lineEffect(
									CreateEffectPacket.EFFECT_LIFE_DRAIN,
									f.x, f.y, ac.x, ac.y, (short) 500, (byte) 6));
						}
					}
				}
			}
		}

		// Ninja Blade Storm — visual-only orbiting shurikens. Re-broadcast
		// every 16 ticks (~250ms) at the caster's CURRENT position with a
		// per-packet duration of 600ms so consecutive packets generously
		// overlap and the orbit reads as continuous as the player moves.
		// The renderer dedupes by effect type (only the newest BLADE_ORBIT
		// actually paints), so stacking packets doesn't cause flicker.
		if (!this.bladeOrbitStates.isEmpty() && this.tickCounter % 16 == 0) {
			final long now = Instant.now().toEpochMilli();
			final Iterator<BladeOrbitState> it = this.bladeOrbitStates.iterator();
			while (it.hasNext()) {
				final BladeOrbitState s = it.next();
				if (now >= s.expiresAtMs) { it.remove(); continue; }
				final Realm realm = this.realms.get(s.realmId);
				if (realm == null) { it.remove(); continue; }
				final Player caster = realm.getPlayer(s.casterId);
				if (caster == null) { it.remove(); continue; }
				final Vector2f pc = caster.getPos().clone(caster.getSize() / 2, caster.getSize() / 2);
				this.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLADE_ORBIT, pc.x, pc.y, 56f, (short) 600, s.tier));
			}
		}

		// Ninja Death Blossom — spiraling blade-blender DoT. Re-emit the
		// visual every 6 ticks and drain HP from enemies inside every 1s.
		// Damage is armor-piercing (the ult tag is armor_pierce). Total
		// damage is spread across 5 one-second ticks.
		if (!this.bladeBlenderFields.isEmpty()) {
			final long now = Instant.now().toEpochMilli();
			final long DRAIN_PERIOD_MS = 1000L;
			final Iterator<BladeBlenderField> it = this.bladeBlenderFields.iterator();
			while (it.hasNext()) {
				final BladeBlenderField f = it.next();
				if (now >= f.expiresAtMs) { it.remove(); continue; }
				final Realm realm = this.realms.get(f.realmId);
				if (realm == null) { it.remove(); continue; }
				// Re-emit the spiral visual every 16 ticks (~250ms) with a
				// generous 600ms per-packet duration. Same dedupe-by-type
				// approach as blade_orbit keeps the spiral readable instead
				// of stacking blade groups out of phase with each other.
				if (this.tickCounter % 16 == 0) {
					this.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
							CreateEffectPacket.EFFECT_BLADE_BLENDER, f.x, f.y, f.radius, (short) 600, f.tier));
				}
				if (now - f.lastTickMs < DRAIN_PERIOD_MS) continue;
				f.lastTickMs = now;
				final float rSq = f.radius * f.radius;
				final List<Enemy> dead = new ArrayList<>();
				for (final Enemy enemy : realm.getEnemies().values()) {
					if (enemy.getDeath()) continue;
					if (enemy.hasEffect(StatusEffectType.STASIS)
							|| enemy.hasEffect(StatusEffectType.INVINCIBLE)) continue;
					final float dx = enemy.getPos().x - f.x;
					final float dy = enemy.getPos().y - f.y;
					if (dx * dx + dy * dy > rSq) continue;
					final short dmg = (short) Math.min(Short.MAX_VALUE, f.damagePerTick);
					enemy.setHealth(enemy.getHealth() - dmg);
					this.broadcastTextEffect(realm, EntityType.ENEMY, enemy,
							TextEffect.ARMOR_BREAK, "-" + dmg);
					if (enemy.getHealth() <= 0) dead.add(enemy);
				}
				for (final Enemy e : dead) this.enemyDeath(realm, e);
			}
		}

		// Phase 4 — Party MVP. Refresh every 32 ticks (~0.5s) so teammate
		// HP/MP bars track combat in near-real-time. Also drop expired
		// invites on the same cadence so the pending list doesn't leak.
		if (this.tickCounter % 32 == 0) {
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

		// Knight Phalanx Dome — every tick, any player with PHALANX_DOME has a
		// 96-px radius sphere around them that destroys any incoming enemy
		// bullet that enters. Cheap because the dome's lifetime is short (~3.5s)
		// and active casters are usually rare. Also re-emits the persistent
		// visual every 12 ticks (~190ms) so it reads as a steady bubble.
		final float PHALANX_RADIUS = 128f;
		final float PHALANX_RADIUS_SQ = PHALANX_RADIUS * PHALANX_RADIUS;
		final boolean refreshDomeVisual = (this.tickCounter % 12 == 0);
		for (final Realm realm : this.realms.values()) {
			if (realm.getPlayers().isEmpty()) continue;
			for (final Player p : realm.getPlayers().values()) {
				if (!p.hasEffect(StatusEffectType.PHALANX_DOME)) continue;
				final Vector2f pc = p.getPos().clone(p.getSize() / 2, p.getSize() / 2);
				// Destroy enemy bullets inside the dome.
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
				if (refreshDomeVisual) {
					// Persistent BLUE shield dome (tier 1 = light blue tint).
					// Duration matches refresh cadence so the dome holds steady.
					this.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
							CreateEffectPacket.EFFECT_SHIELD_DOME, pc.x, pc.y, PHALANX_RADIUS, (short) 240, (byte) 1));
				}
			}
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
				// Death check — Entity.getDeath() returns true when health<=0,
				// but nothing fires playerDeath() from ground-damage on its
				// own. Without this the player could stand in lava and just
				// keep ticking into negative HP forever (user reported -500
				// HP while in a lava pool).
				if (p.getDeath()) {
					this.playerDeath(targetRealm, p);
				}
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
	private void useAbility(final long realmId, final long playerId, final Vector2f pos,
			final byte abilityIndex, final boolean isCastResolution) {
		final Realm targetRealm = this.realms.get(realmId);

		final Player player = targetRealm.getPlayer(playerId);
		if (player == null) return;
		// Reject input-driven re-casts while the player is mid-cast on a
		// previous ability. Cast-resolution calls bypass this gate.
		if (!isCastResolution && player.isCasting()) return;

		// Phase 2B: read MP cost + cooldown from the new Ability data if the
		// requested slot is bound; otherwise fall back to the legacy ability
		// item path (slot 0 / classAbilityId) so abilities still fire for
		// classes that haven't been ported.
		final int slot = abilityIndex >= 0 && abilityIndex < 4 ? abilityIndex : 0;
		final Ability ab = player.getActiveAbility(slot);
		// [DIAG] log every cast so we can trace the slot → ability → tags path.
		log.info("[USEABILITY] playerId={} classId={} slot={} hotbarId={} ab={} tags={}",
				player.getId(), player.getClassId(), slot,
				player.getHotbarId(slot),
				ab == null ? "(null - falling to LEGACY path)" : ("#"+ab.getId()+" "+ab.getName()),
				ab == null ? "n/a" : ab.getTags());
		final int abMpCost;
		final long abCooldownMs;
		if (ab != null) {
			abMpCost = ab.getMpCost();
			// Phase 2D — effective cooldown = baseCooldown − level × cdReduction,
			// floored at 500ms. cdReductionPerPointMs defaults to 0 so abilities
			// that don't declare a per-point CD lever behave unchanged.
			final int invested = player.getSkillLevel(ab.getId());
			final long reduction = (long) invested * (long) ab.getCdReductionPerPointMs();
			abCooldownMs = Math.max(500L, ab.getBaseCooldownMs() - reduction);
		} else {
			abMpCost     = -1;  // sentinel meaning "use legacy"
			abCooldownMs = -1;
		}

		// Per-slot cooldown via Player.abilityCooldowns. For the legacy path
		// (ab == null) we still use the playerAbilityState map keyed by
		// playerId — preserves existing behavior bit-for-bit.
		//
		// Cast-resolution calls skip the CD gate entirely: the cooldown was
		// set at cast-start to (now + castMs + cooldownMs), so by the time
		// resolution fires it's still in effect and would block us.
		final long now = Instant.now().toEpochMilli();
		if (ab != null && !isCastResolution) {
			final long[] cds = player.getAbilityCooldowns();
			if (cds != null && slot < cds.length) {
				if (cds[slot] > now) {
					log.debug("Ability {} slot {} on cooldown for player {}", ab.getName(), slot, playerId);
					return;
				}
				cds[slot] = now + abCooldownMs;
			}
		}

		// Legacy GameItem still drives projectile spawn for some abilities —
		// Phase 2B only swaps cost/CD off Ability data, Phase 2C will read
		// projectile group + scalings from Ability.effects directly.
		//
		// IMPORTANT: classes that fully migrated to the new Ability pipeline
		// (Heavy_Debuffer/Buffer/DPS/Oddball — all have classAbilityId=0 in
		// character-classes.json) have NO legacy ability item. Previously we
		// bailed here when getAbility() returned null, which silently killed
		// the cast AFTER the client had already paid mana + started cooldown
		// (those are predicted client-side). Symptoms: mana drain + cooldown
		// spinner + zero visual effect because the AoE/effect dispatch block
		// at line ~2935 was never reached.
		//
		// Fix: only bail when BOTH the new Ability data (ab) AND the legacy
		// item are missing. When ab is non-null the new path doesn't need
		// abilityItem to compute the visual; null-guard the few places that
		// still touch it.
		final GameItem legacyAbility = player.getAbility();
		final GameItem abilityItem = legacyAbility != null
				? GameDataManager.GAME_ITEMS.get(legacyAbility.getItemId())
				: null;
		if (ab == null && abilityItem == null) return;
		final Effect effect = abilityItem != null ? abilityItem.getEffect() : null;

		if (ab == null) {
			// Legacy global-cooldown gate (unchanged from pre-Phase-2 behavior).
			final Long lastAbilityUsage = this.playerAbilityState.get(playerId);
			if (lastAbilityUsage == null
					|| (now - lastAbilityUsage >= effect.getCooldownDuration())) {
				this.playerAbilityState.put(playerId, now);
			} else {
				log.debug("Ability {} is on cooldown (legacy)", abilityItem);
				return;
			}
		}

		// Godmode (INVINCIBLE) = infinite mana. MP is charged at cast-start
		// (not at resolution), so cast-resolution calls skip this block —
		// the player has already paid the cost up front.
		if (!isCastResolution && !player.hasEffect(StatusEffectType.INVINCIBLE)) {
			final int mpCost = abMpCost >= 0 ? abMpCost : effect.getMpCost();
			if (player.getMana() < mpCost) return;
			player.setMana(player.getMana() - mpCost);
		}

		// ── Cast-time gate ──────────────────────────────────────────
		// If the ability has baseCastMs > 0 and we are NOT already in the
		// resolution call, schedule the resolution and return early:
		//   1. SLOW the player for the cast duration (prepares the spell)
		//   2. Stash CastState so the per-tick loop can resolve it later
		//   3. Set the slot cooldown to (now + castMs + cooldownMs) so the
		//      same slot can't be re-cast during the cast and the regular
		//      cooldown still applies after the spell fires
		//   4. Broadcast AbilityCastStartPacket so every client in the realm
		//      (caster + party + bystanders) can render a cast bar overlay
		// Per-skill-point cast-speed reduction follows the same lever as
		// per-skill-point cooldown reduction: castMs = max(150, baseCastMs −
		// invested × cdReductionPerPointMs / 2). That keeps the formulas
		// simple and tightly correlated — pumping points into an ability
		// makes it both come up faster AND fire faster.
		if (!isCastResolution && ab != null && ab.getBaseCastMs() > 0L) {
			final int invested = player.getSkillLevel(ab.getId());
			final long castReduction = (long) invested * (long) ab.getCdReductionPerPointMs() / 2L;
			final long castMs = Math.max(150L, ab.getBaseCastMs() - castReduction);
			if (player.getMetrics() != null) {
				player.getMetrics().recordCastStarted(ab.getId());
			}
			player.addEffect(StatusEffectType.SLOWED, castMs);
			player.setCurrentCast(new CastState(
					ab.getId(), slot, now, now + castMs, pos.x, pos.y, false));
			// Push the slot cooldown forward so the cast time is included.
			final long[] cds2 = player.getAbilityCooldowns();
			if (cds2 != null && slot < cds2.length) {
				cds2[slot] = now + castMs + abCooldownMs;
			}
			this.enqueueServerPacketToRealm(targetRealm,
					new AbilityCastStartPacket(
							playerId, ab.getId(), (byte) slot, (int) castMs, pos.x, pos.y));
			return;
		}
		// Cast-completion metric — fires on every ability invocation that
		// reaches the effect dispatch below. Instant casts (no cast gate)
		// only hit this point; cast-time abilities hit it on resolution,
		// after their cast-start record already fired in the gate above.
		if (ab != null && player.getMetrics() != null) {
			player.getMetrics().recordCastCompleted(ab.getId());
		}
		// Phase 2B refactor: when an Ability is bound, it is FULLY authoritative
		// for projectile + self-effect. Without this rule, an Ability that
		// only defines STATUS_APPLY SELF (e.g. Blink) would fall through to
		// the legacy item's projectile group (Fire Spray) and the
		// player.setPos teleport branch would never be reached because the
		// projectile-spawn branch already consumed the cast.
		int effPgId;
		StatusEffectType effSelfStatus;
		int effSelfDurationMs;
		boolean effHasSelfStatus;
		// Phase 3 — abilities may apply multiple SELF statuses (e.g. Knight Brace
		// applies BRACED + SLOWED). The primary (first) status keeps the legacy
		// TELEPORT branch behavior; extras are appended to this list and applied
		// alongside the primary at each apply site.
		final List<Object[]> extraSelfStatuses = new ArrayList<>();
		if (ab != null) {
			effPgId           = -1;
			effSelfStatus     = null;
			effSelfDurationMs = 0;
			effHasSelfStatus  = false;
			for (AbilityEffect e : ab.effectList()) {
				final String t = e.getType();
				if (t == null) continue;
				if (t.equalsIgnoreCase("PROJECTILE_GROUP")) {
					effPgId = e.getProjectileGroupId();
				} else if (t.equalsIgnoreCase("STATUS_APPLY") && "SELF".equalsIgnoreCase(e.getTarget())) {
					try {
						final short sid = Short.parseShort(String.valueOf(e.getStatusId()).trim());
						final StatusEffectType st = StatusEffectType.map.get(sid);
						if (st != null) {
							if (!effHasSelfStatus) {
								effSelfStatus    = st;
								effSelfDurationMs = (int) e.getBaseDurationMs();
								effHasSelfStatus = true;
							} else {
								extraSelfStatuses.add(new Object[] { st, (int) e.getBaseDurationMs() });
							}
						}
					} catch (NumberFormatException ignore) { /* non-numeric statusId — Phase 2D resolves via enum name */ }
				}
			}
		} else {
			// Legacy path: no Ability bound, fall back to the GameItem template.
			effPgId           = (abilityItem.getDamage() != null) ? abilityItem.getDamage().getProjectileGroupId() : -1;
			effSelfStatus     = (effect != null) ? effect.getEffectId() : null;
			effSelfDurationMs = (effect != null) ? (int) effect.getDuration() : 0;
			effHasSelfStatus  = effect != null && effect.isSelf();
		}
		final ProjectileGroup group = (effPgId >= 0)
				? GameDataManager.PROJECTILE_GROUPS.get(effPgId)
				: null;

		// Phase 1B: ability is class-bound, not equipped. CombatModifiers
		// for the ability are now derived from the GameItem template (no
		// per-instance enchantments since the ability isn't a held item).
		// Null-guard for classes that have no legacy ability item (Heavy_*).
		final CombatModifiers abilityCm = abilityItem != null
				? CombatModifiers.fromItem(abilityItem)
				: new CombatModifiers();

		// Phase 2B: ability tag "from_sky" emits a two-part visual at the
		// cursor — a vertical chain-lightning streak descending from 320 px
		// above the target, plus a wizard-burst flare at impact. The
		// projectile itself still spawns at the cursor (via the normal
		// positionMode-2 ABSOLUTE path) so the bullet dies on contact and
		// the visuals tell the player "this came from the sky".
		final boolean fromSky = ab != null && ab.getTags() != null && ab.getTags().contains("from_sky");
		final boolean holyVisual = ab != null && ab.getTags() != null && ab.getTags().contains("holy");
		final float SKY_FALL_HEIGHT = 320f;
		if (fromSky && !holyVisual) {
			// Meteor — thicker fiery bolt, smoke-poof + double wizard-burst.
			for (int offX = -6; offX <= 6; offX += 3) {
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						pos.x + offX, pos.y - SKY_FALL_HEIGHT,
						pos.x + offX, pos.y, (short) 800));
			}
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_SMOKE_POOF, pos.x, pos.y, 112f, (short) 1100));
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST, pos.x, pos.y, 80f, (short) 700, (byte) 6));
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST, pos.x, pos.y, 48f, (short) 500, (byte) 6));
		} else if (fromSky && holyVisual) {
			// Holy Beam / Divine Verdict — single big paladin-seal beam
			// descending at the cursor. Avoid KNIGHT_SHOCKWAVE as the impact
			// (case 11 is directional and renders as a forward thrust arrow
			// even when emitted via aoeEffect, producing a stray gold arrow).
			// WIZARD_BURST is radial so it reads as a ground-impact flash.
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_PALADIN_SEAL,
					pos.x, pos.y, 130f, (short) 1400, (byte) 3));
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST,
					pos.x, pos.y, 90f, (short) 700, (byte) 3));
		}

		// Phase 3: "visual_at_self:N" tag emits a CreateEffectPacket of type N
		// at the caster's position. Lets self-buff abilities (Knight Taunt,
		// Phalanx, Last Stand) supply a distinct visual without bespoke code.
		if (ab != null && ab.getTags() != null) {
			for (String tag : ab.getTags()) {
				if (tag == null || !tag.toLowerCase().startsWith("visual_at_self:")) continue;
				try {
					final short eff = Short.parseShort(tag.substring("visual_at_self:".length()).trim());
					final Vector2f pcenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
					this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
							eff, pcenter.x, pcenter.y, 48f, (short) 600));
				} catch (NumberFormatException ignore) { /* skip malformed */ }
				break;
			}

			// "knight_slam" — short forward streak originating IN FRONT of the
			// player, so the projectiles and the visual both read as
			// "pushed forward from the front of the caster" (NOT from behind).
			if (ab.getTags().contains("knight_slam")) {
				final Vector2f from = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dxK = pos.x - from.x, dyK = pos.y - from.y;
				final float lenK = (float) Math.sqrt(dxK * dxK + dyK * dyK);
				if (lenK > 0.001f) {
					// Streak hugs the shield-projectile travel — spawns just in
					// front of the caster, total reach capped to ~110px (the
					// shield projectile range is 70px + 30px spawn-forward; a
					// touch more so the streak visually escorts the projectiles
					// to their stun point, not far past it).
					final float FRONT_OFFSET = 30f;
					final float MAX_REACH    = 110f;
					final float endDist      = Math.min(lenK, MAX_REACH);
					final float fx0 = from.x + dxK / lenK * FRONT_OFFSET;
					final float fy0 = from.y + dyK / lenK * FRONT_OFFSET;
					final float fx1 = from.x + dxK / lenK * endDist;
					final float fy1 = from.y + dyK / lenK * endDist;
					this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
							CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
							fx0, fy0, fx1, fy1, (short) 280));
				}
			}

			// "soul_harvest" — Necromancer #4 ult. Spawns a 10s vortex field at
			// the targeted spot that drains 50 HP/s from enemies inside and
			// heals allies in radius for the total HP sapped each tick. The
			// initial aoe_targeted armor-piercing burst still resolves above
			// — this tag only stands up the persistent field + visual.
			if (ab.getTags().contains("soul_harvest")) {
				final long ttlMs = 10_000L;
				final float vortexR = 128f;
				// Replace any existing field this caster had (recast).
				soulHarvestFields.removeIf(f -> f.casterId == player.getId());
				soulHarvestFields.add(new SoulHarvestField(
						targetRealm.getRealmId(), player.getId(),
						pos.x, pos.y, vortexR, now + ttlMs));
				// Immediate visual — the tick loop will refresh it.
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_SOUL_VORTEX, pos.x, pos.y, vortexR, (short) 1200, (byte) 6));
			}

			// "taunt_visual" — small red circle blink at player + "TAUNTING" text.
			if (ab.getTags().contains("taunt_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_TAUNT_ROAR, pc.x, pc.y, 44f, (short) 700, (byte) 5));
				this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "TAUNTING");
			}

			// "brace_visual" — dedicated renderer case (BRACE_STANCE): a small
			// translucent shield-arc in front of the player + 4 ground tick marks.
			// Reads as "raise shield / brace for impact", not a circle.
			if (ab.getTags().contains("brace_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BRACE_STANCE, pc.x, pc.y, 56f, (short) 700, (byte) 1));
				this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "BRACED");
			}

			// "dash_trail" tag — chain-lightning streak from the caster toward
			// the cursor, ~5 tiles long. Pure visual; the SPEEDY status (applied
			// elsewhere) does the actual mobility.
			if (ab.getTags().contains("dash_trail")) {
				final Vector2f from = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dx = pos.x - from.x, dy = pos.y - from.y;
				final float len = (float) Math.sqrt(dx * dx + dy * dy);
				if (len > 0.001f) { dx = dx / len * 160f; dy = dy / len * 160f; }
				else              { dx = 160f; dy = 0f; }
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						from.x, from.y, from.x + dx, from.y + dy, (short) 500));
			}

			// "shadow_dash" tag — actually teleport the player along a chain
			// lightning streak toward the cursor. The streak draws from the
			// pre-dash origin to wherever they end up; if a tile blocks the
			// path partway, the dash stops at the last clear position. The
			// SPEEDY status applied via STATUS_APPLY SELF gives the follow-up
			// burst once they land.
			if (ab.getTags().contains("shadow_dash")) {
				// GROUNDED vetoes movement abilities (dashes, blinks, teleports).
				if (player.hasEffect(StatusEffectType.GROUNDED)) {
					this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
					return;
				}
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dx = pos.x - origin.x, dy = pos.y - origin.y;
				final float len = (float) Math.sqrt(dx * dx + dy * dy);
				final float MAX_DASH = 192f; // ~6 tiles
				if (len > 0.001f) { dx = dx / len; dy = dy / len; }
				else              { dx = 1f; dy = 0f; }
				final float dashDist = Math.min(MAX_DASH, len < 0.001f ? MAX_DASH : len);
				// Walk in 16px increments until we hit a wall — last clear
				// step is where the ninja lands. Uses the existing tile
				// collider so cliffs / walls block correctly.
				float walked = 0f;
				Vector2f landing = player.getPos().clone();
				final float STEP = 16f;
				while (walked + STEP <= dashDist) {
					final Vector2f test = new Vector2f(
							landing.x + dx * STEP,
							landing.y + dy * STEP);
					if (targetRealm.getTileManager().collidesAtPosition(test, player.getSize())
							|| targetRealm.getTileManager().isVoidTile(test, 0, 0)) {
						break;
					}
					landing = test;
					walked += STEP;
				}
				player.setPos(landing);
				final Vector2f endCenter = landing.clone(player.getSize() / 2, player.getSize() / 2);
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						origin.x, origin.y, endCenter.x, endCenter.y, (short) 500));
				// Faint smoke at the origin so the "vanish here / appear there"
				// read is sharper than just the streak.
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_SMOKE_POOF, origin.x, origin.y, 40f, (short) 400, (byte) 0));
			}

			// "shuriken_volley" tag — Ninja #1 Star Throw. Fires 3 shurikens
			// in a STRAIGHT LINE one behind the other, all heading at the
			// same angle toward the cursor. Sprite tier maps directly off
			// skill points (SP 0 = Iron 1000 / col 10, SP 5 = Demonbane
			// 1005 / col 15). Slow magnitude (6) so the "trio in line"
			// stays readable as it crosses the screen.
			if (ab.getTags().contains("shuriken_volley")) {
				final int sp = player.getSkillLevel(ab.getId());
				final int tier = Math.min(5, Math.max(0, sp));
				final int groupId = 1000 + tier;
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				final float baseA = Bullet.getAngle(origin, pos);
				// Bullet.update() moves the projectile by (sin(stored), cos(stored)).
				// The Bullet ctor stores `-angle`, so the actual flight unit
				// vector is (-sin(baseA), cos(baseA)). To stagger spawns BEHIND
				// the player along that flight line, the backward unit vector
				// is (sin(baseA), -cos(baseA)).
				final float bx = (float) Math.sin(baseA);
				final float by = (float) -Math.cos(baseA);
				final int   STARS = 3;
				final float STEP = 36f; // pixels between adjacent shurikens
				int dmg = ab.getBaseDamage();
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
					final int sv = resolveScalingInput(player, ab, sc);
					dmg += (int) sc.curveEnum().apply(sv, sc.getCoeff(), sc.getCap());
				}
				final short damage = (short) Math.min(Short.MAX_VALUE, Math.max(0, dmg));
				final List<Short> flags = new ArrayList<>();
				for (int i = 0; i < STARS; i++) {
					// Offset each shuriken backwards along the true flight
					// direction so they spawn nose-to-tail in a single file
					// regardless of cursor direction (diagonals included).
					final Vector2f src = new Vector2f(
							origin.x + bx * STEP * i,
							origin.y + by * STEP * i);
					this.addProjectile(realmId, 0L, player.getId(), groupId,
							0, src, baseA, (short) 22, 6f, 2048f,
							damage, false, flags, (short) 0, (short) 0, player.getId());
				}
			}

			// "blade_orbit" tag — Ninja #3 Blade Storm. Visual-only orbiting
			// shurikens around the player for the DAMAGING buff duration. The
			// tick loop in update() re-emits the effect every 6 ticks centered
			// on the caster's live position so the orbit follows movement.
			if (ab.getTags().contains("blade_orbit")) {
				final int sp = player.getSkillLevel(ab.getId());
				// Tier byte = SP, 0..5. The client looks up col = 10 + tier
				// in openrealm-items.png to draw the matching shuriken sprite
				// (Iron → Demonbane). Max SP is 5 so this is safe.
				final byte tier = (byte) Math.min(5, Math.max(0, sp));
				bladeOrbitStates.removeIf(s -> s.casterId == player.getId());
				bladeOrbitStates.add(new BladeOrbitState(
						targetRealm.getRealmId(), player.getId(), now + 5000L, tier));
				// Immediate visual — the tick loop will refresh.
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLADE_ORBIT, pc.x, pc.y, 56f, (short) 600, tier));
			}

			// "blade_blender" tag — Ninja #4 Death Blossom. Spawns a 5s spiral
			// at the cursor that shreds enemies inside every 1s. Total damage
			// budget = ab base + DEX/SP scaling, split across 5 ticks. Tier
			// (Crimson/Obsidian/Demonbane = items 301/302/303) escalates with
			// SP — max SP is 3 so the tier band is 2..4 of the shuriken set.
			if (ab.getTags().contains("blade_blender")) {
				final int sp = player.getSkillLevel(ab.getId());
				// Death Blossom max SP is 3. Start at Crimson (col 13 = tier 3
				// in the col-10-based palette) and climb to Demonbane at SP 2-3.
				// SP 0 -> tier 3 (Crimson), SP 1 -> tier 4 (Obsidian),
				// SP 2-3 -> tier 5 (Demonbane). Tier byte feeds renderer's
				// col-offset lookup against openrealm-items.png row 16.
				final byte tier = (byte) Math.min(5, 3 + Math.max(0, sp));
				int total = ab.getBaseDamage();
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
					final int sv = resolveScalingInput(player, ab, sc);
					total += (int) sc.curveEnum().apply(sv, sc.getCoeff(), sc.getCap());
				}
				final int perTick = Math.max(1, total / 5);
				final long ttlMs = 5000L;
				final float radius = 144f;
				bladeBlenderFields.removeIf(f -> f.casterId == player.getId());
				bladeBlenderFields.add(new BladeBlenderField(
						targetRealm.getRealmId(), player.getId(),
						pos.x, pos.y, radius, now + ttlMs, perTick, tier));
				// Immediate visual — the tick loop will refresh it.
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLADE_BLENDER, pos.x, pos.y, radius, (short) 600, tier));
			}
		}

		// Phase 2B: ability tag "aoe_targeted" handles ground-targeted AoE
		// abilities (Frost Nova et al) — no projectile, just an AoE effect
		// at the cursor that applies STATUS_APPLY ENEMIES_HIT to anything
		// in radius and broadcasts a visual via CreateEffectPacket.
		final boolean aoeTargeted = ab != null && ab.getTags() != null && ab.getTags().contains("aoe_targeted");
		final boolean aoeAlly    = ab != null && ab.getTags() != null && ab.getTags().contains("aoe_ally");
		if (aoeTargeted || aoeAlly) {
			// Radius: ability.baseRadius if set (lets short-range melee
			// AoEs like Ground Pound be tighter than the 96px floor),
			// otherwise the 96px default, plus the sum of RADIUS scaling
			// contributions (curve-aware).
			float aoeRadius = (ab.getBaseRadius() > 0) ? ab.getBaseRadius() : 96f;
			if (ab != null) {
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"RADIUS".equalsIgnoreCase(sc.getTarget())) continue;
					final int statVal = resolveScalingInput(player, ab, sc);
					aoeRadius += sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
				}
			}
			// Ally-targeted AoEs (priest heal/cleanse/sanctuary). Center on caster
			// for self-heal pulses; allies are detected in radius around 'pos'
			// which for ally abilities we anchor at the caster.
			final Vector2f effCenter;
			if (aoeAlly) {
				effCenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			} else {
				effCenter = pos;
			}
			// Visual effect type — derived from tags so designers can pick.
			// from_sky owns its own staged visual chain above; skip the generic
			// AoE ring there so we don't double-stack a stasis field on top of
			// the meteor explosion.
			if (!fromSky) {
				short visualEffect = CreateEffectPacket.EFFECT_STASIS_FIELD;
				byte  vTier        = 1; // T1 light-blue default for stasis
				if (ab.getTags().contains("fire"))     { visualEffect = CreateEffectPacket.EFFECT_WIZARD_BURST;   vTier = 4; } // orange
				if (ab.getTags().contains("curse"))    { visualEffect = CreateEffectPacket.EFFECT_CURSE_RADIUS;   vTier = 6; } // purple
				if (ab.getTags().contains("heal"))     { visualEffect = CreateEffectPacket.EFFECT_HEAL_RADIUS;    vTier = 2; } // green
				if (ab.getTags().contains("cleanse"))  { visualEffect = CreateEffectPacket.EFFECT_WATER_FOUNTAIN; vTier = 1; } // sapphire
				if (ab.getTags().contains("bless"))    { visualEffect = CreateEffectPacket.EFFECT_PALADIN_SEAL;   vTier = 3; } // gold
				if (ab.getTags().contains("holy"))     { visualEffect = CreateEffectPacket.EFFECT_PALADIN_SEAL;   vTier = 3; } // gold
				if (ab.getTags().contains("frost"))    { visualEffect = CreateEffectPacket.EFFECT_FROST_NOVA;     vTier = 1; } // ice
				if (ab.getTags().contains("mark"))     { visualEffect = CreateEffectPacket.EFFECT_HUNTERS_RETICLE;vTier = 5; } // red reticle
				if (ab.getTags().contains("poison"))   { visualEffect = CreateEffectPacket.EFFECT_POISON_CLOUD;   vTier = 2; } // toxic green
				if (ab.getTags().contains("drain"))    { visualEffect = CreateEffectPacket.EFFECT_LIFE_DRAIN;     vTier = 5; } // blood red
				if (ab.getTags().contains("bone"))     { visualEffect = CreateEffectPacket.EFFECT_BONE_SPIKES;    vTier = 0; } // bone white
				if (ab.getTags().contains("lightning")){ visualEffect = CreateEffectPacket.EFFECT_LIGHTNING_STRIKE;vTier = 3; } // electric yellow
				if (ab.getTags().contains("arcane"))   { visualEffect = CreateEffectPacket.EFFECT_MANA_BOLT;      vTier = 6; } // arcane purple
				if (ab.getTags().contains("time"))     { visualEffect = CreateEffectPacket.EFFECT_TIME_STOP;      vTier = 1; } // silver-blue
				if (ab.getTags().contains("smite"))    { visualEffect = CreateEffectPacket.EFFECT_SMITE_FLASH;    vTier = 3; } // gold flash
				if (ab.getTags().contains("death_bloom")){visualEffect = CreateEffectPacket.EFFECT_DEATH_BLOSSOM; vTier = 6; } // dark blade
				if (ab.getTags().contains("bloom"))    { visualEffect = CreateEffectPacket.EFFECT_INSPIRE_BLOOM;  vTier = 3; } // gold petals
				if (ab.getTags().contains("slash"))    { visualEffect = CreateEffectPacket.EFFECT_RECKLESS_SLASH; vTier = 5; } // red arc
				if (ab.getTags().contains("shuriken")) { visualEffect = CreateEffectPacket.EFFECT_STAR_SHURIKEN;  vTier = 0; } // steel
				if (ab.getTags().contains("snare_trap")){visualEffect = CreateEffectPacket.EFFECT_SNARE_GEAR;     vTier = 4; } // iron
				if (ab.getTags().contains("explosion")){ visualEffect = CreateEffectPacket.EFFECT_COMBUSTION_TRAP;vTier = 4; } // orange blast
				if (ab.getTags().contains("warcry"))   { visualEffect = CreateEffectPacket.EFFECT_WAR_CRY_WAVE;   vTier = 5; } // red roar
				if (ab.getTags().contains("caltrops")) { visualEffect = CreateEffectPacket.EFFECT_CALTROPS;       vTier = 0; } // steel spikes
				if (ab.getTags().contains("smoke"))    { visualEffect = CreateEffectPacket.EFFECT_SMOKE_POOF;     vTier = 0; } // grey smoke
				// Bespoke phase-3 visuals — these supersede the generic ring
				// renderer with hand-tuned procedural effects in renderer.js +
				// PlayState.java. Tier byte is mostly cosmetic for them.
				if (ab.getTags().contains("reality_tear"))   { visualEffect = CreateEffectPacket.EFFECT_REALITY_TEAR;    vTier = 6; } // void rift
				if (ab.getTags().contains("phantom_strike")) { visualEffect = CreateEffectPacket.EFFECT_PHANTOM_STRIKE;  vTier = 6; } // shadow afterimage
				if (ab.getTags().contains("stasis_lock"))    { visualEffect = CreateEffectPacket.EFFECT_STASIS_LOCK;     vTier = 2; } // frozen clock
				if (ab.getTags().contains("sanctuary"))      { visualEffect = CreateEffectPacket.EFFECT_SANCTUARY_DOME;  vTier = 3; } // golden dome
				if (ab.getTags().contains("vampiric"))       { visualEffect = CreateEffectPacket.EFFECT_VAMPIRIC_LATCH;  vTier = 5; } // red drain
				// Heavy class visuals (2026-05-14)
				if (ab.getTags().contains("rapier_stab"))    { visualEffect = CreateEffectPacket.EFFECT_RAPIER_STAB;     vTier = 0; } // silver
				if (ab.getTags().contains("low_swing"))      { visualEffect = CreateEffectPacket.EFFECT_LOW_SWING;       vTier = 5; } // red steel
				if (ab.getTags().contains("disarm_flourish")){ visualEffect = CreateEffectPacket.EFFECT_DISARM_FLOURISH; vTier = 3; } // gold + white
				if (ab.getTags().contains("divine_beam"))    { visualEffect = CreateEffectPacket.EFFECT_DIVINE_BEAM;     vTier = 3; } // gold pillar
				if (ab.getTags().contains("fortify_aura"))   { visualEffect = CreateEffectPacket.EFFECT_FORTIFY_AURA;    vTier = 2; } // green regen
				if (ab.getTags().contains("ground_pound"))   { visualEffect = CreateEffectPacket.EFFECT_GROUND_POUND;    vTier = 4; } // brown dust
				// A few effects benefit from a longer-than-default on-screen
				// life (Sanctuary's INVINCIBLE buff lasts 5s; Stasis Lock's
				// GROUNDED window is 4s). Bumping the per-packet duration
				// keeps the visual matched to the gameplay window.
				short visualDurationMs = 1500;
				if (visualEffect == CreateEffectPacket.EFFECT_SANCTUARY_DOME) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_STASIS_LOCK) visualDurationMs = 4000;
				else if (visualEffect == CreateEffectPacket.EFFECT_REALITY_TEAR) visualDurationMs = 2500;
				else if (visualEffect == CreateEffectPacket.EFFECT_PHANTOM_STRIKE) visualDurationMs = 1800;
				else if (visualEffect == CreateEffectPacket.EFFECT_VAMPIRIC_LATCH) visualDurationMs = 2000;
				// Heavy class visuals — match life to the gameplay flavor
				else if (visualEffect == CreateEffectPacket.EFFECT_RAPIER_STAB) visualDurationMs = 350;
				else if (visualEffect == CreateEffectPacket.EFFECT_LOW_SWING)   visualDurationMs = 500;
				else if (visualEffect == CreateEffectPacket.EFFECT_DISARM_FLOURISH) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_DIVINE_BEAM) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_FORTIFY_AURA) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_GROUND_POUND) visualDurationMs = 700;
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						visualEffect, effCenter.x, effCenter.y, aoeRadius, visualDurationMs, vTier));
				// "outline_ring" tag — extra outline at radius. For Hunter's Mark
				// the reticle IS the outline so skip the redundant stasis ring.
				if (ab.getTags().contains("outline_ring") && !ab.getTags().contains("mark")) {
					this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
							CreateEffectPacket.EFFECT_STASIS_FIELD,
							effCenter.x, effCenter.y, aoeRadius, (short) 1500, (byte) 6));
				}
			}
			// "rain_arrows" tag — spawn visual-only arrow projectiles that fall
			// from above the AoE into the circle. Damage=0 so they don't double-
			// dip on the AoE's own damage, but they render as the archer arrow
			// sprite (projectileId 88, the tier 8-10 bow round) for that cool
			// "barrage from the sky" feel.
			if (ab.getTags().contains("rain_arrows")) {
				final int ARROW_COUNT = 14;
				final int ARROW_PID = 88;
				final Random rng = ThreadLocalRandom.current();
				for (int i = 0; i < ARROW_COUNT; i++) {
					final double r  = aoeRadius * Math.sqrt(rng.nextDouble());
					final double th = rng.nextDouble() * 2.0 * Math.PI;
					final float offX = (float)(r * Math.cos(th));
					final float offY = (float)(r * Math.sin(th));
					// Spawn 280px above the landing point; fall straight down.
					// addProjectile passes angle to Bullet ctor which stores
					// -angle, so passing 0 yields (sin,cos)=(0,1) → +Y motion.
					final Vector2f src = new Vector2f(
							effCenter.x + offX, effCenter.y + offY - 280f);
					this.addProjectile(targetRealm.getRealmId(), 0L, player.getId(),
							ARROW_PID, -1, src, 0f, (short) 16, 9f, 320f,
							(short) 0, false, new ArrayList<>(),
							(short) 0, (short) 0, player.getId());
				}
			}
			// Optional direct damage (no projectile). Used by Meteor / Rain of
			// Arrows / etc. Reads baseDamage + DAMAGE scalings from Ability.
			int abDamage = 0;
			if (ab.getBaseDamage() > 0) {
				abDamage = ab.getBaseDamage();
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
					final int statVal = resolveScalingInput(player, ab, sc);
					abDamage += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
				}
			}
			final short finalDmg = (short) Math.min(Short.MAX_VALUE, Math.max(0, abDamage));
			final boolean armorPierce = ab.getTags().contains("armor_pierce");
			// Per-enemy ARMOR_BROKEN is resolved inside the loop below — this
			// only covers the ability-side "armor_pierce" tag (applies to all
			// targets uniformly).
			final TextEffect dmgTextEffectBase = armorPierce ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
			// Enemy branch — only when this isn't an ally-targeted AoE.
			if (aoeTargeted && !aoeAlly) {
				final List<Enemy> dead = new ArrayList<>();
				for (final Enemy enemy : targetRealm.getEnemies().values()) {
					if (enemy.getDeath()) continue;
					final float dx = enemy.getPos().x - pos.x;
					final float dy = enemy.getPos().y - pos.y;
					if (dx * dx + dy * dy > aoeRadius * aoeRadius) continue;
					for (AbilityEffect aoeEff : ab.effectList()) {
						if (!"STATUS_APPLY".equalsIgnoreCase(aoeEff.getType())) continue;
						if (!"ENEMIES_HIT".equalsIgnoreCase(aoeEff.getTarget())) continue;
						try {
							final StatusEffectType st = StatusEffectType.map.get(
									Short.parseShort(String.valueOf(aoeEff.getStatusId()).trim()));
							if (st != null) {
								applyStatusWithFeedback(targetRealm, enemy, EntityType.ENEMY,
										st, aoeEff.getBaseDurationMs());
							}
						} catch (NumberFormatException ignore) { }
					}
					if (finalDmg > 0) {
						enemy.setHealth(enemy.getHealth() - finalDmg);
						final TextEffect dmgFx = enemy.hasEffect(StatusEffectType.ARMOR_BROKEN)
								? TextEffect.ARMOR_BREAK : dmgTextEffectBase;
						this.broadcastTextEffect(EntityType.ENEMY, enemy, dmgFx, "-" + finalDmg);
						if (enemy.getHealth() <= 0) dead.add(enemy);
					}
				}
				for (final Enemy e : dead) {
					this.enemyDeath(targetRealm, e);
				}
			}
			// Ally branch — HEAL / CLEANSE / STATUS_APPLY ALLIES_HIT for priest kit.
			if (aoeAlly) {
				int healAmount = 0;
				boolean hasCleanse = false;
				int cleanseCap = Integer.MAX_VALUE;
				for (AbilityEffect aoeEff : ab.effectList()) {
					if ("HEAL".equalsIgnoreCase(aoeEff.getType())
							&& "ALLIES_HIT".equalsIgnoreCase(aoeEff.getTarget())) {
						healAmount += aoeEff.getBaseMagnitude();
					} else if ("CLEANSE".equalsIgnoreCase(aoeEff.getType())
							&& "ALLIES_HIT".equalsIgnoreCase(aoeEff.getTarget())) {
						hasCleanse = true;
						if (aoeEff.getBaseMagnitude() > 0) cleanseCap = aoeEff.getBaseMagnitude();
					}
				}
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"HEAL".equalsIgnoreCase(sc.getTarget())) continue;
					final int statVal = resolveScalingInput(player, ab, sc);
					healAmount += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
				}
				int cleansedSoFar = 0;
				for (final Player ally : targetRealm.getPlayers().values()) {
					if (ally == null) continue;
					final Vector2f ac = ally.getPos().clone(ally.getSize() / 2, ally.getSize() / 2);
					final float dx = ac.x - effCenter.x;
					final float dy = ac.y - effCenter.y;
					if (dx * dx + dy * dy > aoeRadius * aoeRadius) continue;
					if (healAmount > 0) {
						final int cap = ally.getComputedStats().getHp();
						final int newHp = Math.min(cap, ally.getHealth() + healAmount);
						final int actual = newHp - ally.getHealth();
						ally.setHealth(newHp);
						if (actual > 0) {
							this.broadcastTextEffect(EntityType.PLAYER, ally, TextEffect.HEAL, "+" + actual);
						}
					}
					if (hasCleanse && cleansedSoFar < cleanseCap) {
						ally.resetEffects();
						cleansedSoFar++;
					}
					for (AbilityEffect aoeEff : ab.effectList()) {
						if (!"STATUS_APPLY".equalsIgnoreCase(aoeEff.getType())) continue;
						if (!"ALLIES_HIT".equalsIgnoreCase(aoeEff.getTarget())) continue;
						try {
							final StatusEffectType st = StatusEffectType.map.get(
									Short.parseShort(String.valueOf(aoeEff.getStatusId()).trim()));
							if (st != null) {
								applyStatusWithFeedback(targetRealm, ally, EntityType.PLAYER,
										st, aoeEff.getBaseDurationMs());
							}
						} catch (NumberFormatException ignore) { }
					}
				}
			}
			// Self-effect from STATUS_APPLY SELF (already derived above).
			if (effHasSelfStatus && effSelfStatus != null) {
				applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
			// SPAWN_POTIONS — priest "Holy Bounty" pattern: drop N HP + N MP
			// potions on the ground around the cast point as separate loot
			// bags so the whole party can pick them up. baseMagnitude is the
			// count of EACH potion type; SKILL_POINTS scaling on COUNT adds
			// to that flat. Cap at 6 of each to prevent flooding the realm
			// with loot bags at max investment.
			if (ab != null) {
				for (AbilityEffect aoeEff : ab.effectList()) {
					if (!"SPAWN_POTIONS".equalsIgnoreCase(aoeEff.getType())) continue;
					int hpCount = Math.max(0, aoeEff.getBaseMagnitude());
					int mpCount = hpCount;
					for (AbilityScaling sc : ab.scalingList()) {
						if (!"COUNT".equalsIgnoreCase(sc.getTarget())) continue;
						final int statVal = resolveScalingInput(player, ab, sc);
						final int bonus = (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
						hpCount += bonus; mpCount += bonus;
					}
					hpCount = Math.min(6, Math.max(0, hpCount));
					mpCount = Math.min(6, Math.max(0, mpCount));
					final GameItem hp = GameDataManager.GAME_ITEMS.get(Player.HP_POTION_ITEM_ID);
					final GameItem mp = GameDataManager.GAME_ITEMS.get(Player.MP_POTION_ITEM_ID);
					if (hp == null || mp == null) break;
					final int total = hpCount + mpCount;
					for (int i = 0; i < total; i++) {
						final boolean isHp = i < hpCount;
						final GameItem potion = isHp ? hp.clone() : mp.clone();
						// Spread potions in a tight ring around the cast pos.
						final double a = (2 * Math.PI) * i / Math.max(1, total);
						final float r = 28f;
						final Vector2f drop = new Vector2f(
								pos.x + (float) Math.cos(a) * r,
								pos.y + (float) Math.sin(a) * r);
						targetRealm.addLootContainer(new LootContainer(LootTier.BROWN, drop, potion));
					}
				}
			}
			return; // Skip the projectile spawn paths — this was a pure AoE.
		}
		if (((abilityItem.getDamage() != null) && (abilityItem.getEffect() != null) && (group != null))) {

			final Vector2f dest = new Vector2f(pos.x, pos.y);

			Vector2f source = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			final Vector2f playerCenter = new Vector2f(source.x, source.y);
			final float angle = Bullet.getAngle(source, dest);

			for (final Projectile p : group.getProjectiles()) {
				final short offset = (short) (p.getSize() / (short) 2);
				short rolledDamage;
				if (ab != null && ab.getBaseDamage() > 0) {
					// Ability-data damage path: baseDamage + sum of scalings
					// targeting DAMAGE. Player STR is NOT auto-added; the
					// Ability controls the full damage budget so designers
					// can build large nukes (e.g. Meteor's 2000 base) without
					// fighting the legacy weapon's small range.
					int dmg = ab.getBaseDamage();
					for (AbilityScaling sc : ab.scalingList()) {
						if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
						final int statVal = resolveScalingInput(player, ab, sc);
						dmg += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
					}
					rolledDamage = (short) Math.min(Short.MAX_VALUE, Math.max(0, dmg));
				} else {
					rolledDamage = abilityItem.getDamage().getInRange();
					rolledDamage += player.getComputedStats().getStr();
				}
				rolledDamage = applyCombatDamageMods(rolledDamage, abilityCm);
				if (p.getPositionMode() != ProjectilePositionMode.TARGET_PLAYER) {
					source = dest;
				} else {
					// TARGET_PLAYER mode — bullets spawn at the caster. Push the
					// spawn point ~36px FORWARD along the aim line so the bullet
					// reads as "force-pushed from the front of the player"
					// instead of materialising on top of the player sprite. The
					// playerCenter capture happens once outside the loop so
					// fan-spread iterations don't accumulate offsets.
					float dxN = dest.x - playerCenter.x;
					float dyN = dest.y - playerCenter.y;
					final float lenN = (float) Math.sqrt(dxN * dxN + dyN * dyN);
					if (lenN > 0.001f) {
						final float SPAWN_FWD = 60f;
						source = new Vector2f(
								playerCenter.x + dxN / lenN * SPAWN_FWD,
								playerCenter.y + dyN / lenN * SPAWN_FWD);
					}
				}
				// Symmetric fan around the aim line — see ServerGameLogic
				// shoot logic for the same fix. Aim hits the center of the
				// spread regardless of how many extra projectiles the player
				// has gemmed in. from_sky abilities (Meteor) spawn the bullet
				// AT the cursor and rely on the CreateEffectPacket visuals
				// (chain-lightning streak + impact burst, emitted above) to
				// sell the "fell from above" effect — keeps the bullet path
				// identical to a normal targeted ability.
				{
					final int totalBullets = 1 + abilityCm.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = angle + Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = applyCombatDamageMods(rolledDamage, abilityCm);
						spawnAbilityBullet(realmId, player, effPgId, p,
								source.clone(-offset, -offset), baseA + deltaA, rolled, abilityCm);
					}
				}
			}
			// Apply self-effect if present (e.g., warrior helmet SPEEDY buff)
			if (effHasSelfStatus && effSelfStatus != null) {
				applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}

		} else if ((abilityItem.getDamage() != null) && (group != null)) {
			final Vector2f dest = new Vector2f(pos.x, pos.y);
			for (final Projectile p : group.getProjectiles()) {

				final short offset = (short) (p.getSize() / (short) 2);
				short rolledDamage = abilityItem.getDamage().getInRange();
				rolledDamage += player.getComputedStats().getStr();
				rolledDamage = applyCombatDamageMods(rolledDamage, abilityCm);
				{
					final int totalBullets = 1 + abilityCm.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = applyCombatDamageMods(rolledDamage, abilityCm);
						spawnAbilityBullet(realmId, player, effPgId, p,
								dest.clone(-offset, -offset), baseA + deltaA, rolled, abilityCm);
					}
				}
			}

			// If the ability is non damaging or script-only (rogue cloak, priest tome, sorcerer scepter,
			// wizard blink) — drive off the derived effect so each hotbar slot can have its own.
		} else if (effSelfStatus != null) {
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& player.hasEffect(StatusEffectType.GROUNDED)) {
				// GROUNDED also blocks wizard-blink / sorcerer-flicker style
				// teleports — same veto as shadow_dash.
				this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
				return;
			}
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& !targetRealm.getTileManager().collidesAtPosition(pos, player.getSize())
					&& !targetRealm.getTileManager().isVoidTile(pos, 0, 0)) {
				// Emit a violet runic glyph at BOTH origin and destination so
				// the teleport reads as "vanish here / appear there".
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLINK_GLYPH, origin.x, origin.y, 56f, (short) 700, (byte) 6));
				this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLINK_GLYPH, pos.x, pos.y, 56f, (short) 900, (byte) 6));
				player.setPos(pos);
			} else if (!effSelfStatus.equals(StatusEffectType.TELEPORT) && effHasSelfStatus) {
				applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
		}
		// Invoke any item specific scripts — ONLY when no Ability data is
		// bound for the slot (legacy fallback). When a new Ability is in
		// play, the tag-based visuals (taunt_visual, brace_visual,
		// knight_slam, etc.) own the cast effect; running the legacy item
		// script on top emitted a second visual for every ability — for
		// Knight that was the shield-bash forward-thrust arrow firing
		// alongside Taunt/Brace/Phalanx and making all four casts look the
		// same.
		if (ab == null) {
			final UseableItemScriptBase script = this.getItemScript(abilityItem.getItemId());
			if (script != null) {
				log.info("[USEABILITY] legacy-script firing for itemId={} (ab was null)",
						abilityItem.getItemId());
				script.invokeItemAbility(targetRealm, player, abilityItem, pos);
			}
		} else if (abilityItem != null) {
			log.info("[USEABILITY] skipping legacy-script for itemId={} — ab={} owns the visuals",
					abilityItem.getItemId(), ab.getName());
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

	
	// Per-tick caps on NEW entities added to a player's ledger. Caps apply
	// ONLY to the load side — cap-trimmed IDs simply stay out of the ledger
	// and the wire and get picked up on a future tick, so they never
	// produce a spurious unload. At 32 Hz these limits comfortably exceed
	// any realistic spawn / cross-viewport rate while bounding worst-case
	// packet size during stress (e.g. a fresh viewer entering a 500-enemy
	// boss room hydrates over ~3 ticks instead of one ~50 KB packet).
	private static final int MAX_NEW_ENEMIES_PER_LOAD = 500;
	private static final int MAX_NEW_BULLETS_PER_LOAD = 1000;

	/** Strict set difference: {@code a ∖ b} as a fresh HashSet. */
	private static Set<Long> setDiff(final Set<Long> a, final Set<Long> b) {
		if (a.isEmpty()) return new HashSet<>();
		if (b.isEmpty()) return new HashSet<>(a);
		final Set<Long> out = new HashSet<>(a.size());
		for (final Long id : a) if (!b.contains(id)) out.add(id);
		return out;
	}

	/**
	 * Trim {@code ids} to the {@code cap} closest entities to {@code center},
	 * looked up via {@code source}. IDs whose entity is missing from
	 * {@code source} are skipped. Used to keep the per-tick LoadPacket
	 * bounded while staying deterministic (sort-then-truncate so the same
	 * tick produces the same trimmed set).
	 */
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

	/**
	 * Bullet cap with player-owned-bullet priority. A player's own
	 * projectiles always fit first — otherwise heavy enemy-bullet density
	 * could starve the player's own abilities of cap slots and the player
	 * would see enemy shots but not their own.
	 */
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

	/**
	 * Phase 3 — Knight Deflect: returns true if the incoming bullet was
	 * reflected back at its source enemy (damage skipped, bullet kept alive
	 * as a player projectile aimed at the attacker). Generic over any
	 * passive whose trigger is {@code ON_PROJECTILE_HIT_SELF} and whose
	 * condition is a {@code PROC_CHANCE} scaling — DEF for Knight, but any
	 * stat works.
	 */
	private boolean tryDeflect(final Realm targetRealm, final Bullet b, final Player player) {
		final PassiveAbility passive = player.getClassPassive();
		if (passive == null) return false;
		for (PassiveTrigger trig : passive.triggerList()) {
			if (!"ON_PROJECTILE_HIT_SELF".equalsIgnoreCase(trig.getEvent())) continue;
			double procChance = 0;
			if (trig.getConditions() != null) {
				for (AbilityScaling sc : trig.getConditions()) {
					if (!"PROC_CHANCE".equalsIgnoreCase(sc.getTarget())) continue;
					final int statIdx = sc.statIndex();
					if (statIdx < 0) continue;
					final int statVal = statByIndex(player.getComputedStats(), statIdx);
					double raw = statVal * sc.getCoeff();
					procChance = sc.getCap() > 0 ? Math.min(raw, sc.getCap()) : raw;
					break;
				}
			}
			if (procChance <= 0) return false;
			if (Math.random() >= procChance) return false;
			// Procced — redirect the bullet. Clients only learn about bullets
			// on spawn (LoadPacket diffs), so mutating in-place doesn't update
			// the client's render — the original bullet keeps flying along its
			// incoming trajectory. We instead expire the original and spawn a
			// fresh player-owned bullet so the next diff shows the new shot.
			final Vector2f pcenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
			final Vector2f target;
			if (src != null && !src.getDeath()) {
				target = src.getPos().clone(src.getSize() / 2, src.getSize() / 2);
			} else {
				// Source enemy unknown / dead — flip the bullet 180° from its
				// current heading. Bullet.angle convention: (sin, cos) is the
				// forward unit vector, so flipping means (-sin, -cos) i.e. add π
				// to the stored angle.
				final double flipped = b.getAngle() + Math.PI;
				final float fx = (float) Math.sin(flipped) * 100f;
				final float fy = (float) Math.cos(flipped) * 100f;
				target = new Vector2f(pcenter.x + fx, pcenter.y + fy);
			}
			// Reflected damage bonus: 1 + DEF * 0.005, per design doc §6.1.
			final double mul = 1.0 + (player.getComputedStats().getDef() * 0.005);
			final short deflectDamage = (short) Math.min(Short.MAX_VALUE, (int) (b.getDamage() * mul));
			// Expire the incoming bullet (client will drop it via diff).
			targetRealm.getExpiredBullets().add(b.getId());
			targetRealm.removeBullet(b);
			// Spawn a fresh player-owned bullet aimed at the attacker. addProjectile
			// negates the angle internally — matches the convention used by the
			// player shoot path (final float angle = Bullet.getAngle(source, dest)).
			final float newAngle = Bullet.getAngle(pcenter, target);
			// Triple-up the bullet in a tight fan so the deflect reads as a
			// fanned counter-volley rather than a single round trip.
			final float SPREAD = 0.10f;
			for (int i = -1; i <= 1; i++) {
				this.addProjectile(targetRealm.getRealmId(), 0L, player.getId(), b.getProjectileId(),
						-1, pcenter, newAngle + i * SPREAD,
						(short) b.getSize(), b.getMagnitude(), 300f,
						deflectDamage, false, b.getFlags(),
						(short) 0, (short) 0, player.getId());
			}
			// Deflect visuals — directional, drawn TOWARD the enemy:
			//   1) Chain-lightning arc from player center to the attacker (the
			//      "deflect path"). Long-lived (~500ms) so the eye tracks it.
			//   2) Bright wizard-burst flare at the player center for the moment
			//      of deflection (gold-ish, distinct from the knight shockwave).
			//   3) Small wizard-burst at the attacker so the arrival of the
			//      counter-volley reads even before the bullet itself lands.
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
					CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
					pcenter.x, pcenter.y, target.x, target.y, (short) 500));
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST,
					pcenter.x, pcenter.y, 36f, (short) 500, (byte) 5));
			this.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST,
					target.x, target.y, 28f, (short) 400, (byte) 5));
			this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "DEFLECT");
			return true;
		}
		return false;
	}

	/**
	 * Ninja Kage Bunshin passive (11013) — when the class passive is bound and
	 * its {@code ON_PROJECTILE_HIT_SELF} PROC_CHANCE rolls successfully, spawn a
	 * headless Player "shadow clone" identical to the source ninja that walks
	 * toward the attacker, draws aggro via TAUNT_TARGET, and vanishes with a
	 * smoke poof after CLONE_DURATION_MS. Unlike {@link #tryDeflect}, the source
	 * player still takes the original hit — the clone is purely an escape tool.
	 *
	 * Returns true iff a clone was actually spawned (purely for caller-side
	 * logging / branching — does NOT alter damage application).
	 */
	private static final long CLONE_DURATION_MS = 3000L;
	private static final long TAUNT_DURATION_MS = 3000L;
	private static final long INVINCIBLE_DURATION_MS = 3000L;

	private boolean trySpawnNinjaClone(final Realm targetRealm, final Bullet b, final Player player) {
		final PassiveAbility passive = player.getClassPassive();
		if (passive == null) return false;
		boolean isCloneTrigger = false;
		double procChance = 0;
		for (PassiveTrigger trig : passive.triggerList()) {
			if (!"ON_PROJECTILE_HIT_SELF".equalsIgnoreCase(trig.getEvent())) continue;
			// Distinguish from Knight's Deflect (same trigger, different
			// behavior). Match on passive id so a future ninja-themed
			// passive that ALSO uses this trigger doesn't accidentally
			// route through both branches.
			if (passive.getId() != 11013) continue;
			isCloneTrigger = true;
			if (trig.getConditions() == null) break;
			for (AbilityScaling sc : trig.getConditions()) {
				if (!"PROC_CHANCE".equalsIgnoreCase(sc.getTarget())) continue;
				final int statIdx = sc.statIndex();
				if (statIdx < 0) continue;
				final int statVal = statByIndex(player.getComputedStats(), statIdx);
				final double raw = statVal * sc.getCoeff();
				procChance = sc.getCap() > 0 ? Math.min(raw, sc.getCap()) : raw;
				break;
			}
			break;
		}
		if (!isCloneTrigger || procChance <= 0) return false;
		if (Math.random() >= procChance) return false;

		// Compute walk vector toward the attacker. If the source enemy is
		// gone or unknown, fall back to walking away from the incoming
		// bullet's heading (a sensible escape direction).
		final Vector2f spawnCenter = player.getPos().clone(player.getSize() / 2f, player.getSize() / 2f);
		float vx;
		float vy;
		final Enemy src = (b.getSrcEntityId() != 0L) ? targetRealm.getEnemies().get(b.getSrcEntityId()) : null;
		if (src != null && !src.getDeath()) {
			final Vector2f srcCenter = src.getPos().clone(src.getSize() / 2f, src.getSize() / 2f);
			float dirX = srcCenter.x - spawnCenter.x;
			float dirY = srcCenter.y - spawnCenter.y;
			final float mag = (float) Math.sqrt(dirX * dirX + dirY * dirY);
			if (mag > 0.001f) { dirX /= mag; dirY /= mag; } else { dirX = 1f; dirY = 0f; }
			vx = dirX;
			vy = dirY;
		} else {
			// Walk along the bullet's forward heading. Bullet.angle convention:
			// (sin, cos) = forward unit vector.
			vx = (float) Math.sin(b.getAngle());
			vy = (float) Math.cos(b.getAngle());
		}
		// Convert unit vector to per-tick step matching the source player's
		// movement speed — same formula as movePlayer's applyMovementTick so
		// the clone visibly moves like a real player.
		final float tilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
		final float pxPerTick = tilesPerSec * 32.0f / 64.0f;
		final float dx = vx * pxPerTick;
		final float dy = vy * pxPerTick;

		// Build the clone as a headless+bot Player of the same class. Same
		// class id means the renderer pulls the same sprite — i.e. an
		// identical visual copy of the source ninja, per spec.
		final CharacterClass cls = CharacterClass.valueOf(player.getClassId());
		if (cls == null) return false;
		final long cloneId = Realm.RANDOM.nextLong();
		final Player clone = new Player(cloneId, spawnCenter.clone(), player.getSize(), cls);
		clone.setName(player.getName());
		clone.setHeadless(true);
		clone.setBot(true);
		clone.setAccountUuid(UUID.randomUUID().toString());
		clone.setCharacterUuid(UUID.randomUUID().toString());
		// INVINCIBLE so stray hits don't kill the clone before its 3s
		// timer is up; TAUNT_TARGET so enemy targeting (getClosestPlayer's
		// taunt pass) prefers shooting the clone over the real ninja —
		// turning the clone into a real aggro magnet, not just decoration.
		clone.addEffect(StatusEffectType.INVINCIBLE, INVINCIBLE_DURATION_MS);
		clone.addEffect(StatusEffectType.TAUNT_TARGET, TAUNT_DURATION_MS);
		// Walk-anim flags so the client animates the clone moving instead
		// of standing in place. dx/dy alone wouldn't drive the walk cycle.
		clone.setRight(dx > 0.01f);
		clone.setLeft(dx < -0.01f);
		clone.setDown(dy > 0.01f);
		clone.setUp(dy < -0.01f);

		targetRealm.addPlayer(clone);
		targetRealm.registerClone(cloneId, player.getId(), dx, dy, CLONE_DURATION_MS);

		// Spawn smoke FX so the clone appears to materialize out of a puff
		// rather than just popping in. Matched on despawn by processClones.
		this.enqueueServerPacketToRealm(targetRealm,
				CreateEffectPacket.aoeEffect(CreateEffectPacket.EFFECT_SMOKE_POOF,
						spawnCenter.x, spawnCenter.y, 40f, (short) 600));
		this.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "BUNSHIN");
		return true;
	}

	/** Helper — Stats lookup by the same index used in AbilityScaling.statIndex(). */
	private static int statByIndex(Stats s, int idx) {
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

	/**
	 * Phase 2D — resolve a scaling's input value, honoring SKILL_POINTS (index 8)
	 * which reads from the player's invested level for the parent Ability. Falls
	 * back to {@link #statByIndex(Stats, int)} for all real stats.
	 */
	private static int resolveScalingInput(Player player, Ability ab, AbilityScaling sc) {
		final int idx = sc.statIndex();
		if (idx == 8) {
			return (player == null || ab == null) ? 0 : player.getSkillLevel(ab.getId());
		}
		return statByIndex(player == null ? null : player.getComputedStats(), idx);
	}

	private void processPlayerHit(final long realmId, final Bullet b, final Player p) {
		final Realm targetRealm = this.realms.get(realmId);
		final Player player = targetRealm.getPlayer(p.getId());
		if (player == null)
			return;
		if (circleHit(b, player) && b.isEnemy() && !b.isPlayerHit()) {
			// Phase 3 — Knight's Deflect passive. DEF-scaled chance to reflect
			// the bullet back at its source enemy instead of taking damage.
			// Triggered before playerHit is set so the bullet stays alive and
			// keeps flying as a player-owned projectile.
			if (this.tryDeflect(targetRealm, b, player)) {
				return;
			}
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
			// WEAKEN on the firing enemy — outgoing damage reduced by 35%.
			// Look up the source by id; only enemy-source projectiles apply
			// the WEAKEN scale here (the player-source path scales WEAKEN on
			// outgoing damage instead).
			if (b.getSrcEntityId() != 0L) {
				final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
				if (src != null && src.hasEffect(StatusEffectType.WEAKEN)) {
					rawDmg = (short)(rawDmg * 0.65);
				}
			}
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
			// Ninja Kage Bunshin — roll AFTER damage (player still eats the
			// hit, the clone is an escape tool, not a damage shield). Reads
			// the player's class passive and proc-chance scaling internally.
			this.trySpawnNinjaClone(targetRealm, b, player);
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
				// Heavy Debuffer "Precision Striker" (passive 11014) — grant
				// floor(caster.DEX / PRECISION_STRIKER_DEX_DIVISOR) of armor
				// penetration so this shot bypasses that many points of DEF
				// before the standard mitigation kicks in. Stacks under the
				// full-ignore branch (which already takes priority above).
				int effectiveDef = model.getStats().getDef();
				if (b.getSrcEntityId() != 0L) {
					final Player src = this.getPlayerById(b.getSrcEntityId());
					if (src != null) {
						final PassiveAbility pass = src.getClassPassive();
						if (pass != null && pass.getId() == 11014) {
							final int pen = src.getComputedStats().getDex() / PRECISION_STRIKER_DEX_DIVISOR;
							effectiveDef = Math.max(0, effectiveDef - pen);
						}
					}
				}
				dmgToInflict = (short) (b.getDamage() - effectiveDef);
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
				// WEAKEN on the source — outgoing damage reduced by 35%.
				if (fromPlayer != null && fromPlayer.hasEffect(StatusEffectType.WEAKEN)) {
					dmgToInflict = (short)(dmgToInflict * 0.65);
				}
			}
			// CURSED enemies take 25% more damage from all sources
			if (e.hasEffect(StatusEffectType.CURSED)) {
				dmgToInflict = (short)(dmgToInflict * 1.25);
			}
			// VULNERABLE is a stronger "burst window" debuff: +40% damage
			// taken AND new debuff durations are doubled (handled in
			// Entity.addEffect). Stacks multiplicatively with CURSED for the
			// classic Trickster→Sorcerer combo.
			if (e.hasEffect(StatusEffectType.VULNERABLE)) {
				dmgToInflict = (short)(dmgToInflict * 1.40);
			}

			targetRealm.hitEnemy(b.getId(), e.getId());
			// Phase 3 passive — on-hit triggers. Class passives can register
			// ON_BULLET_HIT_ENEMY with a PROC_CHANCE scaling against any stat
			// to roll a status application. Assassin Lethal Wound uses this to
			// proc POISONED with 15% base + 1%/DEX (cap 60%). Generic so other
			// classes can hang procs off the same hook.
			if (b.getSrcEntityId() != 0L) {
				final Player srcPlayer = this.getPlayerById(b.getSrcEntityId());
				if (srcPlayer != null) {
					final PassiveAbility pa = srcPlayer.getClassPassive();
					if (pa != null) {
						for (PassiveTrigger trig : pa.triggerList()) {
							if (!"ON_BULLET_HIT_ENEMY".equalsIgnoreCase(trig.getEvent())) continue;
							// Per-passive base proc rate. Each new on-hit
							// passive registers its own floor here so the
							// scaling conditions in passives.json only
							// describe the WIS/DEX/etc. growth, not the
							// constant. Falls back to 15% for any unlisted
							// passive (matches the original Lethal Wound
							// behavior pre-Trickster).
							double procChance;
							switch (pa.getId()) {
								case 11007: procChance = 0.15; break; // Lethal Wound
								case 11012: procChance = 0.12; break; // Sleight of Hand
								default:    procChance = 0.15; break;
							}
							if (trig.getConditions() != null) {
								for (AbilityScaling sc : trig.getConditions()) {
									if (!"PROC_CHANCE".equalsIgnoreCase(sc.getTarget())) continue;
									final int statIdx = sc.statIndex();
									if (statIdx < 0) continue;
									final int statVal = statByIndex(srcPlayer.getComputedStats(), statIdx);
									final double bonus = statVal * sc.getCoeff();
									procChance += bonus;
								}
								// honor cap if any condition set it
								for (AbilityScaling sc : trig.getConditions()) {
									if ("PROC_CHANCE".equalsIgnoreCase(sc.getTarget()) && sc.getCap() > 0) {
										procChance = Math.min(procChance, sc.getCap());
									}
								}
							}
							if (procChance > 0 && Math.random() < procChance) {
								// Assassin Lethal Wound — POISONED 3s.
								if (pa.getId() == 11007) {
									applyStatusWithFeedback(targetRealm, e, EntityType.ENEMY,
											StatusEffectType.POISONED, 3000);
								}
								// Trickster Sleight of Hand — MARK enemy for
								// 8s. If the marked enemy dies inside that
								// window, enemyDeath's loot path bumps every
								// qualifying player's upgrade chance by +25%.
								// Base 12% + 0.4%/DEX (cap 40%) via the trigger
								// conditions; the base is the procChance
								// starting value in this hook (0.12 below).
								if (pa.getId() == 11012) {
									applyStatusWithFeedback(targetRealm, e, EntityType.ENEMY,
											StatusEffectType.MARKED_FOR_LOOT, 8000);
								}
							}
						}
					}
				}
			}
			e.setHealth(e.getHealth() - dmgToInflict);
			int maxHealth = (int) (model.getHealth() * e.getDifficulty());
			e.setHealthpercent((float) e.getHealth() / (float) maxHealth);
			// Lifesteal: heal the source player by % of dealt damage from any
			// gem-equipped item. Currently only the weapon (slot 0) can carry
			// gems; the legacy ability slot was removed in Phase 1B.
			if (b.getSrcEntityId() != 0L) {
				final Player healSrc = this.getPlayerById(b.getSrcEntityId());
				if (healSrc != null && dmgToInflict > 0) {
					int totalLifestealPct = 0;
					if (healSrc.getInventory()[0] != null) {
						totalLifestealPct += CombatModifiers
								.fromItem(healSrc.getInventory()[0]).getLifestealPct();
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

			// Cyan damage text when armor is pierced or broken. Use the
			// bullet's CURRENT center as the impact point so the floating
			// number renders where the shot actually landed — not at wherever
			// the enemy has walked to by the time the packet reaches the
			// client. (Without this, fast-moving enemies make the damage text
			// snap to their new position, visually disconnecting hit and
			// number — see webclient game.js handleTextEffect.)
			final TextEffect dmgTextEffect = (armorPiercing || armorBroken) ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
			final float impactX = b.getPos().x + b.getSize() * 0.5f;
			final float impactY = b.getPos().y + b.getSize() * 0.5f;
			this.broadcastTextEffect(targetRealm, EntityType.ENEMY, e, dmgTextEffect, "-" + dmgToInflict, impactX, impactY);
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
			damage = (short) (damage + player.getStats().getStr());
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
			damage = (short) (damage + player.getStats().getStr());
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

			// Notify the overseer of the kill (handles taunts, event spawning)
			// NOTE: We get qualifying players BEFORE clearing damage tracking for soulbound loot
			List<Long> qualifyingPlayerIds = (targetRealm.getOverseer() != null)
					? targetRealm.getOverseer().getQualifyingPlayers(enemy.getId())
					: new ArrayList<>();
			// Party-shared loot eligibility: expand the qualifying-players
			// list to include every party member of any qualifying player
			// (deduped). The "earned a soulbound roll" club grows to include
			// teammates who helped tank/cc/heal without dealing top damage.
			if (!qualifyingPlayerIds.isEmpty()) {
				final LinkedHashSet<Long> expanded = new LinkedHashSet<>(qualifyingPlayerIds);
				for (final Long qid : qualifyingPlayerIds) {
					final long pid = this.partyManager.getPartyId(qid);
					if (pid == 0L) continue;
					for (final Long memberId : this.partyManager.getPartyMembers(qid)) {
						if (memberId == null || memberId.equals(qid)) continue;
						// Only credit teammates currently in the same realm
						// (no claim-from-character-select abuse).
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
			float upgradeChance = upgradeEligible
					? (GlobalConstants.LOOT_TIER_UPGRADE_BASE_PERCENT
							+ GlobalConstants.LOOT_TIER_UPGRADE_PER_DIFFICULTY * diff) / 100.0f
					: 0.0f;
			// MARKED_FOR_LOOT — Trickster's passive proc. When this enemy dies
			// while carrying the mark, every qualifying player's upgrade roll
			// gets +25% (additive). Effect applies whether the trickster
			// landed the killing blow or not, so the WHOLE party benefits as
			// long as the mark was active when the kill resolved.
			if (enemy.hasEffect(StatusEffectType.MARKED_FOR_LOOT)) {
				upgradeChance = Math.min(1.0f, upgradeChance + 0.25f);
			}

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
	 *
	 * Prefer {@link #invalidateLoadStateForPlayer(long)} when a SINGLE player
	 * is joining — the normal delta logic in enqueueGameData picks up the new
	 * roster entry automatically when the new player enters each existing
	 * client's viewport (it's just another entity that wasn't in the
	 * recipient's ledger). Wiping the whole realm's ledger here forces every
	 * existing player to redo a full Load on the next tick, which under
	 * 1500-enemy / 4500-bullet load was costing ~150-300 ms on the tick
	 * thread per join.
	 */
	public void invalidateRealmLoadState(Realm realm) {
		for (final Long pid : realm.getPlayers().keySet()) {
			this.playerLoadLedger.remove(pid);
			this.playerLastFullSnapshotMs.remove(pid);
		}
	}

	/**
	 * Targeted variant — only the named player's ledger is cleared. Use this
	 * for player-join (the joining client needs a full snapshot of their new
	 * realm; their neighbors get the join via the natural delta path).
	 */
	public void invalidateLoadStateForPlayer(long playerId) {
		this.playerLoadLedger.remove(playerId);
		this.playerLastFullSnapshotMs.remove(playerId);
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
				// Only the joining player needs a full Load snapshot built
				// next tick — neighbors will pick up the new entity through
				// the natural ledger-vs-viewport delta in enqueueGameData
				// without paying the cost of rebuilding their entire 1500-
				// enemy / 4500-bullet visible set. The previous full-realm
				// invalidation was the dominant cause of the 10-20 tick
				// stalls observed when a player logged in mid-stress-test.
				this.invalidateLoadStateForPlayer(join.player.getId());
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

	/**
	 * Phase 4 — build + broadcast a {@link PartyUpdatePacket} to every member
	 * of {@code partyId}. Pulls each member's live HP/MP/level via a global
	 * playerId lookup so cross-realm parties (one member in nexus, others in
	 * the overworld) still see each other's bars.
	 *
	 * Callers: roster-change events (invite accept, leave, disband) and the
	 * periodic refresh in the tick loop.
	 */
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
			// Hotbar bindings (4 ability ids) — copied as a boxed array so the
			// streamable collection codec doesn't choke on null. Same for the
			// cooldown end-times. Both arrays are exactly 4 long; UI iterates.
			final int[] hb = p.getHotbarBindings();
			final Integer[] hbBoxed = new Integer[4];
			for (int i = 0; i < 4; i++) hbBoxed[i] = (hb != null && i < hb.length) ? hb[i] : 0;
			m.setHotbarBindings(hbBoxed);
			final long[] cds = p.getAbilityCooldowns();
			final Long[] cdBoxed = new Long[4];
			for (int i = 0; i < 4; i++) cdBoxed[i] = (cds != null && i < cds.length) ? cds[i] : 0L;
			m.setAbilityCooldownEnds(cdBoxed);
			// Phase 4 — teammate ability tooltip parity. Carry the invested
			// level for each hotbar slot so the party panel can render
			// damage / per-level scaling / cooldown REDUCTION using THEIR
			// skill points instead of always defaulting to 0.
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
				// Don't persist a character that the data service has already
				// soft-deleted (delete/death raced ahead of this 12s sync) —
				// the data service refuses the write anyway, this just saves
				// a round trip and a noisy warn log.
				if (character.isDeleted()) {
					RealmManagerServer.log.info(
							"[SERVER] Skipping persist for character {} on account {} — already soft-deleted on data service.",
							character.getCharacterUuid(), account.getAccountEmail());
					return false;
				}
				final CharacterStatsDto newStats = player.serializeStats();
				final Set<GameItemRefDto> newItems = player.serializeItems();
				character.setItems(newItems);
				character.setStats(newStats);
				final CharacterDto savedStats = ServerGameLogic.DATA_SERVICE.executePost(
						"/data/account/character/" + character.getCharacterUuid(), character, CharacterDto.class);
				RealmManagerServer.log.info("[SERVER] Succesfully persisted user account {}",
						account.getAccountEmail());
				// Lifetime metrics flush — drain the in-memory deltas and POST
				// them as a $inc payload. On failure (HTTP / serialization),
				// merge the delta back into the counters so the next window's
				// events stack on top instead of losing the work. See
				// docs/player-metrics-design.md.
				try {
					final PlayerMetrics m = player.getMetrics();
					if (m != null) {
						final MetricsDelta delta = m.drainAndReset();
						if (delta != null) {
							final MetricsDeltaDto dto = MetricsDeltaDto.from(delta);
							try {
								ServerGameLogic.DATA_SERVICE.executePost(
										"/data/account/character/" + character.getCharacterUuid() + "/metrics/delta",
										dto, Object.class);
							} catch (Exception postEx) {
								RealmManagerServer.log.warn(
										"[METRICS] Flush failed for {} — re-queueing delta. Reason: {}",
										character.getCharacterUuid(), postEx.getMessage());
								m.mergeBack(delta);
							}
						}
					}
				} catch (Exception metricsEx) {
					RealmManagerServer.log.error(
							"[METRICS] Unexpected error during flush for {}: {}",
							character.getCharacterUuid(), metricsEx.getMessage());
				}
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
	 * Phase 3 — apply a status to an entity AND broadcast a floating-text label
	 * (e.g. "BRACED") over their head so the player sees feedback. Use this in
	 * place of bare {@code entity.addEffect(...)} wherever an ability gives
	 * combat feedback. Status enum's {@code name()} is the label.
	 */
	public void applyStatusWithFeedback(final Realm realm, final Entity entity,
			final EntityType entityType, final StatusEffectType status, final long durationMs) {
		if (entity == null || status == null) return;
		entity.addEffect(status, durationMs);
		this.broadcastTextEffect(realm, entityType, entity, TextEffect.PLAYER_INFO, status.name());
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
		broadcastTextEffect(realm, entityType, entity, effect, text, 0f, 0f);
	}

	/**
	 * Same as the four-arg overload but carries an explicit world-space impact
	 * point. Use this for bullet-vs-enemy damage text so the client renders the
	 * number where the projectile actually struck rather than wherever the
	 * target has moved to by the time the packet is processed. Pass 0,0 to fall
	 * back to the target's current position.
	 */
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
