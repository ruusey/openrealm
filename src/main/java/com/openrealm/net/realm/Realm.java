package com.openrealm.net.realm;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.openrealm.account.dto.ChestDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.GameObject;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.Portal;
import com.openrealm.game.entity.item.Chest;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.DungeonGraphNode;
import com.openrealm.game.model.EnemyGroup;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.MapModel;
import com.openrealm.game.model.OverworldZone;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.SetPiece;
import com.openrealm.game.model.SetPieceModel;
import com.openrealm.game.model.StaticSpawn;
import com.openrealm.game.model.TerrainGenerationParameters;
import com.openrealm.game.tile.Tile;
import com.openrealm.game.tile.TileData;
import com.openrealm.game.tile.TileManager;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.client.packet.LoadPacket;
import com.openrealm.net.client.packet.ObjectMovePacket;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.entity.NetObjectMovement;
import com.openrealm.net.server.ServerGameLogic;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.util.WorkerThread;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@AllArgsConstructor
@Slf4j
public class Realm {
    // Shared Secure Random instance for generating Ids and other random data
    public static final transient SecureRandom RANDOM = new SecureRandom();
    private long realmId;
    private int mapId;
    private String nodeId;
    // For non-shared dungeon instances, the realmId of the parent (overworld / nexus)
    // realm the player came from. Used by the cowardice portal and the boss-drop exit
    // portal so both know where to return the player. 0 = no source (shared realm).
    private long sourceRealmId;
    // The enemyId of this dungeon's designated boss. Set at realm creation time when
    // the boss is spawned. When this enemy dies, an exit portal is dropped regardless
    // of whether the boss has a loot table. 0 = no designated boss.
    private int dungeonBossEnemyId;
    private Map<Long, Player> players;
    private Map<Long, Bullet> bullets;
    private Map<Long, List<Long>> bulletHits;
    private Map<Long, Enemy> enemies;
    private int initialEnemyCount; // Snapshot of enemy count after initial spawn, used for respawn threshold
    private Map<Long, LootContainer> loot;
    private Map<Long, Portal> portals;

    private List<Long> expiredEnemies;
    private List<Long> expiredBullets;
    private List<Long> expiredPlayers;
    private Map<Long, Long> playerLastShotTime;
    private TileManager tileManager;
    // Compact short ID allocator for bandwidth-efficient movement packets
    private ShortIdAllocator shortIdAllocator = new ShortIdAllocator();
    private final ReentrantLock playerLock = new ReentrantLock();

    // Spatial hash grid for O(1) neighbor lookups (cell size = viewport radius)
    private transient SpatialHashGrid spatialGrid;
    // Per-tick cache of NetObjectMovement instances keyed by entity id. Lets
    // multiple viewers in the same realm share a single allocation per entity
    // per tick instead of building a fresh instance each. Cleared at the
    // start of each enqueueGameData() pass via clearTickMovementCache().
    // Critical for two scenarios:
    //   1. ~40 players clustered in nexus — without sharing, each of 40
    //      viewers built its own NetObjectMovement[] of the other ~39
    //      players + N enemies; with the cache each entity is built ONCE.
    //   2. ~10K total enemies with sparse viewers — the spatial query
    //      already filters off-screen enemies; cache only ever holds the
    //      few that are actually in someone's viewport.
    private transient Map<Long, NetObjectMovement> tickMovementCache;
    // Per-tick cache of stripped (no-inventory) UpdatePacket instances for
    // other-player broadcast at 8 Hz. Each viewer's broadcast loop iterates
    // up to 20 nearby players and previously built each one's stripped
    // UpdatePacket from scratch — 40 viewers × 20 nearby × 8 Hz = 6400
    // builds/sec, each doing 20 inventory ModelMapper.map() calls before
    // throwing the inventory away. With the cache, each player is built
    // ONCE per 8-Hz tick total. ~50× CPU win on the other-player broadcast
    // path during 40-player nexus scenarios.
    private transient Map<Long, UpdatePacket> tickStrippedUpdateCache;

    // Overseer AI for ecosystem management (enemy population, events, taunts)
    private transient RealmOverseer overseer;

    // Poison damage-over-time tracking. Each entry ticks independently (poisons stack).
    private final List<PoisonDotState> activePoisonDots = new ArrayList<>();
    private static final long POISON_TICK_INTERVAL_MS = 200;

    // Pending poison throws — tracked per tick instead of blocking a thread pool thread.
    private final List<PoisonThrowState> pendingPoisonThrows = new ArrayList<>();

    // Active traps — Huntress trap zones that trigger when enemies walk into them.
    private final List<TrapState> activeTraps = new ArrayList<>();

    // Active decoys — lightweight tick-driven entities for Trickster prism ability.
    private final List<DecoyState> activeDecoys = new ArrayList<>();

    // Active realm events — globally announced boss encounters with terrain + minion waves.
    private final List<ActiveRealmEvent> activeRealmEvents = new ArrayList<>();

    static class ActiveRealmEvent {
        final int eventId;
        final long bossEnemyId;
        final long spawnTime;
        final long durationMs;
        final int tileX, tileY;
        final int[][] savedBase;
        final int[][] savedCollision;
        final Set<Long> minionIds = new HashSet<>();
        final boolean[] wavesTriggered;
        boolean completed;

        ActiveRealmEvent(int eventId, long bossEnemyId, int tileX, int tileY,
                         int[][] savedBase, int[][] savedCollision, int waveCount, long durationMs) {
            this.eventId = eventId;
            this.bossEnemyId = bossEnemyId;
            this.spawnTime = Instant.now().toEpochMilli();
            this.durationMs = durationMs;
            this.tileX = tileX;
            this.tileY = tileY;
            this.savedBase = savedBase;
            this.savedCollision = savedCollision;
            this.wavesTriggered = new boolean[waveCount];
            this.completed = false;
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - spawnTime >= durationMs;
        }
    }

    public List<ActiveRealmEvent> getActiveRealmEvents() {
        return this.activeRealmEvents;
    }

    static class PoisonThrowState {
        final long landTime;
        final long sourcePlayerId;
        final float landX;
        final float landY;
        final float radius;
        final int totalDamage;
        final long poisonDuration;
        final byte tier;

        PoisonThrowState(long delayMs, long sourcePlayerId, float landX, float landY,
                         float radius, int totalDamage, long poisonDuration, byte tier) {
            this.landTime = Instant.now().toEpochMilli() + delayMs;
            this.sourcePlayerId = sourcePlayerId;
            this.landX = landX;
            this.landY = landY;
            this.radius = radius;
            this.totalDamage = totalDamage;
            this.poisonDuration = poisonDuration;
            this.tier = tier;
        }

        boolean hasLanded() {
            return Instant.now().toEpochMilli() >= landTime;
        }
    }

    static class TrapState {
        /** Time between landing and the trap becoming dangerous. Without
         *  this gap, an enemy already standing where the throw lands would
         *  arm + trigger the trap on the same tick — the player saw enemies
         *  vanish without ever seeing the placed-trap or trigger visuals.
         *  500ms gives both visuals time to display and matches the
         *  expected "huntress sets trap, lures enemy in" gameplay loop. */
        static final long ARM_TIME_MS = 500;

        final long placeTime;   // when the trap was placed (after throw lands)
        final long armReadyTime;// when the trap becomes dangerous (placeTime + ARM_TIME_MS)
        final long expireTime;  // when the trap disappears if not triggered
        final long sourcePlayerId;
        final float x, y;
        final float triggerRadius; // enemies within this radius trigger the trap
        final short effectId;     // effect to apply (e.g., PARALYZED=2)
        final long effectDuration;
        final int damage;
        final byte tier;          // ability item tier — drives client tint
        boolean armed = false;    // becomes armed after throw lands
        boolean triggered = false;

        TrapState(long throwDelayMs, long sourcePlayerId, float x, float y,
                  float triggerRadius, short effectId, long effectDuration, int damage,
                  long lifetimeMs, byte tier) {
            this.placeTime = Instant.now().toEpochMilli() + throwDelayMs;
            this.armReadyTime = this.placeTime + ARM_TIME_MS;
            this.expireTime = this.placeTime + lifetimeMs;
            this.sourcePlayerId = sourcePlayerId;
            this.x = x;
            this.y = y;
            this.triggerRadius = triggerRadius;
            this.effectId = effectId;
            this.effectDuration = effectDuration;
            this.damage = damage;
            this.tier = tier;
        }

        boolean hasLanded() { return Instant.now().toEpochMilli() >= placeTime; }
        boolean isArmed()   { return Instant.now().toEpochMilli() >= armReadyTime; }
        boolean isExpired() { return Instant.now().toEpochMilli() >= expireTime; }
    }

    static class PoisonDotState {
        final long enemyId;
        final int totalDamage;
        final long duration;
        final long startTime;
        final long sourcePlayerId;
        int damageApplied;
        long lastTickTime;

        PoisonDotState(long enemyId, int totalDamage, long duration, long sourcePlayerId) {
            this.enemyId = enemyId;
            this.totalDamage = totalDamage;
            this.duration = duration;
            this.startTime = Instant.now().toEpochMilli();
            this.sourcePlayerId = sourcePlayerId;
            this.damageApplied = 0;
            this.lastTickTime = this.startTime;
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - startTime >= duration;
        }
    }

    static class DecoyState {
        final long enemyId;
        final long sourcePlayerId;
        final long spawnTime;
        final long durationMs;
        final float dx;
        final float dy;
        final float maxTravelDistSq;
        final float originX;
        final float originY;
        boolean stopped;

        DecoyState(long enemyId, long sourcePlayerId, float originX, float originY,
                   float dx, float dy, float maxTravelDist, long durationMs) {
            this.enemyId = enemyId;
            this.sourcePlayerId = sourcePlayerId;
            this.spawnTime = Instant.now().toEpochMilli();
            this.durationMs = durationMs;
            this.dx = dx;
            this.dy = dy;
            this.maxTravelDistSq = maxTravelDist * maxTravelDist;
            this.originX = originX;
            this.originY = originY;
            this.stopped = false;
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - spawnTime >= durationMs;
        }
    }

    private boolean isServer;
    private boolean shutdown = false;

    public Realm(boolean isServer, int mapId) {
        this.realmId = Realm.RANDOM.nextLong();
        this.players = new ConcurrentHashMap<>();
        this.isServer = isServer;
        this.expiredEnemies = new ArrayList<>();
        this.expiredPlayers = new ArrayList<>();
        this.expiredBullets = new ArrayList<>();
        this.playerLastShotTime = new HashMap<>();
        this.spatialGrid = new SpatialHashGrid(10 * GlobalConstants.BASE_TILE_SIZE);
        this.loadMap(mapId);
        if (this.isServer) {
            WorkerThread.submitAndForkRun(this.getStatsThread());
        }
    }

    public Realm(boolean isServer, int mapId, String nodeId) {
        this(isServer, mapId);
        this.nodeId = nodeId;
    }

    /**
     * Returns true if this realm is a shared/persistent realm (e.g., overworld, nexus).
     * Non-shared realms are dungeon instances that get cleaned up when empty.
     */
    public boolean isShared() {
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return node.isShared();
        }
        return false;
    }

    /**
     * Returns true if this realm was created as a dungeon instance via a portal
     * transition (handleUsePortalServer sets sourceRealmId to the parent realm).
     * Used by the difficulty-based damage scaler: dungeon enemies start scaling
     * one difficulty level earlier than overworld-zone enemies of the same number.
     */
    public boolean isDungeonInstance() {
        return this.sourceRealmId != 0L;
    }

    /**
     * Returns true if this realm is the overworld entry point (the top-level shared realm
     * where enemies respawn). Replaces the old depth == 0 checks.
     */
    public boolean isOverworld() {
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return node.isEntryPoint() || node.isShared();
        }
        return false;
    }

    public List<Long> getExpiredPlayers() {
        return this.expiredPlayers;
    }
    
    public Set<Player> getPlayersExcept(long playerId){
    	return this.players.values().stream().filter(p->p.getId()!=playerId).collect(Collectors.toSet());
    }

    /** Set true at end of setupChests once vault chests have been spawned
     *  into the loot map. serializeChests() refuses to return a chest list
     *  before this flips, so a save-and-clear race (disconnect / portal-out
     *  fired during vault-realm setup) can't write an empty list and wipe
     *  the player's persisted chests. */
    private volatile boolean chestsLoaded = false;

    public void setupChests(final Player player) {
        try {
            final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE
                    .executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
            final List<ChestDto> vaultChests = account.getPlayerVault();
            final int count = vaultChests.size();
            if (count == 0) {
                // Empty vault is a valid state — mark loaded so a subsequent
                // save (e.g., player adds chests in this session) can persist.
                this.chestsLoaded = true;
                return;
            }

            // Layout: 2-column grid centered in the vault room
            // Vault map is 32x32 tiles (32px each). Inner room roughly tiles 10-22 x 8-24.
            // Center of room: tile (16, 16) = pixel (512, 512)
            final int cols = 2;
            final int rows = (int) Math.ceil(count / (double) cols);
            final int spacingX = 64;  // horizontal gap between columns
            final int spacingY = 48;  // vertical gap between rows
            final float centerX = 16 * 32;  // map center X
            final float startY = 16 * 32 - (rows * spacingY) / 2f + spacingY / 2f; // vertically centered
            final float leftColX = centerX - spacingX;
            final float rightColX = centerX + spacingX;

            for (int i = 0; i < count; i++) {
                final ChestDto chest = vaultChests.get(i);
                final int col = i % cols;
                final int row = i / cols;
                final float x = col == 0 ? leftColX : rightColX;
                final float y = startY + row * spacingY;

                final List<GameItem> itemsInChest = chest.getItems().stream()
                        .map(GameItem::fromGameItemRef).collect(Collectors.toList());
                final Chest toSpawn = new Chest(new Vector2f(x, y),
                        itemsInChest.toArray(new GameItem[8]));
                // Vault chests are soulbound to the owning player
                toSpawn.setSoulboundPlayerId(player.getId());
                this.addLootContainer(toSpawn);
            }
            this.chestsLoaded = true;
        } catch (Exception e) {
            Realm.log.error("Failed to get player account for chests. Reason: {}", e);
            // Do NOT set chestsLoaded=true on failure — a subsequent save
            // would otherwise write an empty list and wipe the vault.
        }
    }

    public List<ChestDto> serializeChests() {
        final List<ChestDto> result = new ArrayList<ChestDto>();
        int ordinal = 0;
        for (final LootContainer container : this.loot.values()) {
            if (container instanceof Chest) {
                final ChestDto chest = ChestDto.builder().chestId(container.getUid()).chestUuid(container.getUid())
                        .ordinal(ordinal++).build();
                final List<GameItemRefDto> itemRefs = new ArrayList<>();
                for (int i = 0; i < container.getItems().length; i++) {
                    final GameItem toCopy = container.getItems()[i];
                    if (toCopy != null) {
                        itemRefs.add(GameItemRefDto.builder().itemId(toCopy.getItemId()).itemUuid(toCopy.getUid())
                                .slotIdx(i).build());
                    }
                }
                chest.setItems(itemRefs);
                result.add(chest);
            }
        }
        // After the for-loop runs, mark loaded if it produced anything.
        // Callers should null-check before persisting (see chestsLoaded
        // doc and the four save sites in ServerGameLogic /
        // RealmManagerServer).
        if (!result.isEmpty()) {
            this.chestsLoaded = true;
        }
        return result;
    }

    /**
     * Safe variant of serializeChests: returns null when the realm has not
     * yet finished spawning chests via setupChests, OR when the realm is a
     * non-vault realm where serializing makes no sense. Use this in any
     * code path that POSTs to /account/{uuid}/chest, since that endpoint
     * BULK-REPLACES the vault — a serialize-too-early race would write an
     * empty list and wipe the player's persisted chests.
     */
    public List<ChestDto> serializeChestsForSave() {
        if (!this.chestsLoaded) {
            Realm.log.warn("[REALM] Refusing to serialize chests before setupChests has completed (realmId={}, mapId={}). Skipping save to avoid wipe.",
                    this.realmId, this.mapId);
            return null;
        }
        return this.serializeChests();
    }

    public void loadMap(int mapId) {
        this.mapId = mapId;
        this.bullets = new ConcurrentHashMap<>();
        this.enemies = new ConcurrentHashMap<>();
        this.loot = new ConcurrentHashMap<>();
        this.portals = new ConcurrentHashMap<>();

        this.bulletHits = new ConcurrentHashMap<>();
        if (this.isServer) {
            this.tileManager = new TileManager(mapId);
        } else {
            this.tileManager = new TileManager(GameDataManager.MAPS.get(mapId));
        }
    }
    
    public void clearData() {
        this.bullets = new ConcurrentHashMap<>();
        this.enemies = new ConcurrentHashMap<>();
        this.loot = new ConcurrentHashMap<>();
        this.portals = new ConcurrentHashMap<>();
        this.players = new ConcurrentHashMap<>();
        this.bulletHits = new ConcurrentHashMap<>();
        this.expiredEnemies = new ArrayList<>();
        this.expiredEnemies = new ArrayList<>();
        this.expiredPlayers = new ArrayList<>();
        this.playerLastShotTime = new ConcurrentHashMap<>();
        if (this.spatialGrid != null) {
            this.spatialGrid.clear();
        }
    }

    public long addPlayer(Player player) {
        this.acquirePlayerLock();
        this.players.put(player.getId(), player);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(player.getId(), player.getPos().x, player.getPos().y);
        }
        this.shortIdAllocator.getOrAssign(player.getId());
        this.releasePlayerLock();
        return player.getId();
    }
    
    public long addPlayerIfNotExists(Player player) {
        if (!this.players.containsKey(player.getId())) {
            this.acquirePlayerLock();
            this.players.put(player.getId(), player);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(player.getId(), player.getPos().x, player.getPos().y);
            }
            this.releasePlayerLock();
        }
        return player.getId();
    }

    public boolean removePlayer(Player player) {
        this.acquirePlayerLock();
        this.playerLastShotTime.remove(player.getId());
        final Player p = this.players.remove(player.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(player.getId());
        }
        this.shortIdAllocator.release(player.getId());
        this.releasePlayerLock();
        return p != null;
    }

    public boolean hasHitEnemy(long bulletId, long enemyId) {
        return (this.bulletHits.get(bulletId) != null) && this.bulletHits.get(bulletId).contains(enemyId);
    }

    public void clearHitMap() {
        this.bulletHits.clear();
    }

    public void hitEnemy(long bulletId, long enemyId) {
        if (this.bulletHits.get(bulletId) == null) {
            final List<Long> hits = new ArrayList<>();
            hits.add(enemyId);
            this.bulletHits.put(bulletId, hits);
        } else {
            final List<Long> curr = this.bulletHits.get(bulletId);
            curr.add(enemyId);
            this.bulletHits.put(bulletId, curr);
        }
    }

    public boolean removePlayer(long playerId) {
        this.acquirePlayerLock();
        final Player p = this.players.remove(playerId);
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(playerId);
        }
        this.shortIdAllocator.release(playerId);
        this.releasePlayerLock();
        return p != null;
    }

    public Player getPlayer(long playerId) {
        this.acquirePlayerLock();
        final Player p = this.players.get(playerId);
        this.releasePlayerLock();
        return p;
    }
    
    public Bullet getBullet(long bulletId) {
        return this.bullets.get(bulletId);
    }

    public long addBullet(Bullet b) {
        this.bullets.put(b.getId(), b);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(b.getId(), b.getPos().x, b.getPos().y);
        }
        return b.getId();
    }

    public long addBulletIfNotExists(Bullet b) {
        final Bullet existing = this.bullets.get(b.getId());
        if (existing == null) {
            this.bullets.put(b.getId(), b);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(b.getId(), b.getPos().x, b.getPos().y);
            }
        }
        return b.getId();
    }

    public boolean removeBullet(Bullet b) {
        final Bullet bullet = this.bullets.remove(b.getId());
        this.bulletHits.remove(b.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(b.getId());
        }
        return bullet != null;
    }

    public boolean removeBullet(Collection<Long> b) {
        for (Long l : b) {
            this.bullets.remove(l);
            this.bulletHits.remove(l);
            if (this.spatialGrid != null) {
                this.spatialGrid.remove(l);
            }
        }
        return true;
    }

    public long addPortal(Portal portal) {
        this.portals.put(portal.getId(), portal);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(portal.getId(), portal.getPos().x, portal.getPos().y);
        }
        return portal.getId();
    }

    public boolean removePortal(long portalId) {
        final Portal removed = this.portals.remove(portalId);
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(portalId);
        }
        return removed != null;
    }

    public boolean removePortal(Portal portal) {
        final Portal removed = this.portals.remove(portal.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(portal.getId());
        }
        return removed != null;
    }

    public long addPortalIfNotExists(Portal portal) {
        final Portal existing = this.portals.get(portal.getId());
        if (existing == null) {
            this.portals.put(portal.getId(), portal);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(portal.getId(), portal.getPos().x, portal.getPos().y);
            }
        }
        return portal.getId();
    }

    public long addEnemy(Enemy enemy) {
        this.enemies.put(enemy.getId(), enemy);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(enemy.getId(), enemy.getPos().x, enemy.getPos().y);
        }
        this.shortIdAllocator.getOrAssign(enemy.getId());
        return enemy.getId();
    }

    public long addEnemyIfNotExists(Enemy enemy) {
        final Enemy existing = this.enemies.get(enemy.getId());
        if (existing == null) {
            final EnemyModel model = GameDataManager.ENEMIES.get(enemy.getEnemyId());
            if (model != null) {
                enemy.setModel(model);
                if (enemy.getStats() == null) {
                    enemy.setStats(model.getStats().clone());
                }
                enemy.setChaseRange((int) model.getChaseRange());
                enemy.setAttackRange((int) model.getAttackRange());
            }
            this.enemies.put(enemy.getId(), enemy);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(enemy.getId(), enemy.getPos().x, enemy.getPos().y);
            }
        }
        return enemy.getId();
    }

    public Enemy getEnemy(long enemyId) {
        return this.enemies.get(enemyId);
    }

    public boolean removeEnemy(Enemy enemy) {
        final Enemy e = this.enemies.remove(enemy.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(enemy.getId());
        }
        this.shortIdAllocator.release(enemy.getId());
        return e != null;
    }

    public long addLootContainer(LootContainer lc) {
        long randomId = Realm.RANDOM.nextLong();
        lc.setLootContainerId(randomId);
        this.loot.put(randomId, lc);
        if (this.spatialGrid != null) {
            this.spatialGrid.insert(randomId, lc.getPos().x, lc.getPos().y);
        }
        return randomId;
    }

    public long addLootContainerIfNotExists(LootContainer lc) {
        if (!this.loot.containsKey(lc.getLootContainerId())) {
            this.loot.put(lc.getLootContainerId(), lc);
            if (this.spatialGrid != null) {
                this.spatialGrid.insert(lc.getLootContainerId(), lc.getPos().x, lc.getPos().y);
            }
        }
        return lc.getLootContainerId();
    }

    public boolean removeLootContainer(LootContainer lc) {
        final LootContainer lootContainer = this.loot.remove(lc.getLootContainerId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(lc.getLootContainerId());
        }
        return lootContainer != null;
    }

    public List<Chest> getChests() {
        final List<Chest> objs = new ArrayList<>();
        if (this.loot == null)
            return objs;
        for (final LootContainer lc : this.loot.values()) {
            if (lc instanceof Chest) {
                objs.add((Chest) lc);
            }
        }
        return objs;
    }

    /**
     * Updates the spatial grid positions for all moving entities.
     * Call once per tick from the server update loop.
     */
    public void updateSpatialGrid() {
        if (this.spatialGrid == null) return;
        for (final Player p : this.players.values()) {
            this.spatialGrid.update(p.getId(), p.getPos().x, p.getPos().y);
        }
        for (final Enemy e : this.enemies.values()) {
            this.spatialGrid.update(e.getId(), e.getPos().x, e.getPos().y);
        }
        for (final Bullet b : this.bullets.values()) {
            this.spatialGrid.update(b.getId(), b.getPos().x, b.getPos().y);
        }
    }

    /**
     * Returns players near a point. Iterates the players map directly —
     * the spatial grid holds enemies + bullets too, so at high enemy
     * density a grid query returns thousands of irrelevant candidates per
     * call. Player counts are bounded (~40), so the flat scan is strictly
     * cheaper than any grid filter here.
     */
    public Player[] getPlayersInRadiusFast(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            // /hide: hidden admins aren't enemy targets and don't take AOEs.
            if (p.isHiddenFromOthers()) continue;
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) {
                objs.add(p);
            }
        }
        return objs.toArray(new Player[0]);
    }

    /** Lightweight records used to sort candidates by distance before applying
     *  the per-LoadPacket cap. Plain classes (not records) for compatibility
     *  with the Java target this project compiles against. */
    private static final class EnemyDist {
        final Enemy enemy;
        final float distSq;
        EnemyDist(Enemy e, float d) { this.enemy = e; this.distSq = d; }
    }
    private static final class BulletDist {
        final Bullet bullet;
        final float distSq;
        BulletDist(Bullet b, float d) { this.bullet = b; this.distSq = d; }
    }

    /**
     * Grid-accelerated circular LoadPacket construction.
     *
     * Caps are intentionally generous so dense-enemy stress tests (500+
     * enemies in viewport) don't trigger flicker artifacts from arbitrary
     * truncation. When the cap IS hit, we keep the closest entities first
     * (sorted before truncation) so what's visible to the player is at
     * least deterministic rather than wobbling with HashSet iteration order.
     */
    private static final int MAX_BULLETS_PER_LOAD = 1000;
    private static final int MAX_ENEMIES_PER_LOAD = 500;

    /**
     * Legacy overload without soulbound filtering. Defaults to showing all loot.
     */
    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius) {
        return getLoadPacketCircularFast(center, radius, -1);
    }

    /**
     * Returns a LoadPacket containing all entities within the specified radius,
     * filtering loot containers based on soulbound visibility.
     * 
     * @param center The center position to query from
     * @param radius The query radius
     * @param requestingPlayerId The player ID requesting this packet; soulbound loot
     *        not belonging to this player will be filtered out. Use -1 to show all.
     */
    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius, long requestingPlayerId) {
        if (this.spatialGrid == null) {
            return getLoadPacketCircular(center, radius, requestingPlayerId);
        }
        final float radiusSq = radius * radius;
        // Bullets use a wider radius so projectiles fired by enemies beyond the
        // viewport edge are still sent to the client. Done as a SEPARATE query
        // so the bullet cap doesn't compete with the enemy cap.
        final float bulletRadius = radius * 2f;
        final float bulletRadiusSq = bulletRadius * bulletRadius;
        LoadPacket load = null;
        try {
            final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
            final List<Player> playersToLoadList = new ArrayList<>();
            final List<LootContainer> containersToLoad = new ArrayList<>();
            final List<Portal> portalsToLoad = new ArrayList<>();
            // Collect candidates with their squared distance so we can sort
            // before applying the cap. Without this, which N entities are
            // chosen flickers tick-to-tick (HashSet iteration), making the
            // server emit UnloadPackets for entities that are still alive
            // and producing the visible "enemies disappear/reappear" bug.
            final List<EnemyDist> enemyCandidates = new ArrayList<>();
            final List<BulletDist> bulletCandidatesInner = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                final long id = candidates.get(i);
                Player p = this.players.get(id);
                if (p != null) {
                    // /hide: hidden admins are filtered out of every other
                    // viewer's LoadPacket. Self always sees self.
                    if (p.isHiddenFromOthers() && p.getId() != requestingPlayerId) continue;
                    float dx = p.getPos().x - center.x;
                    float dy = p.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq) playersToLoadList.add(p);
                    continue;
                }
                Enemy e = this.enemies.get(id);
                if (e != null) {
                    float dx = e.getPos().x - center.x;
                    float dy = e.getPos().y - center.y;
                    final float distSq = dx * dx + dy * dy;
                    if (distSq <= radiusSq) enemyCandidates.add(new EnemyDist(e, distSq));
                    continue;
                }
                Bullet b = this.bullets.get(id);
                if (b != null) {
                    float dx = b.getPos().x - center.x;
                    float dy = b.getPos().y - center.y;
                    final float distSq = dx * dx + dy * dy;
                    if (distSq <= radiusSq) bulletCandidatesInner.add(new BulletDist(b, distSq));
                    continue;
                }
                Portal portal = this.portals.get(id);
                if (portal != null) {
                    float dx = portal.getPos().x - center.x;
                    float dy = portal.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq) portalsToLoad.add(portal);
                    continue;
                }
                LootContainer lc = this.loot.get(id);
                if (lc != null) {
                    float dx = lc.getPos().x - center.x;
                    float dy = lc.getPos().y - center.y;
                    // Check soulbound visibility: only include if public or belongs to requesting player
                    if (dx * dx + dy * dy <= radiusSq && lc.isVisibleToPlayer(requestingPlayerId)) {
                        containersToLoad.add(lc);
                    }
                }
            }

            // Sort by distance and truncate to the cap. Closest stays loaded.
            if (enemyCandidates.size() > MAX_ENEMIES_PER_LOAD) {
                enemyCandidates.sort((a, b1) -> Float.compare(a.distSq, b1.distSq));
            }
            final List<Enemy> enemiesToLoad = new ArrayList<>(
                    Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD));
            for (int i = 0, n = Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD); i < n; i++) {
                enemiesToLoad.add(enemyCandidates.get(i).enemy);
            }
            // Bullet cap: player-fired bullets are always loaded first so a
            // player's own ability/projectile is never culled by 1000+ enemy
            // bullets crowding the cap. Without this, under heavy load the
            // player sees enemies firing but their own attacks don't render.
            // Remaining cap slots go to enemy bullets sorted by distance.
            final List<Bullet> bulletsToLoad = new ArrayList<>(MAX_BULLETS_PER_LOAD);
            for (int i = 0; i < bulletCandidatesInner.size() && bulletsToLoad.size() < MAX_BULLETS_PER_LOAD; i++) {
                final Bullet b = bulletCandidatesInner.get(i).bullet;
                if (!b.isEnemy()) bulletsToLoad.add(b);
            }
            if (bulletsToLoad.size() < MAX_BULLETS_PER_LOAD) {
                if (bulletCandidatesInner.size() > MAX_BULLETS_PER_LOAD) {
                    bulletCandidatesInner.sort((a, b1) -> Float.compare(a.distSq, b1.distSq));
                }
                for (int i = 0; i < bulletCandidatesInner.size() && bulletsToLoad.size() < MAX_BULLETS_PER_LOAD; i++) {
                    final Bullet b = bulletCandidatesInner.get(i).bullet;
                    if (b.isEnemy()) bulletsToLoad.add(b);
                }
            }

            // Second pass: query the wider bullet radius for bullets only.
            // This catches projectiles fired by enemies just beyond the viewport
            // (e.g. enemies whose attack range exceeds the load radius). Same
            // player-bullet priority as the inner pass — player abilities in
            // the outer ring (e.g. long-range shots) are kept under cap pressure.
            final List<Long> bulletCandidates = this.spatialGrid.queryRadius(center.x, center.y, bulletRadius);
            // Pass 2a: player bullets in the outer ring
            for (int i = 0; i < bulletCandidates.size(); i++) {
                if (bulletsToLoad.size() >= MAX_BULLETS_PER_LOAD) break;
                final long id = bulletCandidates.get(i);
                Bullet b = this.bullets.get(id);
                if (b == null || b.isEnemy()) continue;
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                float dsq = dx * dx + dy * dy;
                if (dsq <= radiusSq) continue; // already added above
                if (dsq <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            // Pass 2b: enemy bullets in the outer ring (only if cap has slack)
            for (int i = 0; i < bulletCandidates.size(); i++) {
                if (bulletsToLoad.size() >= MAX_BULLETS_PER_LOAD) break;
                final long id = bulletCandidates.get(i);
                Bullet b = this.bullets.get(id);
                if (b == null || !b.isEnemy()) continue;
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                float dsq = dx * dx + dy * dy;
                if (dsq <= radiusSq) continue; // already added above
                if (dsq <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(center.x, center.y));
        } catch (Exception e) {
            Realm.log.error("Failed to get fast circular load Packet. Reason: {}", e.getMessage());
        }
        return load;
    }

    /**
     * Reset the per-tick NetObjectMovement cache. Called by RealmManagerServer
     * once per realm at the top of enqueueGameData() so subsequent
     * getGameObjectsAsPacketsCircularFast() calls (one per viewer) share
     * NetObjectMovement instances instead of each allocating a fresh copy.
     */
    public void clearTickMovementCache() {
        if (this.tickMovementCache != null) {
            this.tickMovementCache.clear();
        }
    }

    /** Reset the per-tick stripped-UpdatePacket cache. */
    public void clearTickStrippedUpdateCache() {
        if (this.tickStrippedUpdateCache != null) {
            this.tickStrippedUpdateCache.clear();
        }
    }

    /**
     * Get-or-build a stripped (no-inventory) UpdatePacket for this player,
     * cached for the duration of the current tick so all viewers share one
     * instance instead of each rebuilding from scratch.
     */
    public UpdatePacket getOrBuildStrippedUpdate(Player p) {
        if (p == null) return null;
        if (this.tickStrippedUpdateCache == null) {
            this.tickStrippedUpdateCache = new HashMap<>(64);
        }
        UpdatePacket u = this.tickStrippedUpdateCache.get(p.getId());
        if (u == null) {
            u = UpdatePacket.fromPlayerWithoutInventory(p);
            this.tickStrippedUpdateCache.put(p.getId(), u);
        }
        return u;
    }

    /** Get-or-build a NetObjectMovement for this entity for the current tick. */
    private NetObjectMovement getOrBuildMovement(GameObject obj) {
        if (this.tickMovementCache == null) {
            // Sized for typical nexus density; HashMap auto-grows if needed.
            this.tickMovementCache = new HashMap<>(64);
        }
        NetObjectMovement m = this.tickMovementCache.get(obj.getId());
        if (m == null) {
            m = new NetObjectMovement(obj);
            this.tickMovementCache.put(obj.getId(), m);
        }
        return m;
    }

    /**
     * Grid-accelerated ObjectMovePacket construction (players + enemies only).
     * Uses the per-tick movement cache so 40 viewers in nexus only allocate
     * ~50 NetObjectMovement instances per tick total (one per visible
     * entity), not 40×50 = 2000.
     */
    public ObjectMovePacket getGameObjectsAsPacketsCircularFast(Vector2f center, float radius) throws Exception {
        if (this.spatialGrid == null) {
            return getGameObjectsAsPacketsCircular(center, radius);
        }
        final float radiusSq = radius * radius;
        final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
        final List<NetObjectMovement> mvts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final long id = candidates.get(i);
            Player p = this.players.get(id);
            if (p != null) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) mvts.add(getOrBuildMovement(p));
                if (p.getTeleported()) p.setTeleported(false);
                continue;
            }
            Enemy e = this.enemies.get(id);
            if (e != null) {
                float dx = e.getPos().x - center.x;
                float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) mvts.add(getOrBuildMovement(e));
                if (e.getTeleported()) e.setTeleported(false);
                continue;
            }
            // Skip bullets — clients predict their positions locally using
            // initial velocity from LoadPacket. Saves enormous bandwidth.
        }
        if (mvts.isEmpty()) return null;
        return ObjectMovePacket.from(mvts.toArray(new NetObjectMovement[0]));
    }

    /**
     * Returns the spatial grid cell key for a world position.
     * Players in the same cell see approximately the same entities.
     */
    public long getSpatialCellKey(float x, float y) {
        if (this.spatialGrid == null) return 0;
        return this.spatialGrid.getCellKey(x, y);
    }

    public Rectangle[] getCollisionBoxesInBounds(Rectangle cam) {
        final List<Rectangle> colBoxes = new ArrayList<>();
        final GameObject[] go = this.getGameObjectsInBounds(cam);
        for (final GameObject g : go) {
            colBoxes.add(g.getBounds());
        }
        return colBoxes.toArray(new Rectangle[0]);
    }

    public Player[] getPlayersInBounds(Rectangle cam) {
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            if (p.getBounds().intersect(cam)) {
                objs.add(p);
            }
        }

        return objs.toArray(new Player[0]);
    }

    public Player[] getPlayersInRadius(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) {
                objs.add(p);
            }
        }
        return objs.toArray(new Player[0]);
    }

    public GameObject[] getGameObjectsInBounds(Rectangle cam) {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            if (p.getBounds().intersect(cam)) {
                objs.add(p);
            }
        }

        for (final Bullet b : this.bullets.values()) {
            if (b.getBounds().intersect(cam)) {
                objs.add(b);
            }
        }

        for (final Enemy e : this.enemies.values()) {
            if (e.getBounds().intersect(cam)) {
                objs.add(e);
            }
        }

        return objs.toArray(new GameObject[0]);
    }

    public GameObject[] getGameObjectsInRadius(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(p);
        }
        for (final Bullet b : this.bullets.values()) {
            float dx = b.getPos().x - center.x;
            float dy = b.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(b);
        }
        for (final Enemy e : this.enemies.values()) {
            float dx = e.getPos().x - center.x;
            float dy = e.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(e);
        }
        return objs.toArray(new GameObject[0]);
    }

    public ObjectMovePacket getGameObjectsAsPacketsCircular(Vector2f center, float radius) throws Exception {
        final float radiusSq = radius * radius;
        final GameObject[] gameObjects = this.getAllGameObjects();
        final List<GameObject> validObjects = new ArrayList<>();
        for (GameObject obj : gameObjects) {
            try {
                float dx = obj.getPos().x - center.x;
                float dy = obj.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) {
                    validObjects.add(obj);
                }
                if (obj.getTeleported()) {
                    obj.setTeleported(false);
                }
            } catch (Exception e) {
                Realm.log.error("Failed to create ObjectMove Packet. Reason: {}", e.getMessage());
            }
        }
        if (validObjects.size() > 0)
            return ObjectMovePacket.from(validObjects.toArray(new GameObject[0]));
        return null;
    }

    public GameObject[] getGameObjectss() {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            objs.add(p);
        }

        for (final Bullet b : this.bullets.values()) {
            objs.add(b);
        }

        for (final Enemy e : this.enemies.values()) {
            objs.add(e);
        }

        return objs.toArray(new GameObject[0]);
    }

    public GameObject[] getAllGameObjects() {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            objs.add(p);
        }

        for (final Bullet b : this.bullets.values()) {
            objs.add(b);
        }

        for (final Enemy e : this.enemies.values()) {
            objs.add(e);
        }

        return objs.toArray(new GameObject[0]);
    }

    /**
     * Returns only players and enemies for ObjectMovePacket.
     * Bullets are excluded because they follow deterministic trajectories
     * and the client simulates them locally from the initial LoadPacket data.
     */
    public GameObject[] getMovableGameObjects() {
        final List<GameObject> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            objs.add(p);
        }

        for (final Enemy e : this.enemies.values()) {
            objs.add(e);
        }

        return objs.toArray(new GameObject[0]);
    }

    public UpdatePacket getPlayerAsPacket(long playerId) {
        final Player p = this.players.get(playerId);
        UpdatePacket pack = null;
        try {
            pack = UpdatePacket.from(p);
        } catch (Exception e) {
            Realm.log.error("Failed to create update packet from Player. Reason: {}", e);
        }
        return pack;
    }
    
    public UpdatePacket getEnemyAsPacket(long enemyId) {
        final Enemy enemy = this.enemies.get(enemyId);
        UpdatePacket pack = null;
        try {
            pack = UpdatePacket.from(enemy);
        } catch (Exception e) {
            Realm.log.error("Failed to create update packet from Enemy. Reason: {}", e);
        }
        return pack;
    }


    public List<UpdatePacket> getPlayersAsPackets(Rectangle cam) {
        final List<UpdatePacket> playerUpdates = new ArrayList<>();
        for (final Player p : this.players.values()) {
            try {
                final UpdatePacket pack = UpdatePacket.from(p);
                playerUpdates.add(pack);
            } catch (Exception e) {
                Realm.log.error("Failed to create update packet from Player. Reason: {}", e);
            }
        }
        return playerUpdates;
    }

    public LoadPacket getLoadPacket(Rectangle cam) {
        LoadPacket load = null;
        try {
            final List<Player> playersToLoadList = new ArrayList<>();
            for (Player p : this.players.values()) {
                final boolean inViewport = cam.inside((int) p.getPos().x, (int) p.getPos().y);
                if (inViewport) {
                    playersToLoadList.add(p);
                }

            }
            final List<LootContainer> containersToLoad = new ArrayList<>();
            for (LootContainer c : this.loot.values()) {
                final boolean inViewport = cam.inside((int) c.getPos().x, (int) c.getPos().y);
                if (inViewport) {
                    containersToLoad.add(c);
                }
            }

            final List<Bullet> bulletsToLoad = new ArrayList<>();
            for (Bullet b : this.bullets.values()) {
                final boolean inViewport = cam.inside((int) b.getPos().x, (int) b.getPos().y);
                if (inViewport) {
                    bulletsToLoad.add(b);
                }
            }

            final List<Enemy> enemiesToLoad = new ArrayList<>();
            for (Enemy e : this.enemies.values()) {
                final boolean inViewport = cam.inside((int) e.getPos().x, (int) e.getPos().y);
                if (inViewport) {
                    enemiesToLoad.add(e);
                }
            }

            final List<Portal> portalsToLoad = new ArrayList<>();
            for (Portal p : this.portals.values()) {
                final boolean inViewport = cam.inside((int) p.getPos().x, (int) p.getPos().y);
                if (inViewport) {
                    portalsToLoad.add(p);
                }
            }

            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(cam.getPos().x + cam.getWidth() / 2f, cam.getPos().y + cam.getHeight() / 2f));
        } catch (Exception e) {
            Realm.log.error("Failed to get load Packet. Reason: {}", e.getMessage(), e);
        }
        return load;
    }

    /**
     * Legacy overload without soulbound filtering. Defaults to showing all loot.
     */
    public LoadPacket getLoadPacketCircular(Vector2f center, float radius) {
        return getLoadPacketCircular(center, radius, -1);
    }

    /**
     * Returns a LoadPacket containing all entities within the specified radius,
     * filtering loot containers based on soulbound visibility.
     * 
     * @param center The center position to query from
     * @param radius The query radius
     * @param requestingPlayerId The player ID requesting this packet; soulbound loot
     *        not belonging to this player will be filtered out. Use -1 to show all.
     */
    public LoadPacket getLoadPacketCircular(Vector2f center, float radius, long requestingPlayerId) {
        final float radiusSq = radius * radius;
        final float bulletRadiusSq = (radius * 2f) * (radius * 2f);
        LoadPacket load = null;
        try {
            final List<Player> playersToLoadList = new ArrayList<>();
            for (Player p : this.players.values()) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) playersToLoadList.add(p);
            }
            final List<LootContainer> containersToLoad = new ArrayList<>();
            for (LootContainer c : this.loot.values()) {
                float dx = c.getPos().x - center.x;
                float dy = c.getPos().y - center.y;
                // Check soulbound visibility: only include if public or belongs to requesting player
                if (dx * dx + dy * dy <= radiusSq && c.isVisibleToPlayer(requestingPlayerId)) {
                    containersToLoad.add(c);
                }
            }
            final List<Bullet> bulletsToLoad = new ArrayList<>();
            for (Bullet b : this.bullets.values()) {
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                if (dx * dx + dy * dy <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            final List<Enemy> enemiesToLoad = new ArrayList<>();
            for (Enemy e : this.enemies.values()) {
                float dx = e.getPos().x - center.x;
                float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) enemiesToLoad.add(e);
            }
            final List<Portal> portalsToLoad = new ArrayList<>();
            for (Portal p : this.portals.values()) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) portalsToLoad.add(p);
            }
            load = LoadPacket.from(playersToLoadList.toArray(new Player[0]),
                    containersToLoad.toArray(new LootContainer[0]), bulletsToLoad.toArray(new Bullet[0]),
                    enemiesToLoad.toArray(new Enemy[0]), portalsToLoad.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null) load.setDifficulty((byte) this.getZoneDifficulty(center.x, center.y));
        } catch (Exception e) {
            Realm.log.error("Failed to get circular load Packet. Reason: {}", e.getMessage());
        }
        return load;
    }

    public ObjectMovePacket getGameObjectsAsPackets(Rectangle cam) throws Exception {
        final GameObject[] gameObjects = this.getAllGameObjects();
        final List<GameObject> validObjects = new ArrayList<>();
        for (GameObject obj : gameObjects) {
            try {

                final boolean inViewport = cam.inside((int) obj.getPos().x, (int) obj.getPos().y);
                if (inViewport) {
                    validObjects.add(obj);
                }
                if (obj.getTeleported()) {
                    obj.setTeleported(false);
                }

            } catch (Exception e) {
                Realm.log.error("Failed to create ObjectMove Packet. Reason: {}", e.getMessage());
            }
        }
        if (validObjects.size() > 0)
            return ObjectMovePacket.from(validObjects.toArray(new GameObject[0]));
        return null;
    }
    
    public LootContainer[] getLootInBounds(Rectangle cam) {
        final List<LootContainer> objs = new ArrayList<>();
        for (final LootContainer lc : this.loot.values()) {
            if (cam.inside((int) lc.getPos().x, (int) lc.getPos().y)) {
                objs.add(lc);
            }
        }
        return objs.toArray(new LootContainer[0]);
    }

    public void spawnRandomEnemies(int mapId) {
        if (this.enemies == null) {
            this.enemies = new ConcurrentHashMap<>();
        }

        final MapModel mapModel = GameDataManager.MAPS.get(mapId);
        if (mapModel == null || mapModel.getTerrainId() < 0) {
            log.info("MapId {} has no terrain (terrainId={}), skipping enemy spawning", mapId,
                    mapModel != null ? mapModel.getTerrainId() : "null");
            return;
        }

        TerrainGenerationParameters params = GameDataManager.TERRAINS.get(mapModel.getTerrainId());
        if (params == null) {
            log.warn("No Terrain generation params found for MapId {}, using default values", mapId);
            params = GameDataManager.TERRAINS.get(GameDataManager.MAPS.get(4).getTerrainId());
        }

        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();

        // Pre-build enemy lists per zone (or single global list for legacy)
        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (EnemyGroup group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }

        // Legacy fallback: all enemies from group 0
        final List<EnemyModel> defaultEnemies = enemiesByGroup.getOrDefault(0,
                new ArrayList<>(enemiesByGroup.values().iterator().next()));

        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapHeight = this.tileManager.getMapLayers().get(0).getHeight();
        final int mapWidth = this.tileManager.getMapLayers().get(0).getWidth();

        // Use per-terrain enemyDensity if set, otherwise fall back to legacy thresholds.
        // enemyDensity is a 0.0-1.0 probability that each eligible tile spawns an enemy.
        final float density;
        if (params.getEnemyDensity() > 0f) {
            density = params.getEnemyDensity();
        } else {
            // Legacy fallback: ~0.8% for overworld, ~0.4% for dungeons (smaller maps)
            density = hasZones ? 0.01375f : 0.005f;
        }

        // Spawn caps for rare/unique enemies
        final Map<Integer, Integer> spawnCaps = new HashMap<>();
        final Map<Integer, Integer> spawnCounts = new HashMap<>();
        spawnCaps.put(13, 3);  // The Man: max 3 per realm (summit only)

        for (int i = 1; i < mapHeight; i++) {
            for (int j = 1; j < mapWidth; j++) {
                if (Realm.RANDOM.nextFloat() >= density) continue;

                final Vector2f spawnPos = new Vector2f(j * tileSize, i * tileSize);
                if (this.tileManager.isVoidTile(spawnPos, 0, 0)) {
                    continue;
                }

                // Select enemy list based on zone
                List<EnemyModel> spawnList = defaultEnemies;
                float diff = this.getDifficulty();

                if (hasZones) {
                    OverworldZone zone = this.tileManager.getZoneForPosition(spawnPos.x, spawnPos.y);
                    if (zone != null) {
                        spawnList = enemiesByGroup.getOrDefault(zone.getEnemyGroupOrdinal(), defaultEnemies);
                        diff = Math.max(1.0f, zone.getDifficulty());
                    }
                }

                if (spawnList.isEmpty()) continue;
                final EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));

                // Hitbox collision check using the enemy's actual size
                if (this.tileManager.collidesAtPosition(spawnPos, toSpawn.getSize())) {
                    continue;
                }

                // Enforce spawn caps for rare enemies (e.g., The Man = max 2)
                if (spawnCaps.containsKey(toSpawn.getEnemyId())) {
                    int current = spawnCounts.getOrDefault(toSpawn.getEnemyId(), 0);
                    if (current >= spawnCaps.get(toSpawn.getEnemyId())) continue;
                    spawnCounts.merge(toSpawn.getEnemyId(), 1, Integer::sum);
                }

                final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                        spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
                enemy.setDifficulty(diff);
                enemy.setHealth((int) (enemy.getHealth() * diff));
                enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
                enemy.setPos(spawnPos);
                this.addEnemy(enemy);
            }
        }

        this.spawnStaticEnemies(mapId);
        this.initialEnemyCount = this.enemies.size();
    }

    /**
     * Respawn enemies in the overworld realm to replenish killed mobs.
     * Only runs on terrain-based realms with zones.
     * Spawns a batch of enemies in random positions away from players.
     */
    public void respawnEnemies(int batchSize) {
        final TerrainGenerationParameters params = this.tileManager.getTerrainParams();
        if (params == null) return;
        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();
        if (!hasZones) return;

        // Only respawn if enemy count has dropped below 75% of the initial population
        final int threshold = (int) (this.initialEnemyCount * 0.75);
        if (this.enemies.size() >= threshold) return;

        // Cap batch so we don't overshoot the initial count
        batchSize = Math.min(batchSize, this.initialEnemyCount - this.enemies.size());
        if (batchSize <= 0) return;

        // Pre-build enemy lists per zone
        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (EnemyGroup group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }
        final List<EnemyModel> defaultEnemies = enemiesByGroup.getOrDefault(0, new ArrayList<>());
        if (defaultEnemies.isEmpty() && enemiesByGroup.isEmpty()) return;

        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapHeight = this.tileManager.getMapLayers().get(0).getHeight();
        final int mapWidth = this.tileManager.getMapLayers().get(0).getWidth();

        // Don't spawn within player viewport radius (10 tiles = 320px)
        final float viewportRadius = 10f * GlobalConstants.BASE_TILE_SIZE;
        final float minPlayerDistSq = viewportRadius * viewportRadius;
        final List<Vector2f> playerPositions = new ArrayList<>();
        for (Player p : this.players.values()) {
            playerPositions.add(p.getPos());
        }

        int spawned = 0;
        int attempts = 0;
        final int maxAttempts = batchSize * 10;

        while (spawned < batchSize && attempts < maxAttempts) {
            attempts++;
            final int col = 1 + Realm.RANDOM.nextInt(mapWidth - 2);
            final int row = 1 + Realm.RANDOM.nextInt(mapHeight - 2);
            final Vector2f spawnPos = new Vector2f(col * tileSize, row * tileSize);

            if (this.tileManager.isVoidTile(spawnPos, 0, 0)) continue;

            // Don't spawn near players
            boolean nearPlayer = false;
            for (Vector2f pp : playerPositions) {
                float dx = spawnPos.x - pp.x, dy = spawnPos.y - pp.y;
                if (dx * dx + dy * dy < minPlayerDistSq) {
                    nearPlayer = true;
                    break;
                }
            }
            if (nearPlayer) continue;

            // Select enemy list based on zone
            List<EnemyModel> spawnList = defaultEnemies;
            float diff = this.getDifficulty();
            OverworldZone zone = this.tileManager.getZoneForPosition(spawnPos.x, spawnPos.y);
            if (zone != null) {
                spawnList = enemiesByGroup.getOrDefault(zone.getEnemyGroupOrdinal(), defaultEnemies);
                diff = Math.max(1.0f, zone.getDifficulty());
            }
            if (spawnList.isEmpty()) continue;

            final EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));
            if (this.tileManager.collidesAtPosition(spawnPos, toSpawn.getSize())) continue;

            final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                    spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
            enemy.setPos(spawnPos);
            this.addEnemy(enemy);
            spawned++;
        }

        if (spawned > 0) {
            log.info("[REALM] Respawned {} enemies in overworld (total: {})", spawned, this.enemies.size());
        }
    }

    /**
     * Register a poison DoT on an enemy in this realm. Poisons stack.
     */
    public void registerPoisonDot(long enemyId, int totalDamage, long duration, long sourcePlayerId) {
        this.activePoisonDots.add(new PoisonDotState(enemyId, totalDamage, duration, sourcePlayerId));
    }

    /**
     * Remove all poison DoTs sourced by a specific player (called on disconnect).
     */
    public void removePlayerPoisonDots(long playerId) {
        this.activePoisonDots.removeIf(dot -> dot.sourcePlayerId == playerId);
    }

    /**
     * Process all active poison DoTs for this realm. Called every server tick.
     * @param mgr the server manager, used for enemyDeath and broadcastTextEffect callbacks
     */
    public void processPoisonDots(RealmManagerServer mgr) {
        if (this.activePoisonDots.isEmpty()) return;
        final long now = Instant.now().toEpochMilli();
        final Iterator<PoisonDotState> it = this.activePoisonDots.iterator();
        while (it.hasNext()) {
            final PoisonDotState dot = it.next();
            final Enemy enemy = this.getEnemy(dot.enemyId);
            if (enemy == null || enemy.getDeath()) { it.remove(); continue; }
            if (dot.isExpired()) { it.remove(); continue; }

            if (now - dot.lastTickTime < POISON_TICK_INTERVAL_MS) continue;
            dot.lastTickTime = now;

            int totalTicks = (int) (dot.duration / POISON_TICK_INTERVAL_MS);
            int tickDamage = Math.max(1, dot.totalDamage / Math.max(1, totalTicks));

            if (dot.damageApplied + tickDamage > dot.totalDamage) {
                tickDamage = dot.totalDamage - dot.damageApplied;
            }
            if (tickDamage <= 0) continue;

            dot.damageApplied += tickDamage;
            enemy.setHealth(enemy.getHealth() - tickDamage);
            mgr.broadcastTextEffect(EntityType.ENEMY, enemy,
                    TextEffect.DAMAGE, "-" + tickDamage);

            if (enemy.getDeath()) {
                mgr.enemyDeath(this, enemy);
                it.remove();
            }
        }
    }

    /**
     * Register a pending poison throw. The landing effect will be applied after the delay
     * elapses, checked each tick — no threads are blocked.
     */
    public void registerPoisonThrow(long delayMs, long sourcePlayerId, float landX, float landY,
                                     float radius, int totalDamage, long poisonDuration) {
        registerPoisonThrow(delayMs, sourcePlayerId, landX, landY, radius, totalDamage, poisonDuration, (byte) 0);
    }

    public void registerPoisonThrow(long delayMs, long sourcePlayerId, float landX, float landY,
                                     float radius, int totalDamage, long poisonDuration, byte tier) {
        this.pendingPoisonThrows.add(new PoisonThrowState(delayMs, sourcePlayerId, landX, landY,
                radius, totalDamage, poisonDuration, tier));
    }

    public void registerTrap(long throwDelayMs, long sourcePlayerId, float x, float y,
                             float triggerRadius, short effectId, long effectDuration, int damage, long lifetimeMs) {
        registerTrap(throwDelayMs, sourcePlayerId, x, y, triggerRadius, effectId, effectDuration, damage, lifetimeMs, (byte) 0);
    }

    public void registerTrap(long throwDelayMs, long sourcePlayerId, float x, float y,
                             float triggerRadius, short effectId, long effectDuration, int damage,
                             long lifetimeMs, byte tier) {
        this.activeTraps.add(new TrapState(throwDelayMs, sourcePlayerId, x, y,
                triggerRadius, effectId, effectDuration, damage, lifetimeMs, tier));
    }

    public void processTraps(RealmManagerServer mgr) {
        if (this.activeTraps.isEmpty()) return;
        final Iterator<TrapState> it = this.activeTraps.iterator();
        while (it.hasNext()) {
            final TrapState trap = it.next();
            if (trap.isExpired()) { it.remove(); continue; }
            if (!trap.hasLanded()) continue;
            if (!trap.armed) {
                trap.armed = true;
                // Broadcast armed trap visual ONLY to players within sight
                // (10 tile radius — same as TextEffectPacket convention).
                // Previously sent to every player in the realm regardless
                // of whether the trap was on-screen for them.
                final float armSightR = 10 * GlobalConstants.BASE_TILE_SIZE;
                final float armSightSq = armSightR * armSightR;
                final CreateEffectPacket armPkt =
                        CreateEffectPacket.aoeEffect(
                            (short) 7, trap.x, trap.y, trap.triggerRadius,
                            (short) (trap.expireTime - Instant.now().toEpochMilli()),
                            trap.tier);
                for (final Player p : this.players.values()) {
                    if (p.isHeadless()) continue;
                    float pdx = p.getPos().x - trap.x;
                    float pdy = p.getPos().y - trap.y;
                    if (pdx * pdx + pdy * pdy <= armSightSq) {
                        mgr.enqueueServerPacket(p, armPkt);
                    }
                }
            }
            // Don't allow the trap to trigger until its arm window has
            // elapsed. Without this, an enemy already standing on the
            // landing spot would arm + trigger the trap in the same tick
            // and the placed-trap visual would never be visible — players
            // saw enemies vanish without ever seeing a snare.
            if (!trap.isArmed()) continue;
            boolean triggered = false;
            final float triggerSq = trap.triggerRadius * trap.triggerRadius;
            for (final Enemy enemy : this.enemies.values()) {
                if (enemy.getDeath()) continue;
                float ecx = enemy.getPos().x + enemy.getSize() / 2f;
                float ecy = enemy.getPos().y + enemy.getSize() / 2f;
                float dx = ecx - trap.x; float dy = ecy - trap.y;
                if (dx * dx + dy * dy <= triggerSq) { triggered = true; break; }
            }
            if (triggered) {
                float blastRadius = trap.triggerRadius + 16.0f;
                float blastSq = blastRadius * blastRadius;
                // Broadcast trigger visual ONLY to nearby (in-sight) players.
                final float trigSightR = 10 * GlobalConstants.BASE_TILE_SIZE;
                final float trigSightSq = trigSightR * trigSightR;
                final CreateEffectPacket trigPkt =
                        CreateEffectPacket.aoeEffect(
                            (short) 8, trap.x, trap.y, blastRadius, (short) 500, trap.tier);
                for (final Player p : this.players.values()) {
                    if (p.isHeadless()) continue;
                    float pdx = p.getPos().x - trap.x;
                    float pdy = p.getPos().y - trap.y;
                    if (pdx * pdx + pdy * pdy <= trigSightSq) {
                        mgr.enqueueServerPacket(p, trigPkt);
                    }
                }
                final StatusEffectType effectType =
                        StatusEffectType.valueOf(trap.effectId);
                for (final Enemy enemy : this.enemies.values()) {
                    if (enemy.getDeath()) continue;
                    float ecx = enemy.getPos().x + enemy.getSize() / 2f;
                    float ecy = enemy.getPos().y + enemy.getSize() / 2f;
                    float dx = ecx - trap.x; float dy = ecy - trap.y;
                    if (dx * dx + dy * dy <= blastSq) {
                        if (effectType != null) {
                            enemy.addEffect(effectType, trap.effectDuration);
                            mgr.broadcastTextEffect(this, EntityType.ENEMY, enemy,
                                    TextEffect.PLAYER_INFO, "SLOWED");
                        }
                        if (trap.damage > 0) {
                            enemy.setHealth(enemy.getHealth() - trap.damage);
                            mgr.broadcastTextEffect(this, EntityType.ENEMY, enemy,
                                    TextEffect.DAMAGE, "-" + trap.damage);
                            if (enemy.getDeath()) {
                                mgr.enemyDeath(this, enemy);
                            }
                        }
                    }
                }
                it.remove();
            }
        }
    }

    public void removePlayerTraps(long playerId) {
        this.activeTraps.removeIf(t -> t.sourcePlayerId == playerId);
    }

    /**
     * Process pending poison throws. When a throw's travel time has elapsed, apply the
     * splash AoE and poison DoT to enemies in range. Called every server tick.
     */
    public void processPoisonThrows(RealmManagerServer mgr) {
        if (this.pendingPoisonThrows.isEmpty()) return;
        final Iterator<PoisonThrowState> it = this.pendingPoisonThrows.iterator();
        while (it.hasNext()) {
            final PoisonThrowState t = it.next();
            if (!t.hasLanded()) continue;
            it.remove();

            // Broadcast splash AoE on landing — scope to THIS realm only.
            // The single-arg enqueueServerPacket() pushes to a global queue
            // that fans out to every connected client across every realm,
            // which was bloating CreateEffectPacket bandwidth on busy
            // servers. enqueueServerPacketToRealm restricts to nearby
            // players who can actually see the effect.
            mgr.enqueueServerPacketToRealm(this,
                    CreateEffectPacket.aoeEffect(
                        CreateEffectPacket.EFFECT_POISON_SPLASH,
                        t.landX, t.landY, t.radius, (short) 1500, t.tier));

            // Apply poison to enemies in radius
            final float radiusSq = t.radius * t.radius;
            for (final Enemy enemy : this.enemies.values()) {
                if (enemy.getDeath()) continue;
                if (enemy.hasEffect(StatusEffectType.STASIS)) continue;
                float dx = enemy.getPos().x - t.landX;
                float dy = enemy.getPos().y - t.landY;
                if (dx * dx + dy * dy <= radiusSq) {
                    enemy.addEffect(StatusEffectType.POISONED, t.poisonDuration);
                    this.registerPoisonDot(enemy.getId(), t.totalDamage, t.poisonDuration, t.sourcePlayerId);
                    mgr.broadcastTextEffect(EntityType.ENEMY, enemy,
                            TextEffect.DAMAGE, "POISONED");
                }
            }
        }
    }

    /**
     * Remove pending poison throws from a disconnecting player.
     */
    public void removePlayerPoisonThrows(long playerId) {
        this.pendingPoisonThrows.removeIf(t -> t.sourcePlayerId == playerId);
    }

    /**
     * Return a proxy Player positioned at the closest active decoy if it is
     * nearer than {@code currentBestDist}. Used by enemy targeting so decoys
     * draw aggro the same way real players do.
     */
    public Player getClosestDecoyTarget(final Vector2f pos, float currentBestDist) {
        Player best = null;
        for (final DecoyState d : this.activeDecoys) {
            final Enemy decoy = this.enemies.get(d.enemyId);
            if (decoy == null) continue;
            final float dist = decoy.getPos().distanceTo(pos);
            if (dist < currentBestDist) {
                currentBestDist = dist;
                best = new Player(d.enemyId, decoy.getPos().clone(),
                        decoy.getSize(), CharacterClass.TRICKSTER);
            }
        }
        return best;
    }

    /**
     * Register a decoy entity. The decoy walks in the given direction until it
     * covers maxTravelDist pixels, then stands still until durationMs expires.
     */
    public void registerDecoy(long enemyId, long sourcePlayerId, float originX, float originY,
                               float dx, float dy, float maxTravelDist, long durationMs) {
        this.activeDecoys.add(new DecoyState(enemyId, sourcePlayerId, originX, originY,
                dx, dy, maxTravelDist, durationMs));
    }

    /**
     * Process active decoys: move them each tick, stop after travel distance,
     * remove after duration expires. Called every server tick.
     */
    public void processDecoys(RealmManagerServer mgr) {
        if (this.activeDecoys.isEmpty()) return;
        final Iterator<DecoyState> it = this.activeDecoys.iterator();
        while (it.hasNext()) {
            final DecoyState d = it.next();
            final Enemy decoy = this.enemies.get(d.enemyId);

            // Decoy was killed or realm cleaned up
            if (decoy == null || decoy.getDeath()) {
                it.remove();
                continue;
            }

            // Duration expired — remove decoy
            if (d.isExpired()) {
                it.remove();
                this.expiredEnemies.add(d.enemyId);
                this.removeEnemy(decoy);
                continue;
            }

            // Move decoy if it hasn't reached travel distance
            if (!d.stopped) {
                float traveled_x = decoy.getPos().x - d.originX;
                float traveled_y = decoy.getPos().y - d.originY;
                if (traveled_x * traveled_x + traveled_y * traveled_y >= d.maxTravelDistSq) {
                    d.stopped = true;
                    // Stop movement and clear direction flags so walk animation stops
                    decoy.setDx(0);
                    decoy.setDy(0);
                    decoy.setUp(false);
                    decoy.setDown(false);
                    decoy.setLeft(false);
                    decoy.setRight(false);
                } else {
                    decoy.getPos().x += d.dx;
                    decoy.getPos().y += d.dy;
                }
            }
        }
    }

    /**
     * Remove decoys spawned by a disconnecting player.
     */
    public void removePlayerDecoys(long playerId) {
        final Iterator<DecoyState> it = this.activeDecoys.iterator();
        while (it.hasNext()) {
            final DecoyState d = it.next();
            if (d.sourcePlayerId == playerId) {
                final Enemy decoy = this.enemies.get(d.enemyId);
                if (decoy != null) {
                    this.expiredEnemies.add(d.enemyId);
                    this.removeEnemy(decoy);
                }
                it.remove();
            }
        }
    }

    /**
     * Called automatically when a realm is added, regardless of whether a decorator exists.
     */
    public void spawnStaticEnemies(int mapId) {
        final MapModel mapModel = GameDataManager.MAPS.get(mapId);
        if (mapModel == null || mapModel.getStaticSpawns() == null) return;
        for (final StaticSpawn ss : mapModel.getStaticSpawns()) {
            final EnemyModel model = GameDataManager.ENEMIES.get(ss.getEnemyId());
            if (model == null) {
                Realm.log.warn("Static spawn references unknown enemyId={}, skipping", ss.getEnemyId());
                continue;
            }
            Vector2f pos = new Vector2f(ss.getX(), ss.getY());
            // Validate spawn position against collision tiles using hitbox check
            if (this.tileManager != null && this.tileManager.collidesAtPosition(pos, model.getSize())) {
                Realm.log.warn("Static spawn at ({}, {}) collides with tiles, finding safe position", ss.getX(), ss.getY());
                pos = this.tileManager.getSafePosition();
            }
            final Enemy enemy = GameObjectUtils.getEnemyFromId(ss.getEnemyId(), pos);
            float diff = this.getZoneDifficulty(pos.x, pos.y);
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            this.addEnemy(enemy);
            Realm.log.info("Static spawn: {} at ({}, {}) in realm mapId={}", model.getName(), pos.x, pos.y, mapId);
        }
    }

    /**
     * Place set piece structures on the terrain (ruins, graveyards, watchtowers, etc.)
     * Each set piece has a base floor tile and a collision layout that stamps tiles onto the map.
     * Placement uses collision avoidance to prevent overlapping.
     */
    public void placeSetPieces(TerrainGenerationParameters params) {
        if (params.getSetPieces() == null || params.getSetPieces().isEmpty()) return;
        final boolean hasZones = params.getZones() != null && !params.getZones().isEmpty();
        final int tileSize = this.tileManager.getMapLayers().get(0).getTileSize();
        final int mapW = this.tileManager.getMapLayers().get(0).getWidth();
        final int mapH = this.tileManager.getMapLayers().get(0).getHeight();
        final Set<Long> occupied = new HashSet<>();

        Realm.log.info("[SET_PIECES] Map {}x{}, tileSize={}, hasZones={}, {} set piece types",
            mapW, mapH, tileSize, hasZones, params.getSetPieces().size());

        for (SetPiece sp : params.getSetPieces()) {
            // Resolve the setpiece template by ID
            final SetPieceModel model = GameDataManager.SETPIECES != null
                ? GameDataManager.SETPIECES.get(sp.getSetPieceId()) : null;
            if (model == null) {
                Realm.log.warn("[SET_PIECES] SetPieceModel not found for setPieceId={}", sp.getSetPieceId());
                continue;
            }

            int count = sp.getMinCount() + Realm.RANDOM.nextInt(Math.max(1, sp.getMaxCount() - sp.getMinCount() + 1));
            int placed = 0;
            int zoneRejects = 0, collRejects = 0;

            for (int attempt = 0; attempt < count * 100 && placed < count; attempt++) {
                int px = 4 + Realm.RANDOM.nextInt(Math.max(1, mapW - model.getWidth() - 8));
                int py = 4 + Realm.RANDOM.nextInt(Math.max(1, mapH - model.getHeight() - 8));

                // Zone check
                if (hasZones && sp.getAllowedZones() != null) {
                    Vector2f worldPos = new Vector2f(px * tileSize, py * tileSize);
                    OverworldZone zone = this.tileManager.getZoneForPosition(worldPos.x, worldPos.y);
                    if (zone == null || !sp.getAllowedZones().contains(zone.getZoneId())) {
                        zoneRejects++;
                        continue;
                    }
                }

                boolean fits = true;
                for (int dy = 0; dy < model.getHeight() && fits; dy++) {
                    for (int dx = 0; dx < model.getWidth() && fits; dx++) {
                        long key = ((long)(py + dy) << 32) | (px + dx);
                        if (occupied.contains(key)) { fits = false; collRejects++; }
                    }
                }
                Vector2f center = new Vector2f(px * tileSize + tileSize, py * tileSize + tileSize);
                if (this.tileManager.isVoidTile(center, 0, 0)) { fits = false; collRejects++; }
                if (!fits) continue;

                // Stamp the setpiece
                stampSetPiece(model, px, py, occupied);
                placed++;
            }
            Realm.log.info("[SET_PIECES] '{}': placed {}/{}, zoneRejects={}, collRejects={}",
                model.getName(), placed, count, zoneRejects, collRejects);
        }
    }

    /**
     * Stamp a SetPieceModel onto the map at the given tile coordinates.
     * Writes every layer present in the setpiece's {@code data} map. Layer
     * keys are numeric strings matching the underlying TileManager layer
     * indices ("0" = base, "1" = collision, etc.). Tile ID 0 = transparent
     * (skip — leaves the existing terrain in place).
     * Optionally tracks occupied tiles in the provided set (may be null).
     */
    public void stampSetPiece(SetPieceModel model, int px, int py,
                               Set<Long> occupied) {
        if (model.getData() == null) return;
        for (int dy = 0; dy < model.getHeight(); dy++) {
            for (int dx = 0; dx < model.getWidth(); dx++) {
                int tx = px + dx, ty = py + dy;
                if (occupied != null) {
                    occupied.add(((long) ty << 32) | tx);
                }
                for (var layerEntry : model.getData().entrySet()) {
                    final int layerIdx;
                    try { layerIdx = Integer.parseInt(layerEntry.getKey()); }
                    catch (NumberFormatException nfe) { continue; }
                    if (layerIdx < 0 || layerIdx >= this.tileManager.getMapLayers().size()) continue;
                    int[][] layer = layerEntry.getValue();
                    if (layer == null || dy >= layer.length || dx >= layer[dy].length) continue;
                    int tileId = layer[dy][dx];
                    if (tileId <= 0) continue;
                    try {
                        TileData data = GameDataManager.TILES.get(tileId) != null
                            ? GameDataManager.TILES.get(tileId).getData() : null;
                        this.tileManager.getMapLayers().get(layerIdx).setTileAt(ty, tx, (short) tileId, data);
                    } catch (Exception e) { /* skip */ }
                }
            }
        }
    }

    /**
     * Save the existing tiles at a location (both layers) so they can be restored later.
     * Returns [savedBase[h][w], savedCollision[h][w]].
     */
    public int[][][] saveTerrainAt(int px, int py, int width, int height) {
        int[][] savedBase = new int[height][width];
        int[][] savedColl = new int[height][width];
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                int tx = px + dx, ty = py + dy;
                try {
                    Tile baseTile = this.tileManager.getMapLayers().get(0).getBlocks()[ty][tx];
                    savedBase[dy][dx] = baseTile != null ? baseTile.getTileId() : 0;
                    Tile collTile = this.tileManager.getMapLayers().get(1).getBlocks()[ty][tx];
                    savedColl[dy][dx] = collTile != null ? collTile.getTileId() : 0;
                } catch (Exception e) {
                    savedBase[dy][dx] = 0;
                    savedColl[dy][dx] = 0;
                }
            }
        }
        return new int[][][] { savedBase, savedColl };
    }

    /**
     * Restore previously saved terrain tiles at a location.
     */
    public void restoreTerrainAt(int px, int py, int[][] savedBase, int[][] savedColl) {
        for (int dy = 0; dy < savedBase.length; dy++) {
            for (int dx = 0; dx < savedBase[dy].length; dx++) {
                int tx = px + dx, ty = py + dy;
                try {
                    int baseTileId = savedBase[dy][dx];
                    TileData baseData = baseTileId > 0 && GameDataManager.TILES.get(baseTileId) != null
                        ? GameDataManager.TILES.get(baseTileId).getData() : null;
                    this.tileManager.getMapLayers().get(0).setTileAt(ty, tx, (short) baseTileId, baseData);

                    int collTileId = savedColl[dy][dx];
                    TileData collData = collTileId > 0 && GameDataManager.TILES.get(collTileId) != null
                        ? GameDataManager.TILES.get(collTileId).getData() : null;
                    this.tileManager.getMapLayers().get(1).setTileAt(ty, tx, (short) collTileId, collData);
                } catch (Exception e) { /* skip */ }
            }
        }
    }

    public void spawnRandomEnemy() {
        final Vector2f spawnPos = this.tileManager.getSafePosition();

        final List<EnemyModel> enemyToSpawn = new ArrayList<>();
        GameDataManager.ENEMIES.values().forEach(enemy -> {
            enemyToSpawn.add(enemy);
        });
        final EnemyModel toSpawn = enemyToSpawn.get(Realm.RANDOM.nextInt(enemyToSpawn.size()));

        final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(), spawnPos, toSpawn.getSize(),
                toSpawn.getAttackId());

        final float diff = this.getZoneDifficulty(spawnPos.x, spawnPos.y);
        enemy.setDifficulty(diff);
        enemy.setHealth((int) (enemy.getHealth() * diff));
        enemy.setPos(spawnPos);
        this.addEnemy(enemy);
    }

    /**
     * Resolves the base difficulty for this realm from terrain or map data.
     * Resolution order: terrain difficulty > map difficulty > dungeon-graph > default 1.0
     * Note: for zone-based terrains, use getZoneDifficulty() instead for positional resolution.
     */
    public float getDifficulty() {
        // Try terrain-level difficulty
        MapModel map = GameDataManager.MAPS.get(this.mapId);
        if (map != null && map.getTerrainId() >= 0) {
            TerrainGenerationParameters terrain = GameDataManager.TERRAINS.get(map.getTerrainId());
            if (terrain != null && terrain.getDifficulty() > 0f) {
                return terrain.getDifficulty();
            }
        }
        // Try map-level difficulty (for static maps)
        if (map != null && map.getDifficulty() > 0f) {
            return map.getDifficulty();
        }
        // Fallback to dungeon graph node difficulty
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return Math.max(1.0f, node.getDifficulty());
        }
        return 1.0f;
    }

    /**
     * Resolves difficulty for a specific position, checking zone first.
     * For zone-based terrains, returns zone difficulty; otherwise falls back to getDifficulty().
     */
    public float getZoneDifficulty(float x, float y) {
        if (this.tileManager != null) {
            OverworldZone zone = this.tileManager.getZoneForPosition(x, y);
            if (zone != null) {
                return Math.max(1.0f, zone.getDifficulty());
            }
        }
        return this.getDifficulty();
    }

    private Runnable getStatsThread() {
        final Runnable statsThread = () -> {
            while (!this.shutdown) {
                final double heapSize = Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0;
                final String nodeName = (this.nodeId != null) ? this.nodeId : "legacy";
                Realm.log.info("--- Realm: {} | Node: {} | MapId: {} | Difficulty: {} ---", this.getRealmId(), nodeName, this.getMapId(), this.getDifficulty());
                Realm.log.info("Enemies: {}", this.enemies.size());
                Realm.log.info("Players: {}", this.players.size());
                Realm.log.info("Loot: {}", this.loot.size());
                Realm.log.info("Bullets: {}", this.bullets.size());
                Realm.log.info("BulletHits: {}", this.bulletHits.size());
                Realm.log.info("Portals: {}", this.portals.size());
                Realm.log.info("Heap Mem: {}", heapSize);

                try {
                    Thread.sleep(10000);
                } catch (Exception e) {

                }
            }
            log.info("Realm {} destroyed", this.getRealmId());
        };
        return statsThread;
    }

    private void acquirePlayerLock() {
        this.playerLock.lock();
    }

    private void releasePlayerLock() {
        this.playerLock.unlock();
    }
}
