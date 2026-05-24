package com.openrealm.game.entity;

import java.io.DataOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.openrealm.account.dto.CharacterStatsDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.GemstoneRegistry;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.metrics.PlayerMetrics;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityTree;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.net.client.packet.PlayerStatePacket;
import com.openrealm.net.entity.NetGameItemRef;
import com.openrealm.util.Tuple;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class Player extends Entity {
	private GameItem[] inventory;
	private long lastStatsTime;
	/** Wall-clock ms of the last damage taken; drives Ninja "Light On Your Feet" dodge. */
	private long lastDamageTakenMs;
	private LootContainer currentLootContainer;
	private int classId;
	private String accountUuid;
	private String characterUuid;
	private long experience;
	private Stats stats;
	private boolean headless;
	@Builder.Default
	private boolean bot = false;
	@Builder.Default
	private String chatRole = "";
	private transient boolean adminModeEnabled = true;
	private transient String storedChatRole = null;
	private transient boolean hiddenFromOthers = false;
	@Builder.Default
	private int lastInputSeq = 0;
	@Builder.Default
	private int lastProcessedInputSeq = 0;
	@Builder.Default
	private float currentVx = 0f;
	@Builder.Default
	private float currentVy = 0f;
	@Builder.Default
	private transient Queue<float[]> inputQueue = new ConcurrentLinkedQueue<>();

	public static final int MAX_CONSUMABLE_POTIONS = 6;
	public static final int HP_POTION_ITEM_ID = 296;
	public static final int MP_POTION_ITEM_ID = 297;

	// Equipment slots: 0=weapon, 1=armor, 2=gauntlets, 3=boots, 4=ring.
	// MoveItemPacket slot constants must stay in sync.
	public static final int EQUIPMENT_SLOT_COUNT = 5;
	public static final int BACKPACK_SIZE = 16;
	public static final int INVENTORY_SIZE = EQUIPMENT_SLOT_COUNT + BACKPACK_SIZE;
	@Builder.Default
	private int hpPotions = 0;
	@Builder.Default
	private int mpPotions = 0;

	@Builder.Default
	private int dyeId = 0;

	@Builder.Default
	private transient long cachedAccountFame = 0L;

	@Builder.Default
	private transient long[] abilityCooldowns = new long[4];
	@Builder.Default
	private transient CastState currentCast = null;
	@Builder.Default
	private transient PlayerMetrics metrics = new PlayerMetrics();
	@Builder.Default
	private transient int[] hotbarBindings = new int[]{0, 0, 0, 0};
	@Builder.Default
	private transient int basicAttackCounter = 0;

	@Builder.Default
	private int availableSkillPoints = 0;
	@Builder.Default
	private Map<Integer, Integer> abilitySkillPoints = new HashMap<>();

	public Player() {
		super(0, null, 0);
	}

	public Player(GameItem[] inventory, long lastStatsTime, long lastDamageTakenMs,
			LootContainer currentLootContainer, int classId,
			String accountUuid, String characterUuid, long experience, Stats stats, boolean headless, boolean bot,
			String chatRole, boolean adminModeEnabled, String storedChatRole, boolean hiddenFromOthers,
			int lastInputSeq, int lastProcessedInputSeq, float currentVx, float currentVy,
			Queue<float[]> inputQueue, int hpPotions, int mpPotions, int dyeId, long cachedAccountFame,
			long[] abilityCooldowns, CastState currentCast,
			PlayerMetrics metrics,
			int[] hotbarBindings, int basicAttackCounter,
			int availableSkillPoints, Map<Integer, Integer> abilitySkillPoints) {
		super(0, null, 0);
		this.inventory = inventory;
		this.lastStatsTime = lastStatsTime;
		this.lastDamageTakenMs = lastDamageTakenMs;
		this.currentLootContainer = currentLootContainer;
		this.classId = classId;
		this.accountUuid = accountUuid;
		this.characterUuid = characterUuid;
		this.experience = experience;
		this.stats = stats;
		this.headless = headless;
		this.bot = bot;
		this.chatRole = chatRole;
		this.adminModeEnabled = adminModeEnabled;
		this.storedChatRole = storedChatRole;
		this.hiddenFromOthers = hiddenFromOthers;
		this.lastInputSeq = lastInputSeq;
		this.lastProcessedInputSeq = lastProcessedInputSeq;
		this.currentVx = currentVx;
		this.currentVy = currentVy;
		this.inputQueue = inputQueue != null ? inputQueue : new ConcurrentLinkedQueue<>();
		this.hpPotions = hpPotions;
		this.mpPotions = mpPotions;
		this.dyeId = dyeId;
		this.cachedAccountFame = cachedAccountFame;
		this.abilityCooldowns = abilityCooldowns != null ? abilityCooldowns : new long[4];
		this.currentCast = currentCast;
		this.metrics = metrics != null ? metrics : new PlayerMetrics();
		this.hotbarBindings = hotbarBindings != null ? hotbarBindings : new int[]{0, 0, 0, 0};
		this.basicAttackCounter = basicAttackCounter;
		this.availableSkillPoints = availableSkillPoints;
		this.abilitySkillPoints = abilitySkillPoints != null ? abilitySkillPoints : new HashMap<>();
		// Re-seed hotbar bindings from class default when transient state is empty (post-login).
		boolean hotbarEmpty = true;
		for (int v : this.hotbarBindings) { if (v != 0) { hotbarEmpty = false; break; } }
		if (hotbarEmpty) {
			final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
			if (cls != null && cls.getAbilityTree() != null && cls.getAbilityTree().getDefaultHotbar() != null) {
				final int[] src = cls.getAbilityTree().getDefaultHotbar();
				for (int i = 0; i < this.hotbarBindings.length && i < src.length; i++) {
					this.hotbarBindings[i] = src[i];
				}
			}
		}
	}

	public Player(long id, Vector2f origin, int size, CharacterClass characterClass) {
		super(id, origin, size);
		this.resetEffects();
		this.resetInventory();
		this.classId = characterClass.classId;
		this.size = size;
		this.experience = 0;
		this.bounds.setWidth(this.size);
		this.bounds.setHeight(this.size);

		this.hitBounds.setWidth(this.size);
		this.hitBounds.setHeight(this.size);
		CharacterClassModel classModel = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		this.health = classModel.getBaseStats().getHp();
		this.mana = classModel.getBaseStats().getMp();

		this.stats = classModel.getBaseStats().clone();
		// Lombok @Builder.Default strips inline init in this ctor path — allocate explicitly.
		if (this.hotbarBindings == null) this.hotbarBindings = new int[]{0, 0, 0, 0};
		if (this.abilityCooldowns == null) this.abilityCooldowns = new long[4];
		if (classModel.getAbilityTree() != null && classModel.getAbilityTree().getDefaultHotbar() != null) {
			final int[] src = classModel.getAbilityTree().getDefaultHotbar();
			for (int i = 0; i < this.hotbarBindings.length && i < src.length; i++) {
				this.hotbarBindings[i] = src[i];
			}
		}
		log.info("[PLAYER-SPAWN] id={} classId={} hotbarBindings={} classPassive={}",
				this.id, this.classId, Arrays.toString(this.hotbarBindings),
				classModel.getAbilityTree() != null ? classModel.getAbilityTree().getPassive() : "(null tree)");
	}

	public void applyStats(CharacterStatsDto stats) {
		this.setExperience(stats.getXp());
		this.health = stats.getHp();
		this.mana = stats.getMp();
		this.stats.setHp(stats.getHp().shortValue());
		this.stats.setMp(stats.getMp().shortValue());
		this.stats.setDef(stats.getDef().shortValue());
		this.stats.setStr(stats.getStr().shortValue());
		this.stats.setSpd(stats.getSpd().shortValue());
		this.stats.setDex(stats.getDex().shortValue());
		this.stats.setVit(stats.getVit().shortValue());
		this.stats.setWis(stats.getWis().shortValue());
		if (stats.getHpPotions() != null) this.hpPotions = stats.getHpPotions();
		if (stats.getMpPotions() != null) this.mpPotions = stats.getMpPotions();
		if (stats.getDyeId() != null) this.dyeId = stats.getDyeId();
		this.availableSkillPoints = stats.getAvailableSkillPoints() != null
				? stats.getAvailableSkillPoints() : 0;
		this.abilitySkillPoints = stats.getAbilitySkillPoints() != null
				? new HashMap<>(stats.getAbilitySkillPoints()) : new HashMap<>();
		// One-shot backfill for chars that levelled past 2 before SP existed.
		final int currentLevel = GameDataManager.EXPERIENCE_LVLS == null
				? 0 : GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience);
		int earnedSoFar = 0;
		for (int lvl = 2; lvl <= Math.min(currentLevel, 20); lvl += 2) earnedSoFar++;
		int totalInvested = 0;
		for (Integer v : this.abilitySkillPoints.values()) totalInvested += (v == null ? 0 : v);
		final int missing = earnedSoFar - (this.availableSkillPoints + totalInvested);
		if (missing > 0) {
			this.availableSkillPoints += missing;
			log.info("[SKILL-POINTS] backfill for player {} level={} earned={} invested={} +{} SP (pool now {})",
					this.id, currentLevel, earnedSoFar, totalInvested, missing, this.availableSkillPoints);
		}
	}

	public Set<GameItemRefDto> serializeItems() {
		final Set<GameItemRefDto> res = new HashSet<>();
		for (int i = 0; i < this.inventory.length; i++) {
			GameItem item = this.inventory[i];
			if (item != null) {
				res.add(item.toGameItemRefDto(i));
			}
		}
		return res;
	}

	public CharacterStatsDto serializeStats() {
		return CharacterStatsDto.builder().xp(this.getExperience()).hp(Integer.valueOf((int) this.stats.getHp()))
				.mp(Integer.valueOf((int) this.stats.getMp())).def(Integer.valueOf((int) this.stats.getDef()))
				.str(Integer.valueOf((int) this.stats.getStr())).spd(Integer.valueOf((int) this.stats.getSpd()))
				.dex(Integer.valueOf((int) this.stats.getDex())).vit(Integer.valueOf((int) this.stats.getVit()))
				.wis(Integer.valueOf((int) this.stats.getWis())).hpPotions(this.hpPotions).mpPotions(this.mpPotions)
				.dyeId(Integer.valueOf(this.dyeId))
				.availableSkillPoints(Integer.valueOf(this.availableSkillPoints))
				.abilitySkillPoints(this.abilitySkillPoints != null
						? new HashMap<>(this.abilitySkillPoints) : new HashMap<>())
				.build();
	}

	/** Invested level for the given abilityId (0 if none invested). */
	public int getSkillLevel(int abilityId) {
		if (this.abilitySkillPoints == null) return 0;
		final Integer v = this.abilitySkillPoints.get(abilityId);
		return v == null ? 0 : v;
	}

	/** Invest one skill point into abilityId. False if no points, unknown id, or cap reached. */
	public boolean investSkillPoint(int abilityId) {
		if (this.availableSkillPoints <= 0) return false;
		final Ability ab = GameDataManager.ABILITIES == null ? null
				: GameDataManager.ABILITIES.get(abilityId);
		if (ab == null) return false;
		final int cap = ab.getMaxSkillPoints() <= 0 ? 5 : ab.getMaxSkillPoints();
		if (this.abilitySkillPoints == null) this.abilitySkillPoints = new HashMap<>();
		final int current = this.abilitySkillPoints.getOrDefault(abilityId, 0);
		if (current >= cap) return false;
		this.abilitySkillPoints.put(abilityId, current + 1);
		this.availableSkillPoints--;
		return true;
	}

	/** Award 1 SP per even level in (prevLevel, newLevel], capped at L20. Returns count granted. */
	public int awardSkillPointsForLevels(int prevLevel, int newLevel) {
		int granted = 0;
		for (int lvl = Math.max(prevLevel + 1, 2); lvl <= newLevel && lvl <= 20; lvl++) {
			if ((lvl & 1) == 0) {
				this.availableSkillPoints++;
				granted++;
			}
		}
		return granted;
	}

	private void resetInventory() {
		this.inventory = new GameItem[INVENTORY_SIZE];
	}

	public int firstEmptyInvSlot() {
		for (int i = EQUIPMENT_SLOT_COUNT; i < this.inventory.length; i++) {
			if (this.inventory[i] == null)
				return i;
		}
		return -1;
	}

	public boolean equipSlot(int slot, GameItem item) {
		this.inventory[slot] = item;
		return true;
	}

	public void equipSlots(Map<Integer, GameItem> items) {
		for (Map.Entry<Integer, GameItem> entry : items.entrySet()) {
			this.equipSlot(entry.getKey(), entry.getValue());
		}
	}

	public int findItemIndex(GameItem item) {
		for (int i = 0; i < this.inventory.length; i++) {
			if (this.inventory[i] != null && this.inventory[i].getUid().equals(item.getUid())) {
				return i;
			}
		}
		return -1;
	}

	public GameItem getSlot(int slot) {
		return this.inventory[slot];
	}

	public GameItem[] getSlots(int start, int end) {
		int size = end - start;
		int idx = 0;
		GameItem[] items = new GameItem[size];
		if (this.inventory == null)
			return items;
		for (int i = start; i < end; i++) {
			items[idx++] = this.inventory[i];
		}

		return items;
	}

	public int getWeaponId() {
		GameItem weapon = this.getSlot(0);
		return weapon == null ? -1 : weapon.getDamage().getProjectileGroupId();
	}

	/** Legacy bridge — returns the GameItem template used by RealmManagerServer.useAbility(). */
	public GameItem getAbility() {
		final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		if (cls == null) return null;
		final int abilityId = cls.getClassAbilityId();
		if (abilityId <= 0) return null;
		return GameDataManager.GAME_ITEMS.get(abilityId);
	}

	/** Ability bound to hotbar slot (0..3). Null if empty, passive, or unloaded. */
	public Ability getActiveAbility(int slot) {
		int id = this.getHotbarId(slot);
		if (id <= 0) {
			// Fallback to class defaultHotbar when bindings are stale/all-zero
			// (e.g., reconstructed via no-arg ctor on realm transition).
			if (slot >= 0 && slot < 4) {
				final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
				if (cls != null && cls.getAbilityTree() != null
						&& cls.getAbilityTree().getDefaultHotbar() != null) {
					final int[] tree = cls.getAbilityTree().getDefaultHotbar();
					if (slot < tree.length) id = tree[slot];
				}
			}
		}
		if (id <= 0 || GameDataManager.ABILITIES == null) return null;
		return GameDataManager.ABILITIES.get(id);
	}

	/** Passive bound to a hotbar slot, if any. Distinct from {@link #getClassPassive()}. */
	public PassiveAbility getSlottedPassive(int slot) {
		final int id = this.getHotbarId(slot);
		if (id <= 0 || GameDataManager.PASSIVES == null) return null;
		return GameDataManager.PASSIVES.get(id);
	}

	/** The class's always-on passive (not bindable). */
	public PassiveAbility getClassPassive() {
		final CharacterClassModel cls = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		if (cls == null || cls.getAbilityTree() == null) return null;
		final int id = cls.getAbilityTree().getPassive();
		if (id <= 0 || GameDataManager.PASSIVES == null) return null;
		return GameDataManager.PASSIVES.get(id);
	}

	public int getHotbarId(int slot) {
		if (this.hotbarBindings == null || slot < 0 || slot >= this.hotbarBindings.length) return 0;
		return this.hotbarBindings[slot];
	}

	/** True if the player is mid-cast on a non-instant ability. */
	public boolean isCasting() {
		return this.currentCast != null;
	}

	@Override
	public void update(double time) {
		super.update(time);
		Stats stats = this.getComputedStats();
		float currentHealthPercent = (float) this.getHealth() / (float) this.getComputedStats().getHp();
		float currentManaPercent = (float) this.getMana() / (float) this.getComputedStats().getMp();

		this.setHealthpercent(currentHealthPercent);
		this.setManapercent(currentManaPercent);

		if (((Instant.now().toEpochMilli() - this.lastStatsTime) >= 1000)) {
			this.lastStatsTime = System.currentTimeMillis();
			float mult = 1.0f;
			if (this.hasEffect(StatusEffectType.HEALING)) {
				mult = 1.5f;
			}
			final int vit = (int) ((0.24f * (stats.getVit() + 4.2f)) * mult);
			if (this.getHealth() < stats.getHp()) {
				int targetHealth = this.getHealth() + vit;
				if (targetHealth > stats.getHp()) {
					targetHealth = stats.getHp();
				}
				this.setHealth(targetHealth);
			} else if (this.getHealth() > stats.getHp()) {
				int targetHealth = this.getHealth() - stats.getHp();
				this.setHealth(this.getHealth() - targetHealth);
			}
			float mpMult = 1.0f;
			if (this.hasEffect(StatusEffectType.MANA_FOUNT)) mpMult = 2.0f;
			// Passive "Muscle for Brains" (11016, 12000) regens MP from STR instead of WIS.
			final PassiveAbility classPassive = this.getClassPassive();
			final int passiveId = classPassive != null ? classPassive.getId() : -1;
			final boolean strRegen = passiveId == 11016 || passiveId == 12000;
			final int regenStat = strRegen ? stats.getStr() : stats.getWis();
			final int regen = (int) ((0.12f * (regenStat + 4.2f)) * mpMult);
			if (this.getMana() < stats.getMp()) {
				int targetMana = this.getMana() + regen;
				if (targetMana > stats.getMp()) {
					targetMana = stats.getMp();
				}
				this.setMana(targetMana);
			}
		}
	}

	public Stats getComputedStats() {
		if (this.stats == null)
			return new Stats();
		Stats stats = this.stats.clone();
		GameItem[] equipment = this.getSlots(0, EQUIPMENT_SLOT_COUNT);
		for (GameItem item : equipment) {
			if (item == null) continue;
			stats = stats.concat(item.getStats());
			if (item.getAttributeModifiers() != null) {
				for (AttributeModifier m : item.getAttributeModifiers()) {
					applyStatDelta(stats, m.getStatId(), m.getDeltaValue());
				}
			}
			if (item.getEnchantments() != null) {
				for (Enchantment e : item.getEnchantments()) {
					applyStatDelta(stats, e.getStatId(), e.getDeltaValue());
				}
			}
			final Gemstone g = GemstoneRegistry.forItem(item);
			if (g != null) g.modifyStats(stats, this, item);
		}
		if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) {
			stats.setDef((short) 0);
		}
		else if (this.hasEffect(StatusEffectType.ARMORED)) {
			stats.setDef((short) (stats.getDef() * 2));
		}
		else if (this.hasEffect(StatusEffectType.BRACED)) {
			stats.setDef((short) Math.min(Short.MAX_VALUE, (int)(stats.getDef() * 1.5f)));
		}
		if (this.hasEffect(StatusEffectType.PROTECTED)) {
			stats.setVit((short) Math.min(Short.MAX_VALUE, stats.getVit() + 5));
		}
		if (this.hasEffect(StatusEffectType.EMPOWERED_STR)) {
			final short bonus = this.getEffectMagnitude(StatusEffectType.EMPOWERED_STR);
			if (bonus > 0) stats.setStr((short) Math.min(Short.MAX_VALUE, stats.getStr() + bonus));
		}
		if (this.hasEffect(StatusEffectType.EMPOWERED_DEX)) {
			final short bonus = this.getEffectMagnitude(StatusEffectType.EMPOWERED_DEX);
			if (bonus > 0) stats.setDex((short) Math.min(Short.MAX_VALUE, stats.getDex() + bonus));
		}
		// Class-passive stat hooks (passives without trigger arrays).
		final PassiveAbility _passive = this.getClassPassive();
		if (_passive != null) {
			switch (_passive.getId()) {
				case 12009: { // Knight "Thick Skin": +(VIT/5) DEF
					final int bonus = stats.getVit() / 5;
					if (bonus > 0) stats.setDef((short) Math.min(Short.MAX_VALUE, stats.getDef() + bonus));
					break;
				}
				case 12007: { // Druid "Will of Nature": +(DEX/10) WIS
					final int bonus = stats.getDex() / 10;
					if (bonus > 0) stats.setWis((short) Math.min(Short.MAX_VALUE, stats.getWis() + bonus));
					break;
				}
				case 12002: { // Wizard "Uncontestable Knowledge": +1 WIS per 20 MP above class base
					final int baseMp = this.stats.getMp();
					final int bonusMp = Math.max(0, stats.getMp() - baseMp);
					final int bonus = bonusMp / 20;
					if (bonus > 0) stats.setWis((short) Math.min(Short.MAX_VALUE, stats.getWis() + bonus));
					break;
				}
				default: break;
			}
		}
		return stats;
	}

	private static void applyStatDelta(Stats stats, int statId, int delta) {
		switch (statId) {
		case 0: stats.setVit((short) (stats.getVit() + delta)); break;
		case 1: stats.setWis((short) (stats.getWis() + delta)); break;
		case 2: stats.setHp(stats.getHp() + delta); break;
		case 3: stats.setMp((short) (stats.getMp() + delta)); break;
		case 4: stats.setStr((short) (stats.getStr() + delta)); break;
		case 5: stats.setDef((short) (stats.getDef() + delta)); break;
		case 6: stats.setSpd((short) (stats.getSpd() + delta)); break;
		case 7: stats.setDex((short) (stats.getDex() + delta)); break;
		}
	}

	public void drinkHp() {
		this.stats.setHp((short) (this.stats.getHp() + 5));
	}

	public void drinkMp() {
		this.stats.setMp((short) (this.stats.getMp() + 5));
	}

	public boolean addHpPotion() {
		if (this.hpPotions >= MAX_CONSUMABLE_POTIONS) return false;
		this.hpPotions++;
		return true;
	}

	public boolean addMpPotion() {
		if (this.mpPotions >= MAX_CONSUMABLE_POTIONS) return false;
		this.mpPotions++;
		return true;
	}

	public boolean consumeHpPotion() {
		if (this.hpPotions <= 0) return false;
		this.hpPotions--;
		this.health = Math.min(this.health + 100, this.getComputedStats().getHp());
		return true;
	}

	public boolean consumeMpPotion() {
		if (this.mpPotions <= 0) return false;
		this.mpPotions--;
		this.mana = Math.min(this.mana + 100, this.getComputedStats().getMp());
		return true;
	}

	@Override
	public float getHealthpercent() {
		return this.healthpercent;
	}

	@Override
	public int getHealth() {
		return this.health;
	}

	@Override
	public int getMana() {
		return this.mana;
	}

	public void applyState(PlayerStatePacket packet) {
		this.health = packet.getHealth();
		this.mana = packet.getMana();
		this.setEffectIds(packet.getEffectIds());
		this.setEffectTimes(packet.getEffectTimes());
	}

	public int numStatsMaxed() {
		int count = 0;
		for (int i = 0; i < 8; i++) {
			if (this.isStatMaxed(i)) {
				count++;
			}
		}
		return count;
	}

	public boolean isStatMaxed(int statIdx) {
		final CharacterClassModel characterClass = GameDataManager.CHARACTER_CLASSES.get(this.classId);
		final Stats maxStats = characterClass.getMaxStats();
		boolean maxed = false;
		switch (statIdx) {
		case 0:
			maxed = this.stats.getHp() >= maxStats.getHp();
			break;
		case 1:
			maxed = this.stats.getMp() >= maxStats.getMp();
			break;
		case 2:
			maxed = this.stats.getDef() >= maxStats.getDef();
			break;
		case 3:
			maxed = this.stats.getStr() >= maxStats.getStr();
			break;
		case 4:
			maxed = this.stats.getSpd() >= maxStats.getSpd();
			break;
		case 5:
			maxed = this.stats.getDex() >= maxStats.getDex();
			break;
		case 6:
			maxed = this.stats.getVit() >= maxStats.getVit();
			break;
		case 7:
			maxed = this.stats.getWis() >= maxStats.getWis();
			break;
		}
		return maxed;
	}

	public boolean canConsume(final GameItem item) {
		boolean canConsume = true;
		if (((item.getStats().getHp() > 0) && this.isStatMaxed(0))
				|| ((item.getStats().getMp() > 0) && this.isStatMaxed(1))) {
			canConsume = false;
		} else if (((item.getStats().getMp() > 0) && this.isStatMaxed(1))
				|| ((item.getStats().getDef() > 0) && this.isStatMaxed(2))) {
			canConsume = false;
		} else if (((item.getStats().getStr() > 0) && this.isStatMaxed(3))
				|| ((item.getStats().getSpd() > 0) && this.isStatMaxed(4))) {
			canConsume = false;
		} else if (((item.getStats().getDex() > 0) && this.isStatMaxed(5))
				|| ((item.getStats().getVit() > 0) && this.isStatMaxed(6))) {
			canConsume = false;
		} else if ((item.getStats().getWis() > 0) && this.isStatMaxed(7)) {
			canConsume = false;
		}
		return canConsume;
	}

	public boolean getIsUp() {
		return this.up;
	}

	public boolean getIsDown() {
		return this.down;
	}

	public boolean getIsLeft() {
		return this.left;
	}

	public boolean getIsRight() {
		return this.right;
	}

	public void queueInput(int seq, float vx, float vy) {
		if (this.inputQueue == null) this.inputQueue = new ConcurrentLinkedQueue<>();
		if (seq > this.lastProcessedInputSeq) {
			this.inputQueue.add(new float[]{(float) seq, vx, vy});
		}
	}

	public int getUpperExperienceBound() {
		if (this.experience > GameDataManager.EXPERIENCE_LVLS.maxExperience())
			return GameDataManager.EXPERIENCE_LVLS.maxExperience();

		final Tuple<Integer, Integer> expRange = GameDataManager.EXPERIENCE_LVLS.getParsedMap()
				.get(GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience));

		return expRange.getY();
	}

	public float getExperiencePercent() {
		if (this.experience > GameDataManager.EXPERIENCE_LVLS.maxExperience())
			return 1.0f;

		final Tuple<Integer, Integer> expRange = GameDataManager.EXPERIENCE_LVLS.getParsedMap()
				.get(GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience));

		return ((float) this.experience / (float) expRange.getY());
	}

	public float getHealthPercent() {
		return this.healthpercent;
	}

	public float getManaPercent() {
		return this.manapercent;
	}

	public int incrementExperience(long experience) {
		final long newExperience = this.getExperience() + experience;
		final int currentLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(this.experience);
		final int newLevel = GameDataManager.EXPERIENCE_LVLS.getLevel(newExperience);
		final CharacterClassModel classModel = GameDataManager.CHARACTER_CLASSES.get(this.getClassId());
		final int levelsGained = newLevel - currentLevel;
		if (levelsGained > 0) {
			for (int i = 0; i < levelsGained; i++) {
				this.setStats(this.getStats().concat(classModel.getRandomLevelUpStats()));
			}
			this.setHealth(this.stats.getHp());
			this.setMana(this.stats.getMp());
			final int granted = this.awardSkillPointsForLevels(currentLevel, newLevel);
			if (granted > 0) {
				log.info("[SKILL-POINTS] player {} crossed L{}->{} earned {} SP (pool={})",
						this.getId(), currentLevel, newLevel, granted, this.availableSkillPoints);
			}
		}
		this.setExperience(newExperience);
		return levelsGained;
	}

	public void write(DataOutputStream stream) throws Exception {
		stream.writeLong(this.getId());
		stream.writeUTF(this.getName());
		stream.writeUTF(this.accountUuid);
		stream.writeUTF(this.characterUuid);
		stream.writeInt(this.getClassId());
		stream.writeShort(this.getSize());
		stream.writeFloat(this.getPos().x);
		stream.writeFloat(this.getPos().y);
		stream.writeFloat(this.dx);
		stream.writeFloat(this.dy);
	}

	public GameItem[] selectGameItems(Boolean[] selectedIdx) {
		GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);
		if (selectedIdx.length != inv.length) {
			System.err.println("SELECT GAME ITEM IDX SIZES NOT EQUAL");
			return null;
		}
		List<GameItem> selected = new ArrayList<>();
		for (int i = 0; i < inv.length; i++) {
			if (inv[i] == null)
				continue;

			if (selectedIdx[i] != null && selectedIdx[i]) {
				selected.add(inv[i]);
			}
		}
		return selected.toArray(new GameItem[0]);
	}

	public NetGameItemRef[] getInventoryAsNetGameItemRefs() {
		final GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);
		final List<NetGameItemRef> results = new ArrayList<>();
		for (int i = 0; i < inv.length; i++) {
			if (inv[i] == null)
				continue;
			results.add(inv[i].asNetGameItemRef(i + EQUIPMENT_SLOT_COUNT));
		}
		return results.toArray(new NetGameItemRef[0]);

	}

	public void addItems(GameItem[] items) {
		for (GameItem item : items) {
			if (item == null)
				continue;
			if (item.isStackable()) {
				int remaining = item.getStackCount();
				for (int i = EQUIPMENT_SLOT_COUNT; i < this.inventory.length && remaining > 0; i++) {
					final GameItem existing = this.inventory[i];
					if (existing == null) continue;
					if (existing.getItemId() != item.getItemId()) continue;
					if (!existing.isStackable()) continue;
					final int room = existing.getMaxStack() - existing.getStackCount();
					if (room <= 0) continue;
					final int move = Math.min(room, remaining);
					existing.setStackCount(existing.getStackCount() + move);
					remaining -= move;
				}
				if (remaining > 0) {
					final int slot = this.firstEmptyInvSlot();
					if (slot == -1) break;
					item.setStackCount(remaining);
					this.inventory[slot] = item;
				}
				continue;
			}
			int slot = this.firstEmptyInvSlot();
			if (slot == -1)
				break;
			this.inventory[slot] = item;
		}
	}

	public void removeItems(GameItem[] items) {
		final GameItem[] inv = this.getSlots(EQUIPMENT_SLOT_COUNT, EQUIPMENT_SLOT_COUNT + 8);

		for (int i = 0; i < inv.length; i++) {
			GameItem invItem = inv[i];
			if (invItem == null)
				continue;
			for (GameItem toRemove : items) {
				if (invItem.getUid() != null && invItem.getUid().equals(toRemove.getUid())) {
					this.inventory[i + EQUIPMENT_SLOT_COUNT] = null;
					break;
				}
			}
		}
	}

	@Override
	public String toString() {
		return this.getId() + " , Pos: " + this.pos.toString() + ", Class: " + this.getClassId() + ", Headless: "
				+ this.isHeadless();
	}

}
