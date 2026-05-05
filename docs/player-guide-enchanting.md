# OpenRealm Player Guide — Rarity, Enchantments & Gems

Everything you need to know about your gear: what the colored borders mean, how to forge new effects onto your equipment, where to find the materials, and how to identify a great drop at a glance.

---

## The Tooltip — Reading Your Gear

When you hover an item, the tooltip is your single source of truth. Read top to bottom:

1. **Item Name** — colored by rarity (see below).
2. **Subtitle line** — `Rarity · Tier · Class`. Rarity comes first because it controls how much you can enchant the item.
3. **Description** — flavor text + ability description.
4. **Damage / Stats** — the item's base contribution. Same for every copy of this item.
5. **Affix line** — a random roll *only* on this specific drop. Looks like:
   `Affix:  +2 ATT · +1 VIT`  (positive rolls in green, negative in red).
6. **Gem Effect** — only on **gem items** (e.g. "Multishot Gem"). Tells you what the gem will do when you forge it into something.
7. **Forged (N/M)** — your enchantment block. `N` is how many gems are currently slotted; `M` is the max for this item's rarity. Each gem appears on its own line with a colored dot and a plain-language effect like `+1 ATT`, `+10% Wisdom Scaling`, `Slowed on hit (1.5s)`, `5% Lifesteal`.

In the **inventory**, every non-common item also has a colored ring around its slot — same color as the rarity name. **Mythical items pulse red** so you can spot them across a full inventory in one glance.

---

## Rarity — The Six Tiers

Every piece of equipment that drops is rolled into one of six rarities. Rarity is **separate from item tier** — tier still tells you the raw power class (e.g. T13 weapon vs T6 weapon), and rarity tells you **how many gems you can forge into it**.

| Rarity      | Color   | Gem Slots | Rough drop chance (mid-tier enemy) |
|-------------|---------|-----------|------------------------------------|
| Common      | gray    | 1         | ~50%                               |
| Uncommon    | green   | 2         | ~30%                               |
| Rare        | blue    | 3         | ~15%                               |
| Epic        | purple  | 4         | ~4%                                |
| Legendary   | orange  | 5         | ~1%                                |
| Mythical    | red     | 6         | < 0.1%                             |

A Mythical T6 staff and a Common T13 staff have the **same base damage and stats** — but the Mythical can hold six gems on top, while the Common can only hold one. Rarity is roughly equivalent to "how customizable is this drop."

### How rarity is rolled

Rarity is rolled **once at drop time** and never changes. Higher-tier (tougher) enemies and harder zones skew the roll toward higher rarities. Killing T0 grasslands enemies will mostly drop Commons and Uncommons; clearing a T12 dungeon makes Legendary and Mythical drops realistic targets.

If you find the same item again, you'll get a fresh re-roll — so a hunt for a Mythical version of your favorite sword is a real activity.

---

## Attribute Modifiers — The "Affix" Roll

Every equipment drop also rolls **0–2 random attribute modifiers**: small per-instance stat tweaks layered on top of the item's base stats. Two copies of the same item can play very differently depending on what affixes they rolled.

- **How many** scales with rarity: Common rolls 0–1, Uncommon 1, Rare 1, Epic 1–2, Legendary 2, Mythical 2.
- **Magnitude** scales with rarity: ±1 on Commons, up to ±5 on Mythicals. HP and MP rolls are scaled up 5× since their base values are larger.
- **Sign**: Common drops have a 35% chance to roll *negative*; Mythical never rolls negative. Don't be surprised by an off-stat penalty on early drops — that's intentional.
- **Stacks with everything else**. Affixes show up in the tooltip's `Affix:` line, separately from base stats and forged gems.

Example: a Rare ring might roll `Affix: +3 ATT, -1 VIT` — flavor that turns a generic ring into a per-character pick.

---

## Enchantments & Gems — Forging Effects

Once you have a piece of equipment, you can forge **gems** into it at the **Forge** to permanently add effects. Each gem fills one slot, capped by the item's rarity.

### What's the difference between a Crystal and a Gem?

- **Crystals** (the original system) only add a flat **+1 to a single stat** (HP/MP get +5 instead, since their scale is bigger). Eight crystals exist — one per stat: Vit / Wis / HP / MP / Att / Def / Spd / Dex. They're great early-game and remain the cheapest way to fill slots.
- **Gems** are typed effects with much richer behavior. Below is the v1 catalog. More are planned.

### Gem Catalog (v1)

| Gem               | Effect                                                        | Stat affected           |
|-------------------|---------------------------------------------------------------|-------------------------|
| Wisdom Scaling    | +10% to your Wisdom (multiplicative)                          | WIS                     |
| Swift Scaling     | +10% to your Speed (multiplicative)                           | SPD                     |
| Multishot         | +1 projectile per shot                                        | weapon/ability output   |
| Crushing          | +15% projectile damage                                        | weapon/ability output   |
| Slowing           | Inflicts SLOWED on enemies for 1.5s on hit                    | on-hit effect           |
| Vampiric          | Heals you for 5% of damage dealt                              | sustain                 |
| Brutal            | 10% chance per shot to deal **double damage** (crit)          | weapon/ability output   |

**Stat-modifying gems** (Crystals, Wisdom/Swift Scaling) take effect the moment they're forged in. **Combat gems** (Multishot, Crushing, Slowing, Vampiric, Brutal) only work when forged into a **weapon (slot 0)** or **ability (slot 1)** — there's no point putting Multishot on a ring.

You can stack gems of the same type. Two Crushing gems → +30% damage. Two Multishot → +2 projectiles. Three Brutal → 30% crit.

### Slot Limits Recap

The number of gems an item can hold equals its rarity number: 1 / 2 / 3 / 4 / 5 / 6. You **cannot upgrade an item's rarity** — if you want six slots, you need to find a Mythical drop. This is the entire point of chasing high-rarity gear.

---

## The Forge — How to Craft

The Forge is a tile in the world. Walk near it and **interact** to open the Forge modal.

You'll see three drop zones at the top of the modal:

1. **Target slot** — drag the piece of equipment you want to enchant here.
2. **Crystal / Gem slot** — drag the crystal *or* gem you want to apply.
3. **Essence slot** — drag the matching essence stack here.

Below those is the **pixel canvas** — a magnified view of your item's sprite. Click any pixel to choose where the gem will be "painted" onto the icon. Each gem you forge sticks a colored pixel on your sprite, so a heavily-forged item visibly stands out.

Then click **Forge**. The cost is deducted, the enchantment is added, and the inventory + tooltip update immediately.

### Forge Costs

Every forge takes:
- **1 Crystal or Gem** (consumed)
- **50 Essence** of the right type (consumed) — *or* **1 Universal Essence**.

The essence type must match the item's slot:
- **Weapon Essence** for slot 0 (weapons)
- **Ability Essence** for slot 1 (abilities, spells, books, scepters)
- **Armor Essence** for slot 2
- **Ring Essence** for slot 3
- **Universal Essence** substitutes for 50 of any essence type. One Universal = one full forge.

Two more rules you'll trip over once:
- Each pixel can only be enchanted **once** — pick a different pixel if the spot you wanted is already painted.
- The forge will refuse if the item is already at its rarity slot cap. The error message tells you the cap and the rarity ("Item at rarity cap (3 slots for Rare)").

### Disenchant (Removing Gems)

Inside the Forge modal there's a **Disenchant All** button. It strips every gem off the target item — there is **no refund**. Use it to reset a poorly-planned build before starting fresh. There's no single-gem removal in v1, so plan your forges deliberately.

### Crafting Crystals from Shards

Crystals don't drop directly. You craft them from **shards**:

- Pick up a stack of stat shards (e.g. 10× Vit Shard).
- Right-click / interact with the stack to consume **10 shards** → produce **1 Vit Crystal** of the matching stat.
- Excess shards stay in the original stack; the new crystal lands in the first empty inventory slot.

Eight shard types, one per stat, mirroring the eight crystals.

---

## Where to Find Things

Everything in the system drops from killing enemies, with quality scaling by enemy tier and zone difficulty. Higher-tier enemies = better drops in every dimension — higher item rarities, more gem availability, and more shards/essence per drop.

| Item                   | Source                                                                 |
|------------------------|------------------------------------------------------------------------|
| **Equipment** (weapon, ability, armor, ring) | Standard enemy drops. Rarity rolled at drop time. |
| **Stat Shards** (800–807) | Common drops in stacks. Used to craft Crystals.                     |
| **Stat Crystals** (808–815) | Crafted: 10 shards → 1 crystal. Or rare direct drops.              |
| **Essences** (816–819, slot-typed) | Drop in stacks of up to 50. Match the slot you want to forge.   |
| **Universal Essence** (820) | Rare drop. One = one full forge of any type.                        |
| **Gems** (830–836)     | **Both**: rare direct drops from enemies *and* craftable from shards + essence at the Forge (recipes per gem). Rarer than Crystals — they're the upgrade target. |

Rarity-rolled equipment + better materials are the reward for fighting tougher enemies, so the natural progression is: clear low-tier zones for shards/crystals → push into mid-tier dungeons for gems and Rare/Epic gear → endgame for Legendary/Mythical drops with full 5–6 slot builds.

---

## Equipping Items

Inventory layout:
- **Slots 0–3** (top row, equipped): **Weapon, Ability, Armor, Ring**.
- **Slots 4–19** (backpack): everything you're carrying but not wearing.

**To equip**, drag from the backpack into the matching equipment slot — the slot accepts only the right type (you can't drop a sword in the ring slot). Dragging an existing equipped item back into the backpack swaps in the new one.

The moment you equip a piece:
- Its base stats apply.
- Its random affix applies.
- All forged gems apply (stat-mod gems instantly, combat gems on your next shot/ability).

Unequipping reverses all of those — your computed stats refresh in real time.

---

## Quick Cheat Sheet

- **Color of name = rarity.** Slots = rarity number. Mythical pulses.
- **Forge = tile in the world.** Drag target + crystal/gem + 50 essence.
- **One pixel per gem.** Each forge costs one crystal/gem and 50 essence (or 1 universal essence).
- **Crystals = +1 stat. Gems = real effects.**
- **Combat gems** (Multishot, Crushing, Slowing, Vampiric, Brutal) belong on **weapon or ability**.
- **Stat gems** (Crystals, Wisdom Scaling, Swift Scaling) work in any slot.
- **Affix line** is a roll unique to that drop — chase good affixes, not just rarities.
- **Disenchant** is "strip everything, no refund." Plan forges before clicking.
- **Higher-tier enemies = better rarity rolls + more gems** — push tougher content for build-relevant drops.

Have fun. The first time you see a Mythical drop with `+5 ATT` affixed, you'll know.
