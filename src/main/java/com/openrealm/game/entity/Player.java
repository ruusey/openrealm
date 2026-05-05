package com.openrealm.game.entity;

import java.io.DataOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openrealm.account.dto.CharacterStatsDto;
import com.openrealm.account.dto.GameItemRefDto;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.net.entity.NetGameItemRef;
import com.openrealm.util.Tuple;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Builder
@EqualsAndHashCode(callSuper = false)
public class Player extends Entity {
	private GameItem[] inventory;
	private long lastStatsTime;
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
	// /admin toggle. Defaults true on login for any account. When false, the
	// command-dispatch gate rejects @AdminRestrictedCommand calls regardless
	// of provisions, godmode is cleared, and chatRole is masked. Not persisted.
	// No @Builder.Default — the explicit all-args constructor below would
	// need updating (and none of its callers care about this transient flag).
	private transient boolean adminModeEnabled = true;
	// Stash original chatRole when /admin toggles off so we can restore it
	// without re-fetching the account from the data service.
	private transient String storedChatRole = null;
	// /hide flag — when true the LoadPacket builder filters this player out
	// of every OTHER viewer's packet, leaving the admin invisible to
	// regular players. Self always sees self. Not persisted.
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
	private transient java.util.Queue<float[]> inputQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

	public static final int MAX_CONSUMABLE_POTIONS = 6;
	public static final int HP_POTION_ITEM_ID = 296;
	public static final int MP_POTION_ITEM_ID = 297;
	@Builder.Default
	private int hpPotions = 0;
	@Builder.Default
	private int mpPotions = 0;

	@Builder.Default
	private int dyeId = 0;

	@Builder.Default
	private transient long cachedAccountFame = 0L;

	public Player() {
		super(0, null, 0);
	}

	public Player(GameItem[] inventory, long lastStatsTime, LootContainer currentLootContainer, int classId,
			String accountUuid, String characterUuid, long experience, Stats stats, boolean headless, boolean bot,
			String chatRole, boolean adminModeEnabled, String storedChatRole, boolean hiddenFromOthers,
			int lastInputSeq, int lastProcessedInputSeq, float currentVx, float currentVy,
			java.util.Queue<float[]> inputQueue, int hpPotions, int mpPotions, int dyeId, long cachedAccountFame) {
		super(0, null, 0);
		this.inventory = inventory;
		this.lastStatsTime = lastStatsTime;
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
		this.inputQueue = inputQueue != null ? inputQueue : new java.util.concurrent.ConcurrentLinkedQueue<>();
		this.hpPotions = hpPotions;
		this.mpPotions = mpPotions;
		this.dyeId = dyeId;
		this.cachedAccountFame = cachedAccountFame;
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
	}

	public void applyStats(CharacterStatsDto stats) {
		this.setExperience(stats.getXp());
		this.health = stats.getHp();
		this.mana = stats.getMp();
		this.stats.setHp(stats.getHp().shortValue());
		this.stats.setMp(stats.getMp().shortValue());
		this.stats.setDef(stats.getDef().shortValue());
		this.stats.setAtt(stats.getAtt().shortValue());
		this.stats.setSpd(stats.getSpd().shortValue());
		this.stats.setDex(stats.getDex().shortValue());
		this.stats.setVit(stats.getVit().shortValue());
		this.stats.setWis(stats.getWis().shortValue());
		if (stats.getHpPotions() != null) this.hpPotions = stats.getHpPotions();
		if (stats.getMpPotions() != null) this.mpPotions = stats.getMpPotions();
		if (stats.getDyeId() != null) this.dyeId = stats.getDyeId();
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
				.att(Integer.valueOf((int) this.stats.getAtt())).spd(Integer.valueOf((int) this.stats.getSpd()))
				.dex(Integer.valueOf((int) this.stats.getDex())).vit(Integer.valueOf((int) this.stats.getVit()))
				.wis(Integer.valueOf((int) this.stats.getWis())).hpPotions(this.hpPotions).mpPotions(this.mpPotions)
				.dyeId(Integer.valueOf(this.dyeId)).build();
	}

	private void resetInventory() {
		this.inventory = new GameItem[20];
	}

	public int firstEmptyInvSlot() {
		for (int i = 4; i < this.inventory.length; i++) {
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

	public GameItem getAbility() {
		GameItem weapon = this.getSlot(1);
		return weapon;
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
			final int wis = (int) ((0.12f * (stats.getWis() + 4.2f)));
			if (this.getMana() < stats.getMp()) {
				int targetMana = this.getMana() + wis;
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
		GameItem[] equipment = this.getSlots(0, 4);
		// Two-pass to keep STAT_SCALE multipliers honest: first sum all
		// additive contributions (item stats, attribute modifiers, STAT_DELTA
		// enchantments), then apply scale percentages on the post-additive
		// totals so a "+10% wisdom" gem stacks predictably with raw +WIS.
		int[] scalePctByStat = new int[8];
		for (GameItem item : equipment) {
			if (item == null) continue;
			stats = stats.concat(item.getStats());
			if (item.getAttributeModifiers() != null) {
				for (com.openrealm.game.entity.item.AttributeModifier m : item.getAttributeModifiers()) {
					applyStatDelta(stats, m.getStatId(), m.getDeltaValue());
				}
			}
			if (item.getEnchantments() != null) {
				for (com.openrealm.game.entity.item.Enchantment e : item.getEnchantments()) {
					final byte effectType = e.getEffectType();
					if (effectType == 0 /* STAT_DELTA */) {
						// param1=statId, magnitude=delta (legacy uses statId/deltaValue)
						final byte sid = (byte) (e.getParam1() != 0 || e.getMagnitude() != 0 ? e.getParam1() : e.getStatId());
						final short delta = e.getMagnitude() != 0 ? e.getMagnitude() : (short) e.getDeltaValue();
						applyStatDelta(stats, sid, delta);
					} else if (effectType == 1 /* STAT_SCALE */) {
						final int sid = e.getParam1();
						if (sid >= 0 && sid < 8) scalePctByStat[sid] += e.getMagnitude();
					}
					// Other effectTypes (PROJECTILE_*, ON_HIT_*, LIFESTEAL, CRIT)
					// are combat-time and applied at projectile-spawn / damage-roll.
				}
			}
		}
		if (scalePctByStat[0] != 0) stats.setVit((short) (stats.getVit() + (stats.getVit() * scalePctByStat[0]) / 100));
		if (scalePctByStat[1] != 0) stats.setWis((short) (stats.getWis() + (stats.getWis() * scalePctByStat[1]) / 100));
		if (scalePctByStat[2] != 0) stats.setHp(stats.getHp() + (stats.getHp() * scalePctByStat[2]) / 100);
		if (scalePctByStat[3] != 0) stats.setMp((short) (stats.getMp() + (stats.getMp() * scalePctByStat[3]) / 100));
		if (scalePctByStat[4] != 0) stats.setAtt((short) (stats.getAtt() + (stats.getAtt() * scalePctByStat[4]) / 100));
		if (scalePctByStat[5] != 0) stats.setDef((short) (stats.getDef() + (stats.getDef() * scalePctByStat[5]) / 100));
		if (scalePctByStat[6] != 0) stats.setSpd((short) (stats.getSpd() + (stats.getSpd() * scalePctByStat[6]) / 100));
		if (scalePctByStat[7] != 0) stats.setDex((short) (stats.getDex() + (stats.getDex() * scalePctByStat[7]) / 100));
		if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) {
			stats.setDef((short) 0);
		}
		else if (this.hasEffect(StatusEffectType.ARMORED)) {
			stats.setDef((short) (stats.getDef() * 2));
		}
		return stats;
	}

	private static void applyStatDelta(Stats stats, int statId, int delta) {
		switch (statId) {
		case 0: stats.setVit((short) (stats.getVit() + delta)); break;
		case 1: stats.setWis((short) (stats.getWis() + delta)); break;
		case 2: stats.setHp(stats.getHp() + delta); break;
		case 3: stats.setMp((short) (stats.getMp() + delta)); break;
		case 4: stats.setAtt((short) (stats.getAtt() + delta)); break;
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

	public void applyState(com.openrealm.net.client.packet.PlayerStatePacket packet) {
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
			maxed = this.stats.getAtt() >= maxStats.getAtt();
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
		} else if (((item.getStats().getAtt() > 0) && this.isStatMaxed(3))
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
		if (this.inputQueue == null) this.inputQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
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
		GameItem[] inv = this.getSlots(4, 12);
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
		final GameItem[] inv = this.getSlots(4, 12);
		final List<NetGameItemRef> results = new ArrayList<>();
		for (int i = 0; i < inv.length; i++) {
			if (inv[i] == null)
				continue;
			results.add(inv[i].asNetGameItemRef(i + 4));
		}
		return results.toArray(new NetGameItemRef[0]);

	}

	public void addItems(GameItem[] items) {
		for (GameItem item : items) {
			if (item == null)
				continue;
			if (item.isStackable()) {
				int remaining = item.getStackCount();
				for (int i = 4; i < this.inventory.length && remaining > 0; i++) {
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
		final GameItem[] inv = this.getSlots(4, 12);

		for (int i = 0; i < inv.length; i++) {
			GameItem invItem = inv[i];
			if (invItem == null)
				continue;
			for (GameItem toRemove : items) {
				if (invItem.getUid() != null && invItem.getUid().equals(toRemove.getUid())) {
					this.inventory[i + 4] = null;
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
