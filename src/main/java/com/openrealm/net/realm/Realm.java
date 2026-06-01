package com.openrealm.net.realm;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import com.openrealm.game.entity.item.PotionStorage;
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
import com.openrealm.net.server.PvpEffectsManager;
import com.openrealm.net.server.ServerGameLogic;
import com.openrealm.net.server.VisibilityHelper;
import com.openrealm.util.GameObjectUtils;
import com.openrealm.util.WorkerThread;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@AllArgsConstructor
@Slf4j
public class Realm {
    public static final transient SecureRandom RANDOM = new SecureRandom();
    public static final int VAULT_MAP_ID = 30;
    private static final long POISON_TICK_INTERVAL_MS = 200;
    // Cap is hit only under stress; we sort by distance first so truncation
    // is deterministic and doesn't flicker with HashSet iteration order.
    private static final int MAX_BULLETS_PER_LOAD = 1000;
    private static final int MAX_ENEMIES_PER_LOAD = 500;
    private long realmId;
    private int mapId;
    private String nodeId;
    // realmId of the overworld/nexus a non-shared dungeon was entered from. 0 = none.
    private long sourceRealmId;
    // Designated dungeon boss enemyId — drops the exit portal even with no loot table.
    private int dungeonBossEnemyId;
    // Mirrored from MapModel.isPvp at loadMap. Gates friendly-fire checks + no-permadeath in playerDeath.
    private boolean isPvp;
    private Map<Long, Player> players;
    private Map<Long, Bullet> bullets;
    private Map<Long, List<Long>> bulletHits;
    private Map<Long, Enemy> enemies;
    private int initialEnemyCount;
    private Map<Long, LootContainer> loot;
    private Map<Long, Portal> portals;

    private List<Long> expiredEnemies;
    private List<Long> expiredBullets;
    private List<Long> expiredPlayers;
    private Map<Long, Long> playerLastShotTime;
    private TileManager tileManager;
    private ShortIdAllocator shortIdAllocator = new ShortIdAllocator();
    private final ReentrantLock playerLock = new ReentrantLock();

    // Only populated on vault realms (mapId == VAULT_MAP_ID); list[0] is the only index v1 uses.
    private final Map<Long, List<PotionStorage>> playerPotionStorage = new ConcurrentHashMap<>();

    // partyIds currently occupying this dungeon; checked against MapModel.maxPartyCount.
    private final Set<Long> dungeonPartyIds = ConcurrentHashMap.newKeySet();
    // Baked at FIRST-party entry so later joiners can't shift mob HP/damage mid-run.
    private float partyDifficultyBonus = 0f;
    public Set<Long> getDungeonPartyIds() { return this.dungeonPartyIds; }
    public float getPartyDifficultyBonus() { return this.partyDifficultyBonus; }
    public void   setPartyDifficultyBonus(float v) { this.partyDifficultyBonus = v; }

    private transient SpatialHashGrid spatialGrid;
    private transient Map<Long, NetObjectMovement> tickMovementCache;
    private transient Map<Long, UpdatePacket> tickStrippedUpdateCache;

    // Per-enemy damage accounting for soulbound loot; keyed enemyId -> (playerId -> damage).
    // Logic lives in ServerSoulboundHelper.
    private final transient Map<Long, Map<Long, Integer>> damageTracker = new ConcurrentHashMap<>();

    private final List<DotState> activeDots = new ArrayList<>();

    private final List<PoisonThrowState> pendingPoisonThrows = new ArrayList<>();
    private final List<TrapState> activeTraps = new ArrayList<>();
    private final List<DecoyState> activeDecoys = new ArrayList<>();

    // Ninja Kage Bunshin clones — real Player instances (same sprite/class as source).
    private final List<CloneState> activeClones = new ArrayList<>();
    private final List<ActiveRealmEvent> activeRealmEvents = new ArrayList<>();

    private boolean isServer;
    private boolean shutdown = false;

    // Flip to true ONLY after vault chests have been spawned, so serializeChestsForSave
    // can refuse to wipe persisted chests if a save races with setup.
    private volatile boolean chestsLoaded = false;

    private float bulletScaleThisTick = 1.0f;

    public List<ActiveRealmEvent> getActiveRealmEvents() {
        return this.activeRealmEvents;
    }

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

    public boolean isShared() {
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return node.isShared();
        }
        return false;
    }

    public boolean isDungeonInstance() {
        return this.sourceRealmId != 0L;
    }

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

    public void setupChests(final Player player) {
        try {
            final PlayerAccountDto account = ServerGameLogic.DATA_SERVICE
                    .executeGet("/data/account/" + player.getAccountUuid(), null, PlayerAccountDto.class);
            this.loadPotionStorage(player, account);
            final List<ChestDto> vaultChests = account.getPlayerVault();
            final int count = vaultChests.size();
            if (count == 0) {
                this.chestsLoaded = true;
                return;
            }

            final int cols = 2;
            final int rows = (int) Math.ceil(count / (double) cols);
            final int spacingX = 64;
            final int spacingY = 48;
            // Derive from the actual map so swapping the vault map (e.g.
            // VAULT_MAP_ID) doesn't require re-tuning hardcoded pixels.
            final MapModel vaultModel = GameDataManager.MAPS.get(this.mapId);
            final Vector2f mc = vaultModel != null ? vaultModel.getCenter() : new Vector2f(16 * 32, 16 * 32);
            final float centerX = mc.x;
            final float startY = mc.y - (rows * spacingY) / 2f + spacingY / 2f;
            final float leftColX = centerX - spacingX;
            final float rightColX = centerX + spacingX;

            for (int i = 0; i < count; i++) {
                final ChestDto chest = vaultChests.get(i);
                final float x = (i % cols) == 0 ? leftColX : rightColX;
                final float y = startY + (i / cols) * spacingY;
                final List<GameItem> itemsInChest = chest.getItems().stream()
                        .map(GameItem::fromGameItemRef).collect(Collectors.toList());
                final Chest toSpawn = new Chest(new Vector2f(x, y), itemsInChest.toArray(new GameItem[8]));
                toSpawn.setSoulboundPlayerId(player.getId());
                this.addLootContainer(toSpawn);
            }
            this.chestsLoaded = true;
        } catch (Exception e) {
            // Leave chestsLoaded=false so a subsequent save can't wipe persisted chests.
            Realm.log.error("Failed to get player account for chests. Reason: {}", e);
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
        if (!result.isEmpty()) {
            this.chestsLoaded = true;
        }
        return result;
    }

    // Returns null when chest spawn hasn't completed; the chest endpoint bulk-replaces
    // the vault, so a serialize-too-early race would wipe persisted chests.
    public List<ChestDto> serializeChestsForSave() {
        if (!this.chestsLoaded) {
            Realm.log.warn("[REALM] Refusing to serialize chests before setupChests has completed (realmId={}, mapId={}). Skipping save to avoid wipe.",
                    this.realmId, this.mapId);
            return null;
        }
        return this.serializeChests();
    }

    private void loadPotionStorage(final Player player, final PlayerAccountDto account) {
        final List<ChestDto> persisted = account.getPlayerPotionStorage();
        final List<PotionStorage> hydrated = new ArrayList<>();
        if (persisted != null) {
            for (final ChestDto cd : persisted) {
                if (cd == null) continue;
                final PotionStorage ps = new PotionStorage(cd.getChestUuid(),
                        cd.getOrdinal() == null ? hydrated.size() : cd.getOrdinal(),
                        new GameItem[PotionStorage.SIZE]);
                if (cd.getItems() != null) {
                    for (final GameItemRefDto ref : cd.getItems()) {
                        if (ref == null || ref.getSlotIdx() == null) continue;
                        final int slot = ref.getSlotIdx();
                        if (slot < 0 || slot >= PotionStorage.SIZE) continue;
                        ps.getItems()[slot] = GameItem.fromGameItemRef(ref);
                    }
                }
                hydrated.add(ps);
            }
        }
        this.playerPotionStorage.put(player.getId(), hydrated);
    }

    public List<ChestDto> serializePotionStorage(final long playerId) {
        final List<PotionStorage> list = this.playerPotionStorage.get(playerId);
        final List<ChestDto> result = new ArrayList<>();
        if (list == null) return result;
        for (final PotionStorage ps : list) {
            if (ps == null) continue;
            final ChestDto chest = ChestDto.builder()
                    .chestId(ps.getChestUid())
                    .chestUuid(ps.getChestUid())
                    .ordinal(ps.getOrdinal())
                    .build();
            final List<GameItemRefDto> refs = new ArrayList<>();
            for (int i = 0; i < ps.getItems().length; i++) {
                final GameItem item = ps.getItems()[i];
                if (item == null) continue;
                refs.add(item.toGameItemRefDto(i));
            }
            chest.setItems(refs);
            result.add(chest);
        }
        return result;
    }

    /**
     * Gated variant for the same wipe-protection reason as
     * {@link #serializeChestsForSave()}: a save during early setup races
     * could otherwise replace persisted storage with [].
     */
    public List<ChestDto> serializePotionStorageForSave(final long playerId) {
        if (!this.chestsLoaded) {
            Realm.log.warn("[REALM] Refusing to serialize potion storage before setupChests has completed (realmId={}, mapId={}). Skipping save to avoid wipe.",
                    this.realmId, this.mapId);
            return null;
        }
        return this.serializePotionStorage(playerId);
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
        final MapModel model = GameDataManager.MAPS.get(mapId);
        this.isPvp = model != null && model.isPvp();
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

    public List<Long> queryEnemiesNear(float x, float y, float radius) {
        if (this.spatialGrid == null) return Collections.emptyList();
        final List<Long> raw = this.spatialGrid.queryRadius(x, y, radius);
        if (raw.isEmpty()) return raw;
        final List<Long> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            final long id = raw.get(i);
            if (this.enemies.containsKey(id)) out.add(id);
        }
        return out;
    }

    public boolean removeEnemy(Enemy enemy) {
        final Enemy e = this.enemies.remove(enemy.getId());
        if (this.spatialGrid != null) {
            this.spatialGrid.remove(enemy.getId());
        }
        this.shortIdAllocator.release(enemy.getId());
        reapBulletsFromEnemy(enemy.getId());
        return e != null;
    }

    // Without this sweep, enemy bullets outlive their source up to 10s and stream as ghosts.
    private void reapBulletsFromEnemy(long srcEnemyId) {
        if (srcEnemyId == 0L || this.bullets == null || this.bullets.isEmpty()) return;
        int reaped = 0;
        final Iterator<Map.Entry<Long, Bullet>> it = this.bullets.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Bullet> entry = it.next();
            final Bullet b = entry.getValue();
            if (b == null || b.getSrcEntityId() != srcEnemyId) continue;
            if (this.spatialGrid != null) this.spatialGrid.remove(entry.getKey());
            this.shortIdAllocator.release(entry.getKey());
            it.remove();
            reaped++;
        }
        if (reaped > 0) {
            Realm.log.debug("[Reap] dropped {} in-flight bullets from dead enemy {}", reaped, srcEnemyId);
        }
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

    // Flat scan beats a grid query here — player count is bounded (~40)
    // while the grid is dominated by enemies/bullets.
    public Player[] getPlayersInRadiusFast(Vector2f center, float radius) {
        final float radiusSq = radius * radius;
        final List<Player> objs = new ArrayList<>();
        for (final Player p : this.players.values()) {
            if (p.isHiddenFromOthers()) continue;
            float dx = p.getPos().x - center.x;
            float dy = p.getPos().y - center.y;
            if (dx * dx + dy * dy <= radiusSq) objs.add(p);
        }
        return objs.toArray(new Player[0]);
    }

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

    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius) {
        return getLoadPacketCircularFast(center, radius, -1);
    }

    public LoadPacket getLoadPacketCircularFast(Vector2f center, float radius, long requestingPlayerId) {
        if (this.spatialGrid == null) {
            return getLoadPacketCircular(center, radius, requestingPlayerId);
        }
        final float radiusSq = radius * radius;
        // Wider bullet radius keeps long-range projectiles visible past the viewport.
        final float bulletRadius = radius * 2f;
        final float bulletRadiusSq = bulletRadius * bulletRadius;
        LoadPacket load = null;
        try {
            final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
            final List<Player> playersToLoadList = new ArrayList<>();
            final List<LootContainer> containersToLoad = new ArrayList<>();
            final List<Portal> portalsToLoad = new ArrayList<>();
            final List<EnemyDist> enemyCandidates = new ArrayList<>();
            final List<BulletDist> bulletCandidatesInner = new ArrayList<>();

            // Wall occlusion: hide enemies/players/loot the requesting player can't
            // see through a wall. Skipped entirely in wall-less realms (the open
            // overworld) so the only cost lands in dungeons/boss rooms. Portals and
            // bullets are never occluded (navigation aids / in-flight projectiles).
            final boolean occlude = requestingPlayerId >= 0 && this.tileManager != null
                    && this.tileManager.hasWalls();

            for (int i = 0; i < candidates.size(); i++) {
                final long id = candidates.get(i);
                Player p = this.players.get(id);
                if (p != null) {
                    if (p.isHiddenFromOthers() && p.getId() != requestingPlayerId) continue;
                    float dx = p.getPos().x - center.x;
                    float dy = p.getPos().y - center.y;
                    if (dx * dx + dy * dy <= radiusSq) {
                        if (occlude && p.getId() != requestingPlayerId
                                && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                        p.getPos().x + p.getSize() / 2f, p.getPos().y + p.getSize() / 2f)) {
                            continue;
                        }
                        playersToLoadList.add(p);
                    }
                    continue;
                }
                Enemy e = this.enemies.get(id);
                if (e != null) {
                    float dx = e.getPos().x - center.x;
                    float dy = e.getPos().y - center.y;
                    final float distSq = dx * dx + dy * dy;
                    if (distSq <= radiusSq) {
                        if (occlude && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                e.getPos().x + e.getSize() / 2f, e.getPos().y + e.getSize() / 2f)) {
                            continue;
                        }
                        enemyCandidates.add(new EnemyDist(e, distSq));
                    }
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
                    if (dx * dx + dy * dy <= radiusSq && lc.isVisibleToPlayer(requestingPlayerId)) {
                        if (occlude && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                lc.getPos().x, lc.getPos().y)) {
                            continue;
                        }
                        containersToLoad.add(lc);
                    }
                }
            }

            if (enemyCandidates.size() > MAX_ENEMIES_PER_LOAD) {
                enemyCandidates.sort((a, b1) -> Float.compare(a.distSq, b1.distSq));
            }
            final List<Enemy> enemiesToLoad = new ArrayList<>(
                    Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD));
            for (int i = 0, n = Math.min(enemyCandidates.size(), MAX_ENEMIES_PER_LOAD); i < n; i++) {
                enemiesToLoad.add(enemyCandidates.get(i).enemy);
            }
            // Player-fired bullets always fit first; enemy-bullet spam can't starve them.
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

            // Outer-ring bullet pass catches projectiles fired just beyond the viewport.
            final List<Long> bulletCandidates = this.spatialGrid.queryRadius(center.x, center.y, bulletRadius);
            for (int i = 0; i < bulletCandidates.size(); i++) {
                if (bulletsToLoad.size() >= MAX_BULLETS_PER_LOAD) break;
                final long id = bulletCandidates.get(i);
                Bullet b = this.bullets.get(id);
                if (b == null || b.isEnemy()) continue;
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                float dsq = dx * dx + dy * dy;
                if (dsq <= radiusSq) continue;
                if (dsq <= bulletRadiusSq) bulletsToLoad.add(b);
            }
            for (int i = 0; i < bulletCandidates.size(); i++) {
                if (bulletsToLoad.size() >= MAX_BULLETS_PER_LOAD) break;
                final long id = bulletCandidates.get(i);
                Bullet b = this.bullets.get(id);
                if (b == null || !b.isEnemy()) continue;
                float dx = b.getPos().x - center.x;
                float dy = b.getPos().y - center.y;
                float dsq = dx * dx + dy * dy;
                if (dsq <= radiusSq) continue;
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

    // No cap, no payload hydration — RealmManagerServer's ledger diff applies caps later.
    public VisibleIds getVisibleIdsCircularFast(Vector2f center, float radius, long requestingPlayerId) {
        final VisibleIds out = new VisibleIds();
        if (this.spatialGrid == null) return out;
        final float radiusSq = radius * radius;
        final float bulletRadius = radius * 2f;
        final float bulletRadiusSq = bulletRadius * bulletRadius;
        // Wall occlusion: hide enemies/players/loot the requesting player can't see
        // through a wall (anti-ESP + can't-see-into-the-next-room). No-op in wall-less
        // realms so the open overworld pays nothing. Portals/bullets are never occluded.
        final boolean occlude = requestingPlayerId >= 0 && this.tileManager != null
                && this.tileManager.hasWalls();
        final List<Long> innerCandidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
        for (int i = 0; i < innerCandidates.size(); i++) {
            final long id = innerCandidates.get(i);
            final Player p = this.players.get(id);
            if (p != null) {
                if (p.isHiddenFromOthers() && p.getId() != requestingPlayerId) continue;
                final float dx = p.getPos().x - center.x;
                final float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) {
                    if (occlude && p.getId() != requestingPlayerId
                            && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                    p.getPos().x + p.getSize() / 2f, p.getPos().y + p.getSize() / 2f)) {
                        continue;
                    }
                    out.getPlayers().add(id);
                }
                continue;
            }
            final Enemy e = this.enemies.get(id);
            if (e != null) {
                final float dx = e.getPos().x - center.x;
                final float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) {
                    if (occlude && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                            e.getPos().x + e.getSize() / 2f, e.getPos().y + e.getSize() / 2f)) {
                        continue;
                    }
                    out.getEnemies().add(id);
                }
                continue;
            }
            final Bullet b = this.bullets.get(id);
            if (b != null) {
                final float dx = b.getPos().x - center.x;
                final float dy = b.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) out.getBullets().add(id);
                continue;
            }
            final Portal portal = this.portals.get(id);
            if (portal != null) {
                final float dx = portal.getPos().x - center.x;
                final float dy = portal.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq) out.getPortals().add(id);
                continue;
            }
            final LootContainer lc = this.loot.get(id);
            if (lc != null) {
                final float dx = lc.getPos().x - center.x;
                final float dy = lc.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq && lc.isVisibleToPlayer(requestingPlayerId)) {
                    if (occlude && !VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                            lc.getPos().x, lc.getPos().y)) {
                        continue;
                    }
                    out.getContainers().add(id);
                }
            }
        }
        final List<Long> outerBulletCandidates = this.spatialGrid.queryRadius(center.x, center.y, bulletRadius);
        for (int i = 0; i < outerBulletCandidates.size(); i++) {
            final long id = outerBulletCandidates.get(i);
            if (out.getBullets().contains(id)) continue;
            final Bullet b = this.bullets.get(id);
            if (b == null) continue;
            final float dx = b.getPos().x - center.x;
            final float dy = b.getPos().y - center.y;
            final float dsq = dx * dx + dy * dy;
            if (dsq <= radiusSq) continue;
            if (dsq <= bulletRadiusSq) out.getBullets().add(id);
        }
        return out;
    }

    // IDs that no longer resolve to a live entity are silently dropped — the
    // caller's ledger updates from what actually shipped.
    public LoadPacket buildLoadPacketForIds(Collection<Long> playerIds,
                                            Collection<Long> enemyIds,
                                            Collection<Long> bulletIds,
                                            Collection<Long> containerIds,
                                            Collection<Long> portalIds,
                                            Vector2f originForDifficulty) {
        try {
            final List<Player> ps = new ArrayList<>(playerIds.size());
            for (final Long id : playerIds) {
                final Player p = this.players.get(id);
                if (p != null) ps.add(p);
            }
            final List<Enemy> es = new ArrayList<>(enemyIds.size());
            for (final Long id : enemyIds) {
                final Enemy e = this.enemies.get(id);
                if (e != null) es.add(e);
            }
            final List<Bullet> bs = new ArrayList<>(bulletIds.size());
            for (final Long id : bulletIds) {
                final Bullet b = this.bullets.get(id);
                if (b != null) bs.add(b);
            }
            final List<LootContainer> cs = new ArrayList<>(containerIds.size());
            for (final Long id : containerIds) {
                final LootContainer lc = this.loot.get(id);
                if (lc != null) cs.add(lc);
            }
            final List<Portal> rs = new ArrayList<>(portalIds.size());
            for (final Long id : portalIds) {
                final Portal pt = this.portals.get(id);
                if (pt != null) rs.add(pt);
            }
            final LoadPacket load = LoadPacket.from(
                    ps.toArray(new Player[0]),
                    cs.toArray(new LootContainer[0]),
                    bs.toArray(new Bullet[0]),
                    es.toArray(new Enemy[0]),
                    rs.toArray(new Portal[0]),
                    this.shortIdAllocator);
            if (load != null && originForDifficulty != null) {
                load.setDifficulty((byte) this.getZoneDifficulty(originForDifficulty.x, originForDifficulty.y));
            }
            return load;
        } catch (Exception e) {
            Realm.log.error("Failed to hydrate LoadPacket for ID set. Reason: {}", e.getMessage());
            return null;
        }
    }

    public void clearTickMovementCache() {
        if (this.tickMovementCache != null) {
            this.tickMovementCache.clear();
        }
    }

    public void clearTickStrippedUpdateCache() {
        if (this.tickStrippedUpdateCache != null) {
            this.tickStrippedUpdateCache.clear();
        }
    }

    public void setBulletScaleThisTick(float scale) {
        this.bulletScaleThisTick = scale;
    }

    public float getBulletScaleThisTick() {
        return this.bulletScaleThisTick;
    }

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

    private NetObjectMovement getOrBuildMovement(GameObject obj) {
        if (this.tickMovementCache == null) {
            this.tickMovementCache = new HashMap<>(64);
        }
        NetObjectMovement m = this.tickMovementCache.get(obj.getId());
        if (m == null) {
            m = new NetObjectMovement(obj);
            this.tickMovementCache.put(obj.getId(), m);
        }
        return m;
    }

    public ObjectMovePacket getGameObjectsAsPacketsCircularFast(Vector2f center, float radius) throws Exception {
        if (this.spatialGrid == null) {
            return getGameObjectsAsPacketsCircular(center, radius);
        }
        final float radiusSq = radius * radius;
        // Occlude positions server-side so a modified client can't read entities behind
        // a wall from the movement stream. center is the viewer's own position, so its
        // LOS-to-self is always clear — the player's own movement is never dropped.
        final boolean occlude = this.tileManager != null && this.tileManager.hasWalls();
        final List<Long> candidates = this.spatialGrid.queryRadius(center.x, center.y, radius);
        final List<NetObjectMovement> mvts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final long id = candidates.get(i);
            Player p = this.players.get(id);
            if (p != null) {
                float dx = p.getPos().x - center.x;
                float dy = p.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq && (!occlude
                        || VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                p.getPos().x + p.getSize() / 2f, p.getPos().y + p.getSize() / 2f))) {
                    mvts.add(getOrBuildMovement(p));
                }
                if (p.getTeleported()) p.setTeleported(false);
                continue;
            }
            Enemy e = this.enemies.get(id);
            if (e != null) {
                float dx = e.getPos().x - center.x;
                float dy = e.getPos().y - center.y;
                if (dx * dx + dy * dy <= radiusSq && (!occlude
                        || VisibilityHelper.hasLineOfSight(this.tileManager, center.x, center.y,
                                e.getPos().x + e.getSize() / 2f, e.getPos().y + e.getSize() / 2f))) {
                    mvts.add(getOrBuildMovement(e));
                }
                if (e.getTeleported()) e.setTeleported(false);
                continue;
            }
            // Bullets skipped — clients predict from initial velocity in LoadPacket.
        }
        if (mvts.isEmpty()) return null;
        return ObjectMovePacket.from(mvts.toArray(new NetObjectMovement[0]));
    }

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

    // Bullets are excluded; clients simulate them from initial LoadPacket velocity.
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

    public LoadPacket getLoadPacketCircular(Vector2f center, float radius) {
        return getLoadPacketCircular(center, radius, -1);
    }

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

                // Phase 4 — apply the dungeon's party-size difficulty
                // bonus to every spawn. Bonus is 0 for overworld realms
                // and was captured at first-party-entry for dungeons.
                final float effDiff = diff + this.partyDifficultyBonus;
                final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                        spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
                enemy.setDifficulty(effDiff);
                enemy.setHealth((int) (enemy.getHealth() * effDiff));
                enemy.getStats().setHp((short) (enemy.getStats().getHp() * effDiff));
                enemy.setPos(spawnPos);
                this.addEnemy(enemy);
            }
        }

        // spawnStaticEnemies is the caller's responsibility (RealmManagerServer.addRealm).
        this.initialEnemyCount = this.enemies.size();
    }

    public void respawnEnemies(int batchSize) {
        final TerrainGenerationParameters params = this.tileManager.getTerrainParams();
        if (params == null) return;
        final List<OverworldZone> zones = params.getZones();
        if (zones == null || zones.isEmpty()) return;

        // Only respawn if enemy count has dropped below 75% of the initial population
        final int threshold = (int) (this.initialEnemyCount * 0.75);
        if (this.enemies.size() >= threshold) return;

        // Cap batch so we don't overshoot the initial count
        batchSize = Math.min(batchSize, this.initialEnemyCount - this.enemies.size());
        if (batchSize <= 0) return;

        // Roster per zone, drawn straight from the terrain's enemy groups. A zone's
        // spawns ONLY ever come from its own group — there is no cross-zone fallback,
        // so an inner-tier roster can never leak into an outer zone (and vice versa).
        final Map<Integer, List<EnemyModel>> enemiesByGroup = new HashMap<>();
        for (EnemyGroup group : params.getEnemyGroups()) {
            List<EnemyModel> models = new ArrayList<>();
            for (int enemyId : group.getEnemyIds()) {
                EnemyModel m = GameDataManager.ENEMIES.get(enemyId);
                if (m != null) models.add(m);
            }
            enemiesByGroup.put(group.getOrdinal(), models);
        }

        // Don't spawn within player viewport radius (10 tiles = 320px)
        final float viewportRadius = 10f * GlobalConstants.BASE_TILE_SIZE;
        final float minPlayerDistSq = viewportRadius * viewportRadius;
        final List<Vector2f> playerPositions = new ArrayList<>();
        for (Player p : this.players.values()) {
            playerPositions.add(p.getPos());
        }

        // Spread the batch evenly across the zones so no single zone (typically the
        // large outer ring) dominates and the inner tiers are left starved.
        final int zoneCount = zones.size();
        int spawned = 0;
        for (int z = 0; z < zoneCount && spawned < batchSize; z++) {
            final OverworldZone zone = zones.get(z);
            final List<EnemyModel> spawnList = enemiesByGroup.get(zone.getEnemyGroupOrdinal());
            if (spawnList == null || spawnList.isEmpty()) continue;
            final float diff = Math.max(1.0f, zone.getDifficulty());

            // Even share; the leftover from an uneven split goes to the first zones.
            int zoneQuota = batchSize / zoneCount;
            if (z < batchSize % zoneCount) zoneQuota++;

            int zoneSpawned = 0;
            int attempts = 0;
            final int maxAttempts = Math.max(1, zoneQuota) * 10;
            while (zoneSpawned < zoneQuota && spawned < batchSize && attempts < maxAttempts) {
                attempts++;
                final Vector2f spawnPos = this.tileManager.getSafePositionInZone(zone);
                if (spawnPos == null) break;
                if (this.tileManager.isVoidTile(spawnPos, 0, 0)) continue;

                // getSafePositionInZone can fall back to an arbitrary safe tile when the
                // band is hard to hit; confirm the tile really sits in this zone so a
                // hard zone's enemy can't end up stamped onto an easy zone's ground.
                final OverworldZone actual = this.tileManager.getZoneForPosition(spawnPos.x, spawnPos.y);
                if (actual == null || actual.getEnemyGroupOrdinal() != zone.getEnemyGroupOrdinal()) continue;

                boolean nearPlayer = false;
                for (Vector2f pp : playerPositions) {
                    float dx = spawnPos.x - pp.x, dy = spawnPos.y - pp.y;
                    if (dx * dx + dy * dy < minPlayerDistSq) {
                        nearPlayer = true;
                        break;
                    }
                }
                if (nearPlayer) continue;

                final EnemyModel toSpawn = spawnList.get(Realm.RANDOM.nextInt(spawnList.size()));
                if (this.tileManager.collidesAtPosition(spawnPos, toSpawn.getSize())) continue;

                final Enemy enemy = new Enemy(Realm.RANDOM.nextLong(), toSpawn.getEnemyId(),
                        spawnPos.clone(), toSpawn.getSize(), toSpawn.getAttackId());
                enemy.setDifficulty(diff);
                enemy.setHealth((int) (enemy.getHealth() * diff));
                enemy.getStats().setHp((short) (enemy.getStats().getHp() * diff));
                enemy.setPos(spawnPos);
                this.addEnemy(enemy);
                zoneSpawned++;
                spawned++;
            }
        }

        if (spawned > 0) {
            log.info("[REALM] Respawned {} enemies across {} zones (total: {})",
                    spawned, zoneCount, this.enemies.size());
        }
    }

    /** Register a damage-over-time effect (poison or bleed) on an enemy. Refreshes
     *  rather than stacks: an enemy carries at most one DoT per status type. */
    public void registerDot(long enemyId, StatusEffectType status, int totalDamage,
            long duration, long sourcePlayerId) {
        this.activeDots.removeIf(dot -> dot.enemyId == enemyId && dot.status == status);
        this.activeDots.add(new DotState(enemyId, status, totalDamage, duration, sourcePlayerId));
    }

    public void removePlayerDots(long playerId) {
        this.activeDots.removeIf(dot -> dot.sourcePlayerId == playerId);
    }

    public void processDots(RealmManagerServer mgr) {
        if (this.activeDots.isEmpty()) return;
        final long now = Instant.now().toEpochMilli();
        final Iterator<DotState> it = this.activeDots.iterator();
        while (it.hasNext()) {
            final DotState dot = it.next();
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
            final TextEffect dotFx = enemy.hasEffect(StatusEffectType.ARMOR_BROKEN)
                    ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
            mgr.broadcastTextEffect(EntityType.ENEMY, enemy,
                    dotFx, "-" + tickDamage);

            if (enemy.getDeath()) {
                mgr.enemyDeath(this, enemy);
                it.remove();
            }
        }
    }

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
                // 10-tile sight radius mirrors TextEffectPacket convention.
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
                                    TextEffect.PLAYER_INFO, effectType.name());
                        }
                        if (trap.damage > 0) {
                            enemy.setHealth(enemy.getHealth() - trap.damage);
                            final TextEffect trapFx = enemy.hasEffect(StatusEffectType.ARMOR_BROKEN)
                                    ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
                            mgr.broadcastTextEffect(this, EntityType.ENEMY, enemy,
                                    trapFx, "-" + trap.damage);
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

            // Per-realm broadcast — the global enqueue would fan out to every connected client.
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
                    this.registerDot(enemy.getId(), StatusEffectType.POISONED, t.totalDamage,
                            t.poisonDuration, t.sourcePlayerId);
                    mgr.broadcastTextEffect(EntityType.ENEMY, enemy,
                            TextEffect.DAMAGE, "POISONED");
                }
            }

            // PvP: the same cloud poisons opposing-team players (enemy DoTs live on the
            // realm; player DoTs are owned by PvpEffectsManager).
            if (this.isPvp()) {
                PvpEffectsManager.applyPoisonAoe(mgr, this, t.sourcePlayerId,
                        t.landX, t.landY, t.radius, t.poisonDuration);
            }
        }
    }

    public void removePlayerPoisonThrows(long playerId) {
        this.pendingPoisonThrows.removeIf(t -> t.sourcePlayerId == playerId);
    }

    // Enemy targeting treats decoys as real players via this proxy.
    public Player getClosestDecoyTarget(final Vector2f pos, float currentBestDist) {
        Player best = null;
        for (final DecoyState d : this.activeDecoys) {
            final Enemy decoy = this.enemies.get(d.enemyId);
            if (decoy == null) continue;
            final float dist = decoy.getPos().distanceTo(pos);
            if (dist < currentBestDist) {
                currentBestDist = dist;
                best = new Player(d.enemyId, decoy.getPos().clone(),
                        decoy.getSize(), CharacterClass.NINJA);
            }
        }
        return best;
    }

    public void registerDecoy(long enemyId, long sourcePlayerId, float originX, float originY,
                               float dx, float dy, float maxTravelDist, long durationMs) {
        this.activeDecoys.add(new DecoyState(enemyId, sourcePlayerId, originX, originY,
                dx, dy, maxTravelDist, durationMs));
    }

    public void processDecoys(RealmManagerServer mgr) {
        if (this.activeDecoys.isEmpty()) return;
        final Iterator<DecoyState> it = this.activeDecoys.iterator();
        while (it.hasNext()) {
            final DecoyState d = it.next();
            final Enemy decoy = this.enemies.get(d.enemyId);

            if (decoy == null || decoy.getDeath()) {
                it.remove();
                continue;
            }

            if (d.isExpired()) {
                it.remove();
                this.expiredEnemies.add(d.enemyId);
                this.removeEnemy(decoy);
                continue;
            }

            if (!d.stopped) {
                float traveled_x = decoy.getPos().x - d.originX;
                float traveled_y = decoy.getPos().y - d.originY;
                if (traveled_x * traveled_x + traveled_y * traveled_y >= d.maxTravelDistSq) {
                    d.stopped = true;
                    // Clear direction flags so the walk animation stops.
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

    public void registerClone(long clonePlayerId, long sourcePlayerId,
                              float dx, float dy, long durationMs) {
        this.activeClones.add(new CloneState(clonePlayerId, sourcePlayerId, dx, dy, durationMs));
    }

    public void processClones(RealmManagerServer mgr) {
        if (this.activeClones.isEmpty()) return;
        final Iterator<CloneState> it = this.activeClones.iterator();
        while (it.hasNext()) {
            final CloneState c = it.next();
            final Player clone = this.players.get(c.clonePlayerId);
            if (clone == null) {
                it.remove();
                continue;
            }
            if (c.isExpired()) {
                final float cx = clone.getPos().x + clone.getSize() / 2f;
                final float cy = clone.getPos().y + clone.getSize() / 2f;
                mgr.enqueueServerPacketToRealm(this,
                        CreateEffectPacket.aoeEffect(
                                CreateEffectPacket.EFFECT_SMOKE_POOF,
                                cx, cy, 40f, (short) 600));
                this.removePlayer(clone);
                it.remove();
                continue;
            }
            clone.getPos().x += c.dx;
            clone.getPos().y += c.dy;
        }
    }

    public void removePlayerClones(long sourcePlayerId) {
        final Iterator<CloneState> it = this.activeClones.iterator();
        while (it.hasNext()) {
            final CloneState c = it.next();
            if (c.sourcePlayerId != sourcePlayerId) continue;
            final Player clone = this.players.get(c.clonePlayerId);
            if (clone != null) this.removePlayer(clone);
            it.remove();
        }
    }

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
            enemy.setStaticSpawn(true);
            float diff = this.getZoneDifficulty(pos.x, pos.y);
            enemy.setDifficulty(diff);
            enemy.setHealth((int) (enemy.getHealth() * diff));
            this.addEnemy(enemy);
            Realm.log.info("Static spawn: {} at ({}, {}) in realm mapId={}", model.getName(), pos.x, pos.y, mapId);
        }
    }

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
        // Set-piece may have written wall tiles — force a re-scan for LOS gating.
        this.tileManager.invalidateWallCache();
    }

    // Returns [savedBase[h][w], savedCollision[h][w]].
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
        // Restoring may have removed the set-piece's walls — force a re-scan.
        this.tileManager.invalidateWallCache();
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

    // Resolution order: terrain > map > dungeon-graph node > 1.0.
    public float getDifficulty() {
        MapModel map = GameDataManager.MAPS.get(this.mapId);
        if (map != null && map.getTerrainId() >= 0) {
            TerrainGenerationParameters terrain = GameDataManager.TERRAINS.get(map.getTerrainId());
            if (terrain != null && terrain.getDifficulty() > 0f) {
                return terrain.getDifficulty();
            }
        }
        if (map != null && map.getDifficulty() > 0f) {
            return map.getDifficulty();
        }
        if (this.nodeId != null && GameDataManager.DUNGEON_GRAPH != null) {
            DungeonGraphNode node = GameDataManager.DUNGEON_GRAPH.get(this.nodeId);
            if (node != null) return Math.max(1.0f, node.getDifficulty());
        }
        return 1.0f;
    }

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
