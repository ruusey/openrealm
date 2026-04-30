# OpenRealm

### A Multiplayer 8-Bit Bullet Hell Dungeon Crawler — Play Now at [OpenRealm.Net](http://openrealm.net/)

<div>
    <img src="https://github.com/ruusey/openrealm/blob/main/banner.png" width="100%">
    <img src="https://github.com/ruusey/openrealm/blob/main/MobileView.png" width="100%">
</div>

---

## About

**OpenRealm** is a real-time multiplayer bullet hell dungeon crawler inspired by classic 8-bit action RPGs. Players explore a persistent overworld, fight through procedurally generated dungeons, collect loot, and battle bosses — all rendered in pixel art and playable directly in the browser or via a native Java client.

The game features a server-authoritative architecture with client-side prediction, supporting dozens of concurrent players with responsive movement even at higher latencies. The server runs at a fixed 64Hz tick rate with deterministic physics, ensuring fair and consistent gameplay for all players.

### Key Features

- **Browser & Desktop Play** — Full-featured HTML5 web client (PixiJS) with mobile touch controls, plus a native Java desktop client (LibGDX)
- **Persistent World** — Shared overworld with four biome zones of increasing difficulty, from the Beach to the Summit
- **Procedural Dungeons** — Over 20 unique dungeon types with generated room layouts, corridor systems, and boss encounters
- **14 Character Classes** — Each with unique abilities, stat progressions, and equipment types (Rogue, Archer, Wizard, Priest, Warrior, Knight, Paladin, Assassin, Necromancer, Mystic, Trickster, Sorcerer, Huntress, Ninja)
- **Realm Events** — Boss encounters with multi-phase attacks, minion-wave triggers tied to boss HP, and pinned minimap markers (Skull Shrine, Cube God, Ghost God, Lord of the Lost Lands)
- **130+ Enemy Types** — From beach crabs to realm gods, with multi-phase AI, burst patterns, and status effects
- **Real-Time Combat** — Dodge hundreds of projectiles with precise movement, fire weapons with client-predicted instant feedback
- **Permadeath & Loot** — Characters die permanently unless equipped with an Amulet of Resurrection. Tiered loot drops from enemies and dungeon bosses
- **Vault Storage** — Personal vault accessible from the Nexus hub for storing items across characters
- **Trading System** — Player-to-player item trading with confirmation UI
- **Forge System** — Pixel-edit enchantments onto equipment using crystals + essence
- **Fame Store** — Spend account fame on cosmetic dyes and stat-enchant crystals
- **Guest Accounts** — Play instantly without registration; upgrade to a full account anytime
- **Game Data Editor** — Full-featured web-based editor for tiles, enemies, items, animations, maps, projectiles, portals, loot tables, and more
- **Projectile Simulator** — Interactive canvas playground for testing and visualizing projectile patterns and enemy attack behaviors

---

## Architecture

OpenRealm consists of two services:

| Service | Description | Stack |
|---------|-------------|-------|
| **openrealm** (this repo) | Game server + desktop client | Java 17, LibGDX, WebSocket (NIO) |
| **[openrealm-data](https://github.com/ruusey/openrealm-data)** | Data service, REST API, web client, game editor | Java 17, Spring Boot, MongoDB, PixiJS |

### Networking

- **Server tick rate**: 64Hz with fixed-timestep simulation
- **Client prediction**: Sequence-numbered input commands (64Hz) with server reconciliation
- **Entity interpolation**: Smooth rendering of remote players and enemies between server snapshots, with extrapolation cap on stale velocities
- **Dead reckoning**: Server only emits `ObjectMovePacket` when an entity's position diverges from the velocity-based prediction (saves ~80% of move bandwidth at scale)
- **Packet transport**: Binary WebSocket protocol with 26+ packet types, zlib compression for frames > 128 bytes
- **Bandwidth**: ~2-3 KB/s per player during active gameplay, 40-player scenarios stay under 1 Mbit/s outbound

---

## Getting Started

### Prerequisites

- Java JDK 17+
- Apache Maven 3.8.3+
- MongoDB Server (for the data service)

### Running the Data Service

```bash
cd openrealm-data
mvn clean package
java -jar target/openrealm-data.jar
```

The data service starts on port 80 by default and provides:
- REST API for account management and game data
- Web client at `/game-data/webclient/index.html`
- Game data editor at `/game-data/editor/index.html`
- Static sprite sheet serving

### Running the Game Server

```bash
cd openrealm
mvn clean package
java -jar target/openrealm-shaded.jar {DATA_SERVER_ADDR}
```

The game server listens on:
- Port 2222 — Native TCP client connections
- Port 2223 — WebSocket connections (browser client)

### Running the Desktop Client

```bash
java -jar target/openrealm-shaded.jar -client {DATA_SERVER_ADDR}
```

---

## Controls

### Keyboard (Desktop & Browser)

| Key | Action |
|-----|--------|
| **W/A/S/D** | Move Up/Left/Down/Right |
| **Left Click** | Shoot / Pick up loot |
| **Right Click** | Use ability |
| **1-8** | Use inventory slot |
| **R / F1** | Return to Nexus |
| **F / F2 / Space** | Use nearest portal |
| **Enter** | Open chat / send message |
| **Escape** | Return to character select |

### Mobile (Touch)

- **Left joystick** — Movement
- **Right joystick** — Aim and shoot
- **Vault button** — Access vault storage
- **Double-tap** — Use ability at tap location

---

## Server Commands

Commands are entered in chat with a `/` prefix. Some require admin privileges.

| Command | Description | Access |
|---------|-------------|--------|
| `/pos` | Show current world position | All |
| `/help` | List available commands | All |
| `/about` | Server information | All |
| `/stat max` / `/stat {name} {value}` | Set or max stats | Admin |
| `/spawn {enemyId} [count]` | Spawn an enemy (or N enemies in a 4-tile disc) | Moderator |
| `/kill {radius_tiles}` | Kill all enemies within radius (skips INVINCIBLE NPCs) | Moderator |
| `/event` / `/event {id}` | List or spawn a realm event in the current realm | Moderator |
| `/item {itemId} [count]` | Spawn an item | Admin |
| `/portal {mapName}` | Open a portal | Admin |
| `/tp {name or x,y}` | Teleport | Admin |
| `/op {playerName}` | Promote/demote operator | Sys Admin |
| `/godmode` | Toggle invincibility | Admin |
| `/heal` | Restore HP and MP | Admin |
| `/fame {amount} [player]` | Award account fame | Moderator |
| `/seteffect add {effectId} {sec}` / `/seteffect clear` | Apply or clear status effects | Moderator |
| `/spawnbots {count} [spam]` | Stress-test bot connections | Sys Admin |
| `/killbots` | Kill orphaned stress-test bots | Sys Admin |

---

## Game Data

All game content is defined in JSON files served by the data service. Edit them directly or use the built-in web editor at `/game-data/editor/index.html`.

| File | Contents |
|------|----------|
| `character-classes.json` | Class definitions, base/max stats, starting equipment |
| `enemies.json` | Enemy stats, sprites, phase behaviors, attack patterns |
| `game-items.json` | Weapons, armor, abilities, consumables, stat bonuses |
| `projectile-groups.json` | Bullet patterns — speed, range, amplitude, flags |
| `animations.json` | Character sprite animations (idle, walk, attack) |
| `maps.json` | Static and procedural map definitions with tile layers |
| `terrains.json` | Procedural terrain parameters, biome zones, enemy groups |
| `tiles.json` | Tile definitions — collision, slowing, damaging flags |
| `portals.json` | Portal types and dungeon graph routing |
| `dungeon-graph.json` | Dungeon node graph — shared/instanced realms |
| `loot-groups.json` | Item drop groupings by tier |
| `loot-tables.json` | Per-enemy drop tables with probabilities |
| `setpieces.json` | Multi-tile structures stamped during decoration / event spawn |
| `realm-events.json` | Boss-event definitions: setpiece, boss enemyId, minion waves, allowed zones |
| `dye-assets.json` | Cosmetic dye registry — solid colors and patterned cloths |
| `character-class-masks.json` | Per-class pixel masks for dye recoloring |

---

## SDK & Extensibility

OpenRealm ships with a reflection-based scripting framework that lets you add packets, commands, AI behavior, item logic, terrain mutators, and event encounters **without touching the core engine**. Every extension point is discovered automatically at runtime via classpath scanning, so dropping a class in the right package is all that's required to register it.

The runtime classpath scanner is `IOService.CLASSPATH_SCANNER` (Reflections library, scoped to `com.openrealm`). It scans for subtypes and methods annotated with the SDK markers below.

### 1. Auto Packet Registration

Two annotation systems work together to make packets a one-class-per-message affair: declarative wire format on the type, and declarative server handlers on a method.

#### Defining a packet wire format

Use `@Streamable` and `@PacketId` on the class, and `@SerializableField` on each field. The `IOService` reads those annotations once at startup and emits a per-packet `PacketMappingInformation[]` array used for zero-reflection writes on the hot path.

```java
import com.openrealm.net.Packet;
import com.openrealm.net.Streamable;
import com.openrealm.net.core.PacketId;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableString;

@Streamable
@PacketId(packetId = (byte) 42)
public class MyCustomPacket extends Packet {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;

    @SerializableField(order = 1, type = SerializableString.class)
    private String message;

    @SerializableField(order = 2, type = NetTile.class, isCollection = true)
    private NetTile[] tiles;
}
```

- `@PacketId` — single byte identifier, must be unique across all packets. Server registers a reverse map at startup so `IOService.writePacket` can look up the id in O(1).
- `@SerializableField.order` — declares the wire order. Skipped numbers are tolerated; gaps are skipped at write time.
- `@SerializableField.type` — points to a `SerializableFieldType<T>` (one of the built-in primitives in `com.openrealm.net.core.nettypes`, or your own subclass for a complex type).
- `@SerializableField.isCollection = true` — emits `int32 length` followed by N elements. Required for arrays and collections.

To send the packet:

```java
mgr.enqueueServerPacket(player, MyCustomPacket.builder()
    .playerId(player.getId()).message("hello").tiles(tiles).build());
```

To override defaults, your packet can implement `read(DataInputStream)` and `write(value, DataOutputStream)` directly — see `NetGameItem` and `NetStats` for hand-coded fast paths used in hot loops.

#### Registering a server handler

Annotate any **static method** with `@PacketHandlerServer(MyCustomPacket.class)` and the classpath scanner will wire it into the dispatcher at boot. Methods are looked up first under `ServerGameLogic`, then `ServerTradeManager` — adding a new container class is a one-line addition to `registerPacketCallbacksReflection`.

```java
import com.openrealm.net.Packet;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.util.PacketHandlerServer;

public class ServerGameLogic {
    @PacketHandlerServer(MyCustomPacket.class)
    public static void handleMyCustom(RealmManagerServer mgr, Packet packet) {
        final MyCustomPacket p = (MyCustomPacket) packet;
        // ...
    }
}
```

For latency-critical packets (movement, shoot), prefer the direct `mgr.registerPacketCallback(MyCustomPacket.class, MyHandler::handle)` API in `RealmManagerServer.registerPacketCallbacks()` to avoid `MethodHandle` indirection.

### 2. IOService Auto-Serialization

`IOService` is the bidirectional codec for any `@Streamable` class — packets, nested net-DTOs, even custom struct-like records.

```java
// Write a packet to a stream:
final DataOutputStream out = ...;
final byte[] frame = IOService.writePacket(packet, out);

// Read a packet from a buffer:
final byte[] data = ...;
final MyCustomPacket pkt = IOService.readPacket(MyCustomPacket.class, data);
```

The frame layout is:
```
[1B packetId] [4B payload length incl. header] [payload bytes...]
```

Compression: `PacketCompression` automatically deflate-compresses any frame larger than 128 bytes (zlib `BEST_SPEED`) and flips a high bit on the length to mark it as compressed. The reader checks the bit and decompresses transparently.

#### Custom field types

If a built-in `Serializable*` type doesn't fit your data, subclass `SerializableFieldType<T>`:

```java
public class NetPosition extends SerializableFieldType<NetPosition> {
    private float x, y;

    @Override
    public int write(NetPosition value, DataOutputStream stream) throws Exception {
        stream.writeFloat(value.x);
        stream.writeFloat(value.y);
        return 8;
    }

    @Override
    public NetPosition read(DataInputStream stream) throws Exception {
        final NetPosition n = new NetPosition();
        n.x = stream.readFloat();
        n.y = stream.readFloat();
        return n;
    }
}
```

Then reference it from any `@SerializableField(type = NetPosition.class)`.

#### POJO mapping

`IOService.mapModel(src, DestType.class)` runs a ModelMapper translation between a transport DTO and a domain type, with built-in converters for `LootTier↔byte`, `StatusEffectType↔short`, etc. Register additional converters with `IOService.registerModelConverter(...)`.

### 3. Command Handlers

In-game chat commands (`/help`, `/spawn`, `/event`) are static methods discovered via `@CommandHandler`. Add a method, redeploy, and it shows up.

```java
import com.openrealm.account.dto.AccountProvision;
import com.openrealm.game.entity.Player;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.util.AdminRestrictedCommand;
import com.openrealm.util.CommandHandler;

public class ServerCommandHandler {

    @CommandHandler(value = "wave", description = "Spawn a minion wave at your feet. Usage: /wave {enemyId} {count}")
    @AdminRestrictedCommand(provisions = { AccountProvision.OPENREALM_MODERATOR })
    public static void invokeSpawnWave(RealmManagerServer mgr, Player target, ServerCommandMessage message)
            throws Exception {
        if (message.getArgs() == null || message.getArgs().size() < 2) {
            throw new IllegalArgumentException("Usage: /wave {enemyId} {count}");
        }
        final int enemyId = Integer.parseInt(message.getArgs().get(0));
        final int count   = Integer.parseInt(message.getArgs().get(1));
        // ... spawn logic
        mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(),
                "Spawned " + count + " of enemy " + enemyId));
    }
}
```

- `@CommandHandler(value = "name")` — chat trigger. The framework strips the `/` prefix.
- `@AdminRestrictedCommand(provisions = {...})` — optional gate. Player must hold at least one matching provision (or any tier that *satisfies* it — `ADMIN` covers `MODERATOR`/`EDITOR`/`PLAYER`, but `DEMO` is a flag and only matches itself).
- The method signature must be `(RealmManagerServer, Player, ServerCommandMessage)` and return `void`.
- Throw `IllegalArgumentException` for usage errors — the dispatcher converts thrown exceptions into a `CommandPacket` error response sent back to the caller.
- Use `mgr.enqueueServerPacket(target, TextPacket.from("SYSTEM", target.getName(), msg))` to print success messages to the player's chat.

The command catalog (printed by `/help`) is rendered from `COMMAND_DESCRIPTIONS`, populated automatically from the annotation's `description`.

### 4. Enemy Scripts

Enemy scripts add custom behavior to specific `enemyId`s — anything declarative attack patterns can't express. Examples in-tree: `Enemy67Script` (Vault Healer that heals nearby players), decoy AI, etc.

```java
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.script.EnemyScriptBase;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

public class Enemy999Script extends EnemyScriptBase {

    public Enemy999Script(RealmManagerServer mgr) { super(mgr); }

    @Override
    public int getTargetEnemyId() { return 999; }

    @Override
    public void attack(Realm realm, Enemy self, Player target) throws Exception {
        // Custom attack: e.g. AOE heal on every other tick, projectile burst on the rest
        if ((self.getId() & 1) == 0) {
            // Heal nearby allies, broadcast a CreateEffectPacket, etc.
        } else {
            // Spawn a projectile pattern via this.getMgr().addProjectile(...)
        }
    }
}
```

- The classpath scanner picks up every concrete `EnemyScriptBase` subclass and registers it by `getTargetEnemyId()`.
- `attack()` is invoked by `processAttacks()` on the matching enemy whenever it'd fire its scripted attack — typically gated by JSON-defined cooldowns and HP-phase thresholds in `enemies.json`.
- `mgr.broadcastTextEffect(...)` and `mgr.enqueueServerPacketToRealm(realm, ...)` are the standard helpers for visual feedback.

### 5. Item Scripts (Useable Abilities)

Class abilities, consumables, and quest items live in the same scripting framework. Subclass `UseableItemScriptBase` for any item that needs custom logic beyond the JSON-declared effect/damage.

```java
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.script.item.UseableItemScriptBase;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

public class MyAbilityScript extends UseableItemScriptBase {
    private static final int MIN_ID = 500, MAX_ID = 506; // tiered T0–T6

    public MyAbilityScript(RealmManagerServer mgr) { super(mgr); }

    @Override public int getTargetItemId() { return MIN_ID; }
    @Override public boolean handles(int itemId) { return itemId >= MIN_ID && itemId <= MAX_ID; }

    @Override
    public void invokeUseItem(Realm realm, Player player, GameItem item) {
        // Inventory-slot use (right-click on a consumable). Optional.
    }

    @Override
    public void invokeItemAbility(Realm realm, Player player, GameItem abilityItem, Vector2f cursor) {
        // Click-to-cast ability (right-click in-game). cursor is the world-space target.
        // Read tier from itemId or abilityItem.getTier(); apply effects, spawn projectiles,
        // broadcast a CreateEffectPacket for the visual.
    }
}
```

- Override `handles(int)` if a single script class covers a tier range or a non-contiguous set of item ids; otherwise the default match-by-`getTargetItemId()` works.
- Both `invokeItemAbility` overloads exist — the position-aware one is preferred (the framework calls it during ability use). The position-less overload exists for backwards compatibility.
- `RealmManagerServer.useAbility` already handles the **cooldown gate**, **MP cost**, **TELEPORT effect**, **standard projectile spawn** (if the item declares damage + projectileGroupId), and **self-effect application** before delegating to your script — so scripts only need to layer custom logic on top.

### 6. Realm Decorators

Realm decorators run **once per realm** at creation, after random enemy spawn but before set-piece placement. Use them for static-map post-processing — e.g. spawning the nexus's healers and decorations, hand-placing portals in a hub map, or adding cinematic NPCs to a boss arena.

```java
import com.openrealm.game.tile.decorators.RealmDecoratorBase;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

public class Nexus0Decorator extends RealmDecoratorBase {
    public Nexus0Decorator(RealmManagerServer mgr) { super(mgr); }

    @Override
    public Integer getTargetMapId() { return 29; }   // mapId from maps.json

    @Override
    public void decorate(Realm realm) {
        // Spawn a permanent NPC, place a custom portal, paint extra tiles, etc.
    }
}
```

- Classpath scanner picks up every `RealmDecoratorBase` subclass and registers it by `getTargetMapId()`.
- `tryDecorate(realm)` runs in `addRealm()` after the realm is created. It looks up the matching decorator by mapId.
- For terrain-generated maps, **terrain decoration** (set-piece stamping) is a separate pipeline — see Terrain Decoration below.

### 7. Terrain Decoration & Set Pieces

Procedural maps go through a terrain pipeline driven by `terrains.json` (referenced from `maps.json` via `terrainId`). Each terrain entry declares:

- **Zones** — biome regions (Beach, Grasslands, Highlands, Summit) with their own difficulty multipliers, tile palettes, and enemy groups.
- **Enemy groups** — pools of `enemyId`s that random spawning picks from per zone. Keep boss enemies OUT of these pools (they're spawned by the realm-event system instead).
- **Set pieces** — multi-tile structures (Stone Circle, Ancient Ruins, Watchtower Compound, etc.) defined in `setpieces.json` and stamped by `Realm.placeSetPieces()` during generation. Set pieces stamp BOTH the floor layer AND the collision layer; on event completion, `Realm.saveTerrainAt()` / `restoreTerrainAt()` snapshot/restore the original tiles so the world heals.

Set pieces look like this in JSON:

```json
{
    "setPieceId": 6,
    "name": "Stone Circle",
    "width": 11,
    "height": 11,
    "floorTileId": 5,
    "collision": [[0,0,1,1,1,...], [0,1,0,0,0,...], ...]
}
```

To author a new set piece, add the entry to `setpieces.json`, reference it from the terrain's `setPieces` array, and (optionally) point a `RealmEventModel.setPieceId` at it so a realm event will stamp it before spawning the boss inside.

### 8. Realm Events

Realm events are timed boss encounters with set-piece arenas, HP-thresholded minion waves, announce/defeat/timeout messages, and minimap pins. Defined entirely in `realm-events.json`:

```json
{
    "eventId": 1,
    "name": "Cube God",
    "announceMessage": "A Cube God has materialized in the %s!",
    "defeatMessage": "The Cube God has been shattered!",
    "timeoutMessage": "The Cube God has phased out of existence...",
    "bossEnemyId": 1,
    "eventMultiplier": 8,
    "setPieceId": 0,
    "allowedZones": ["grasslands", "highlands", "summit"],
    "durationSeconds": 300,
    "minionWaves": [
        { "triggerHpPercent": 0.80, "enemyId": 12, "count": 6, "eventMultiplier": 2, "offset": 128 },
        { "triggerHpPercent": 0.40, "enemyId":  9, "count": 4, "eventMultiplier": 3, "offset": 160 }
    ]
}
```

- **`bossEnemyId`** — id from `enemies.json`. The boss should NOT be in any `terrains.json` enemy group; otherwise it'll spawn naturally and the event becomes redundant.
- **`eventMultiplier`** — multiplies HP and difficulty (and thus damage). `8` = 8× a normal-zone spawn.
- **`setPieceId`** — set piece stamped at the spawn point. The terrain is saved before stamping and restored on completion.
- **`allowedZones`** — restricts where the event can roll. The scheduler retries up to 200 times to find a valid position in an allowed zone.
- **`durationSeconds`** — kill-timer. If the boss isn't dead in time, the event times out, the boss + minions despawn, terrain restores, and the timeout message fires.
- **`minionWaves`** — fire when the boss's HP drops below `triggerHpPercent`. Each wave spawns `count` minions in a ring at `offset` pixels. Each minion gets `eventMultiplier` × zone difficulty.

The `RealmOverseer` (one per non-static realm) handles:
- Periodic event rolling (`EVENT_CHECK_INTERVAL_TICKS = 6400` = ~100s, `REALM_EVENT_SPAWN_CHANCE = 0.15`).
- `spawnRealmEvent(model)` — picks a position, stamps the set piece, spawns the boss with multipliers, broadcasts the announce taunt + immediate minimap marker.
- `processActiveEvents()` — every tick checks boss-HP for wave triggers, checks expiration, and re-broadcasts the marker every ~3s so newly-joined players see the pin.
- `completeRealmEvent` / `timeoutRealmEvent` — clean up minions, restore terrain, broadcast the appropriate message + minimap REMOVE.

To trigger an event manually as an admin, use `/event {eventId}` from chat.

#### Authoring a new event

1. Add the boss enemy to `enemies.json` with multi-phase attacks.
2. **Remove the boss from any terrain `enemyGroups`** — events should be the only spawn path.
3. Add a set piece to `setpieces.json` (or reuse an existing one).
4. Add the event entry to `realm-events.json` with the values above.
5. Restart the data service. The game server picks up the new event on next deploy via the `/game-data/realm-events.json` HTTP fetch.

### 9. Status Effects & Visual FX

`CreateEffectPacket` carries a typed visual to the client; the renderer dispatches on `effectType` to one of ~14 pre-built visual cases (heal radius, vampirism, stasis field, chain lightning, poison cloud, smoke poof, wizard burst, knight shockwave, warrior buff, ninja dash, paladin seal, trap throw/placed/trigger). Tier byte (0-6) is on the wire — any tiered effect uses the `TIER_COLORS` palette to recolour itself.

Send an effect:

```java
mgr.enqueueServerPacketToRealm(realm, CreateEffectPacket.aoeEffect(
    CreateEffectPacket.EFFECT_HEAL_RADIUS,
    centerX, centerY,
    /* radius */ 224.0f,
    /* duration ms */ (short) 1500,
    /* tier */ (byte) 4));
```

Use `lineEffect(...)` instead of `aoeEffect(...)` for visuals that need a directional axis (ninja dash, knight thrust, chain lightning, trap throw, poison throw).

To add a new effect type:
1. Add a `EFFECT_*` constant to `CreateEffectPacket` (next free byte).
2. Add a `case N:` to the `switch (fx.type)` in `renderer.js#renderVisualEffects`.
3. Emit it from your script with `aoeEffect` or `lineEffect`.

---

## Project Layout

```
openrealm/
├── src/main/java/com/openrealm/
│   ├── account/dto/         AccountDto, AccountProvision (auth/role model)
│   ├── game/
│   │   ├── contants/        StatusEffectType, CharacterClass, ProjectileFlag, ...
│   │   ├── data/            GameDataManager (JSON loaders + caches)
│   │   ├── entity/          Player, Enemy, Bullet, GameItem, LootContainer
│   │   ├── math/            Vector2f, Rectangle, ShortIdAllocator
│   │   ├── model/           POJO models for character classes, terrains, events, ...
│   │   ├── script/          EnemyScriptBase + Enemy*Script implementations
│   │   ├── script/item/     UseableItemScriptBase + tiered ability scripts
│   │   └── tile/            TileManager, TileMap, decorators
│   ├── net/
│   │   ├── core/            IOService, PacketId, SerializableField, nettypes
│   │   ├── client/packet/   server→client packets (LoadPacket, UpdatePacket, ...)
│   │   ├── server/packet/   client→server packets (PlayerMove, PlayerShoot, ...)
│   │   ├── realm/           RealmManagerServer, Realm, RealmOverseer
│   │   └── server/          ServerCommandHandler, ServerGameLogic, ServerItemHelper
│   └── util/                Annotations (@PacketHandlerServer, @CommandHandler, ...)
└── src/main/resources/
    └── logback.xml
```

The data service (`openrealm-data`) ships the JSON, sprite sheets, and the entire web client + editor.

---

## License

Copyright (c) 2024-2026 Robert Usey. All rights reserved.

This software and associated documentation files (the "Software") are the exclusive property of Robert Usey. No part of this Software may be copied, modified, merged, published, distributed, sublicensed, sold, or otherwise made available to any third party, in whole or in part, in any form or by any means, without the prior express written permission of the copyright holder.

Unauthorized reproduction or distribution of this Software, or any portion of it, may result in severe civil and criminal penalties, and will be prosecuted to the maximum extent possible under the law.

For licensing inquiries, contact: **ruusey@gmail.com**
