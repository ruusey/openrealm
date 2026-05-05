# Enchantment, Rarity & Tier System — Design

> Status: design draft, not yet implemented. Decisions captured 2026-05-05.

## TL;DR

Most of the foundation is already in place. The current codebase ships:

- A working **stat-enchantment crystal system** (Forge UI, pixel-painting, persistence, wire) capped at 5 per item — and enchantments **do** apply to player stats (`Player.java:~280` switches on `statId` to add deltas).
- A `tier` byte on `GameItem` (0–15+, −1 untiered) — but this is **power tier** (used for loot bag color and class gating), not the new "rarity" axis.
- 8 stats (VIT, WIS, HP, MP, ATT, DEF, SPD, DEX), per-instance item state via `clone()` + `uid`, full DTO/wire/persistence plumbing for enchantments, and 33 packet types in the web client.

**The new system is largely additive**: add a `rarity` dimension separate from `tier`, add an `attributeModifier` field (random affix), and generalize `Enchantment` from "stat-delta only" to a typed **gem effect** that can scale projectiles/abilities/effects. The Forge already owns the UX surface for socketing, and `FORGE_DISENCHANT` (packet id 31) is already wired for removal.

---

## 1. Existing infrastructure map

### Item core
- `com.openrealm.game.entity.item.GameItem` — canonical model (per-instance via `clone()`, `uid`, `stackCount`, `enchantments`).
- `com.openrealm.game.entity.item.Stats` — flat additive struct over 8 stats; `concat()` / `subtract()` are the stacking primitives.
- `com.openrealm.game.entity.item.Damage` — weapon `min`/`max` + `projectileGroupId`.
- `com.openrealm.game.entity.item.Effect` — single status effect with `mpCost`, `cooldownDuration`, `duration`, `self`.
- `com.openrealm.game.model.Projectile` + `com.openrealm.game.entity.Bullet` — projectile prototypes (damage, speed, range, size, flags, on-hit `ProjectileEffect`s) and runtime instances.

### Existing enchantment system
- `Enchantment` (5 fields: `statId`, `deltaValue`, `pixelX`, `pixelY`, `pixelColor`) — stat-only.
- `ServerForgeHelper` — full handler: `MAX_ENCHANTMENTS_PER_ITEM = 5`, `ENCHANT_ESSENCE_COST = 50`, `SHARDS_PER_CRYSTAL = 10`, `STAT_COLORS[]`, `HP_MP_ENCHANT_DELTA = 5` (HP/MP otherwise dwarf single-point deltas).
- Application path: `Player.java:~277` walks `equipment[]`, calls `stats.concat(item.stats)`, then iterates `item.enchantments` and switches on `statId` to add `deltaValue`. **This is the load-bearing line for stat enchantment math.**
- DTO/wire: `EnchantmentDto` ↔ `NetEnchantment` ↔ `Enchantment`. Persisted via `GameItemRefDto.enchantments`.
- Web client: Forge modal (`forge.js`, 479 lines) is fully implemented — drag target/crystal/essence, click pixel, dispatch `FORGE_ENCHANT` (id 30) / `FORGE_DISENCHANT` (id 31).

### Tier (existing)
- `GameItem.tier` byte. Drives `LootContainer.determineTier()` (loot-bag color: WHITE / PURPLE / CYAN / BROWN). `GlobalConstants.CYAN_BAG_MIN_*_TIER` constants gate cyan bags. Items in `game-items.json` have `tier` 0–15 with −1 for untiered consumables/essence.

### Loot
- `LootTableModel.getLootDrop()` — per-enemy probability roll, supports `group:N`, `shard:N[:min-max]`, `essence:N`, `essenceany`, direct `item:N`. Returns cloned templates. **This is the single hook point** for layering rarity-rolling and modifier-rolling.

### Persistence and admin
- `ServerCommandHandler` already has `/item {id} [count]` (`@AdminRestrictedCommand`). Trivial to extend with `/rarity`, `/modifier`, `/gem`.
- `mgr.persistPlayerAsync(player)` is the persistence trigger after inventory mutations.

---

## 2. Gaps the new design exposes

| Need | Today | Gap |
|---|---|---|
| 6 rarities (common→mythical) | only `tier` (power-bucket) | **No rarity field**; needs to be a separate axis |
| Slot count by rarity (1→6) | flat 5 via `MAX_ENCHANTMENTS_PER_ITEM` | constant must become per-instance via rarity lookup |
| Random attribute modifier per drop | not present | new `attributeModifiers` field + roll on drop |
| Gems beyond stat-delta (proj count, scaling, on-hit) | `Enchantment` is stat-only | enchantment model needs a typed effect, not just `(statId, delta)` |
| Gem items in catalog | crystals stop at one stat-delta each | new "gem" item category or extended `crystal` with effect kind |
| Visual rarity in UI | tier-color CSS exists | needs separate rarity-color and slot-count display |

---

## 3. Design

### 3.1 Rarity as a new dimension (decoupled from `tier`)

Add an enum and per-instance field on `GameItem`:

```java
public enum Rarity {
    COMMON(1),     // 0
    UNCOMMON(2),   // 1
    RARE(3),       // 2
    EPIC(4),       // 3
    LEGENDARY(5),  // 4
    MYTHICAL(6);   // 5
    public final int gemSlots;
}
```

- New field: `private byte rarity = 0;` on `GameItem` (default COMMON).
- **Keep `tier` unchanged** — it still drives loot-bag color and class gating. Rarity is orthogonal.
- Web/native client get a rarity-color (gray / green / blue / purple / orange / red) layered on top of the tier badge in tooltips.

### 3.2 Random attribute modifier (Diablo-style affix)

A small ±delta on one or two random stats, rolled at drop time:

```java
@Data class AttributeModifier {
    private byte statId;       // 0..7
    private byte deltaValue;   // signed: −3..+3 typical, scaled by rarity
}
private List<AttributeModifier> attributeModifiers; // 0..2 entries on GameItem
```

- Rolled once in `LootTableModel.getLootDrop()` after clone, persisted via DTO.
- Naming convention: prefix/suffix appears in tooltip ("Brutal Short Bow of the Ox" → `+2 ATT`, `+1 VIT`).
- Magnitude scales with rarity: COMMON ±1, MYTHICAL ±3 (configurable table).
- Applied in the same `Player.java:~277` loop alongside `concat(stats)` and the enchantment switch.

### 3.3 Generalized enchantment / gem model

The current `Enchantment` only knows `(statId, delta)`. To support "wisdom scaling," "speed scaling," projectile changes, and behavior swaps, refactor:

```java
public enum GemEffectType {
    STAT_DELTA,           // +N to one of 8 stats (today's behavior)
    STAT_SCALE,           // ×% to one stat (e.g., +10% wisdom)
    PROJECTILE_COUNT,     // +N projectiles
    PROJECTILE_DAMAGE,    // ×% damage
    ON_HIT_EFFECT,        // apply a StatusEffectType for ms
    LIFESTEAL,            // % of damage healed
    CRIT_CHANCE           // % crit
}

@Data class Enchantment {  // extended
    private byte effectType;  // GemEffectType ordinal
    private byte param1;      // statId, StatusEffectType id, etc.
    private short magnitude;  // delta or scaled value (×100 for percentages)
    private int durationMs;   // for ON_HIT_EFFECT
    private byte pixelX, pixelY;
    private int pixelColor;
}
```

- **Backward compatibility**: `effectType=STAT_DELTA, param1=statId, magnitude=delta` reproduces today's behavior 1:1. Existing serialized enchantments deserialize unchanged if we default `effectType=STAT_DELTA` when absent.
- Apply pipeline split into two phases:
  - **Stat phase** (already in `Player.java`): handle STAT_DELTA + STAT_SCALE + AttributeModifier.
  - **Combat phase** (new): hook into ability firing / projectile spawn / damage roll. The cleanest insertion is `Player.useAbility()` and the projectile-spawn site — read `equippedItem.enchantments`, apply scalars to `Bullet` fields (count, damage, crit, lifesteal) before send.

### 3.4 Gem items

Add gem item IDs above 821; give them a new `category = "gem"` and a `gemEffectType` byte mirroring the enum. The Forge handler grows a branch: if `crystal.category == "gem"`, build the new `Enchantment` from gem fields instead of `(STAT_DELTA, statId, +1)`. Crystals 808–815 stay as the entry-level "stat-delta gems."

**Gem acquisition: both drops and crafting.**
- Gems can drop directly from enemies (rare, with rarity scaling like equipment — see §3.6).
- Gems can also be crafted from shards/essence using the existing Forge tile. The shard → crystal recipe (10 shards → 1 crystal, `SHARDS_PER_CRYSTAL`) generalizes to gems: each gem effect type defines its own recipe (e.g., 25 shards + 5 essence → CRIT_CHANCE gem).

**Removal: existing `FORGE_DISENCHANT` (packet 31) already strips all enchantments from a target.** Keep this as the sole removal path for now — no reroll stone in v1. Optionally extend with single-gem removal in P5 if players ask for it.

### 3.5 Slot count by rarity

Replace `MAX_ENCHANTMENTS_PER_ITEM = 5` with a lookup:

```java
public static int maxEnchants(Rarity r) { return r.gemSlots; }
```

- `ServerForgeHelper.handleForgeEnchant` reads `Rarity.values()[target.getRarity()].gemSlots` instead of the constant.

**Migration for legacy items with >1 enchantment** (decision: bump rarity to fit existing slot count):
- On player load, if `item.rarity == COMMON` and `item.enchantments.size() > 1`, set `item.rarity = Rarity.values()[item.enchantments.size() - 1]`. So a legacy item with 4 enchantments becomes EPIC, with 5 becomes LEGENDARY. No enchantments are stripped, no essence is refunded.

### 3.6 Drop-time rolling — enemy-driven rarity tables

The user's call: rarity drop chances scale with enemy tier / zone difficulty, controlled at the enemy level.

**Implementation:**
1. Add a `rarityWeights` field to enemy definitions (in the data repo's enemy JSON, parallel to `LootTableModel`):
   ```json
   "rarityWeights": [60, 25, 10, 4, 0.9, 0.1]   // common..mythical
   ```
2. If absent, default to a tier-derived table:
   ```
   tier 0–2  enemies: 70 / 25 / 4 / 1 / 0 / 0
   tier 3–5  enemies: 50 / 30 / 15 / 4 / 1 / 0
   tier 6–8  enemies: 30 / 30 / 25 / 10 / 4 / 1
   tier 9–11 enemies: 15 / 25 / 30 / 20 / 8 / 2
   tier 12+  enemies: 5  / 15 / 30 / 30 / 15 / 5
   ```
3. Optionally apply a zone/realm multiplier (e.g., "Nest" or "endgame realm" shifts the curve right by 1 tier).

**Wired in `LootTableModel.getLootDrop()`** after `template.clone()`:

```java
GameItem inst = template.clone();
if (inst.getTargetSlot() >= 0 && inst.getTargetSlot() <= 3) {  // equipment only
    inst.setRarity(rollRarity(enemy.getRarityWeights(), enemy.getTier(), realm.getModifier()));
    inst.setAttributeModifiers(rollAttributeModifiers(inst.getRarity()));
}
return inst;
```

Loot-bag tier (`LootContainer.determineTier`) can additionally consider `rarity` so a mythical-rolled white drops in a cyan bag.

---

## 4. Concrete change list

### Server (`openrealm/`)
1. **New**: `game/entity/item/Rarity.java` (enum), `game/entity/item/AttributeModifier.java`, `game/entity/item/GemEffectType.java`.
2. **Extend** `GameItem`: add `rarity` (byte), `attributeModifiers` (List). Update `clone()`, `toGameItemRefDto()`, `fromGameItemRef()`.
3. **Extend** `Enchantment`: add `effectType`, `param1`, `magnitude`, `durationMs`. Default values keep wire compat.
4. **Extend** DTOs: `EnchantmentDto` (new fields, all `@JsonInclude(NON_NULL)`), `GameItemRefDto` (rarity, modifiers).
5. **Extend** wire: `NetGameItem` gets `rarity` + modifiers; `NetEnchantment` gets the new fields. Add fields with new `order=` values at the end (don't renumber existing) to preserve binary compat with web client until it's updated.
6. **Refactor** `Player.java:~277`: split the loop into `applyStatLayer()` (today's logic + attributeModifiers + STAT_SCALE) and `collectCombatModifiers()` (returns a struct used at projectile-spawn time).
7. **Refactor** `ServerForgeHelper`: replace `MAX_ENCHANTMENTS_PER_ITEM` with `Rarity.gemSlots` lookup; add gem-category branch in `handleForgeEnchant`. Add gem-crafting recipes.
8. **New** `LootRarityRoller` utility, called from `LootTableModel.getLootDrop()`.
9. **Extend** `ServerCommandHandler`: `/rarity {slot} {rarity}`, `/modifier {slot} {statId} {delta}`, `/gem {slot} {gemItemId} [pixelX] [pixelY]`.
10. **Hook** projectile-spawn site (`Player.useAbility` → `Bullet` construction) to read combat enchantments and apply count/damage/crit/lifesteal scalars.
11. **Migration** in player-load path: bump legacy rarity to fit existing enchantment count.
12. **Extend** enemy model with `rarityWeights` field.

### Data (`openrealm-data/`)
1. **Extend** `game-items.json` schema: add `rarity` field on equipment templates (defaults to `0`/COMMON if absent). Add new gem items (id 821+) with `category: "gem"`, `gemEffectType`, etc.
2. **Extend** enemy JSON: add optional `rarityWeights` array.
3. **Add** REST endpoint `/api/gem` if we want the web editor to enumerate gems separately.
4. **Web client `forge.js`**: add gem-item recognition; when crystal slot holds a gem, display its effect description. Update `MAX_ENCHANTMENTS` to read from the target item's rarity-derived slot count. Add gem-crafting UI tab.
5. **Web client `main.js` / `game.js`**: tooltip rarity color, attribute-modifier section ("of the Ox: +2 ATT"), gem-effect descriptions in the enchantment breakdown.
6. **Web client `codec.js`**: extend `NetGameItem` and `NetEnchantment` readers/writers with new fields. Order must match server `@SerializableField`.

### Native client (`openrealm-client-legacy/`)
- Mirror the protocol additions (`NetGameItem`, `NetEnchantment`) per the duplicated-protocol convention.
- Inventory tooltip + rarity coloring; the Forge UI in the native client is lower priority since the web client is the canonical UI.

---

## 5. Phasing

| Phase | Scope | Why |
|---|---|---|
| **P1 — Rarity scaffolding** | `Rarity` enum, `GameItem.rarity` field, default COMMON, persistence + wire (additive only), tooltip rarity color (web), `/rarity` admin command, `LootRarityRoller` rolling on drop using enemy `rarityWeights`. | Smallest end-to-end slice that makes rarity visible and rollable. Slot count still flat. |
| **P2 — Slot count by rarity** | `MAX_ENCHANTMENTS_PER_ITEM` → `rarity.gemSlots`, web Forge respects it, migration for legacy items with >1 enchantment (auto-bump rarity). | Unlocks the rarity-utility (more slots). |
| **P3 — Attribute modifiers** | `AttributeModifier` model, roll on drop, apply in `Player.java`, tooltip line, `/modifier` admin. | Pure stat-side feature — easy to ship and balance. |
| **P4 — Gem catalog (typed effects)** | `GemEffectType` enum (7 effects), extended `Enchantment` fields, gem items 821+, Forge gem branch, gem crafting recipes, Player combat-modifier collection. | Biggest design surface; do it after P1–P3 so the framing is settled. |
| **P5 — Combat-time enchantments** | Projectile count / damage / on-hit effect / lifesteal / crit wired into ability firing. | Where the design gets complex — needs a stable enchantment API from P4. |
| **P6 — Native client parity** | Mirror protocol + tooltips into `openrealm-client-legacy/`. | Web client first; native catches up. |

---

## 6. Decisions (resolved 2026-05-05)

1. **Rarity scaling with enemy tier**: yes, rarity drop weights are defined per-enemy and scale with enemy tier / zone difficulty. Higher-tier enemies have heavier weights on rare/epic/legendary/mythical. Default tier-based table provided in §3.6.
2. **Migration for legacy >1-enchantment items**: bump rarity to fit existing slot count (option a). No essence refund, no enchantments stripped.
3. **Gem acquisition**: both — gems drop directly off enemies (rare), and gems can be crafted from shards + essence at the Forge tile.
4. **Gem catalog for v1**: STAT_DELTA, STAT_SCALE, PROJECTILE_COUNT, PROJECTILE_DAMAGE, ON_HIT_EFFECT, LIFESTEAL, CRIT_CHANCE.
5. **Reroll stone**: not in v1. The existing `FORGE_DISENCHANT` (packet 31) handles full removal of enchantments. Single-gem removal can be added later if needed.
6. **Rarity drop control point**: at the enemy level — each enemy carries a `rarityWeights` array (defaulted from tier if not specified), and the `LootRarityRoller` reads it at drop time.
