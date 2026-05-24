package com.openrealm.net.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import lombok.extern.slf4j.Slf4j;

import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.LootContainer;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.GemstoneRegistry;
import com.openrealm.game.entity.item.gem.ShotContext;
import com.openrealm.game.contants.LootTier;
import com.openrealm.game.contants.ProjectilePositionMode;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileEffect;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityEffect;
import com.openrealm.game.model.ability.AbilityScaling;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.entity.CastState;
import com.openrealm.game.entity.item.Effect;
import com.openrealm.net.client.packet.AbilityCastStartPacket;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.game.script.item.UseableItemScriptBase;

@Slf4j
public final class ServerAbilityHelper {

    private ServerAbilityHelper() {}

	public static void useAbility(final RealmManagerServer mgr, final long realmId, final long playerId, final Vector2f pos,
			final byte abilityIndex, final boolean isCastResolution) {
		final Realm targetRealm = mgr.getRealms().get(realmId);

		final Player player = targetRealm.getPlayer(playerId);
		if (player == null) return;
		// Reject re-cast while mid-cast on a previous ability; cast-resolution bypasses.
		if (!isCastResolution && player.isCasting()) return;

		final int slot = abilityIndex >= 0 && abilityIndex < 4 ? abilityIndex : 0;
		final Ability ab = player.getActiveAbility(slot);
		if (ab != null) {
			final int maxRange = ab.getMaxCastRange();
			if (maxRange >= 0) {
				final float cx = player.getPos().x + player.getSize() * 0.5f;
				final float cy = player.getPos().y + player.getSize() * 0.5f;
				if (maxRange == 0) {
					pos.x = cx;
					pos.y = cy;
				} else {
					final float dx = pos.x - cx;
					final float dy = pos.y - cy;
					final float distSq = dx * dx + dy * dy;
					final long maxSq = (long) maxRange * maxRange;
					if (distSq > maxSq) {
						final float scale = maxRange / (float) Math.sqrt(distSq);
						pos.x = cx + dx * scale;
						pos.y = cy + dy * scale;
					}
				}
			}
		}
		log.info("[USEABILITY] playerId={} classId={} slot={} hotbarId={} ab={} tags={}",
				player.getId(), player.getClassId(), slot,
				player.getHotbarId(slot),
				ab == null ? "(null - falling to LEGACY path)" : ("#"+ab.getId()+" "+ab.getName()),
				ab == null ? "n/a" : ab.getTags());
		final int abMpCost;
		final long abCooldownMs;
		if (ab != null) {
			abMpCost = ab.getMpCost();
			// Effective CD = baseCooldown − level × cdReduction, floored at 500ms.
			final int invested = player.getSkillLevel(ab.getId());
			final long reduction = (long) invested * (long) ab.getCdReductionPerPointMs();
			abCooldownMs = Math.max(500L, ab.getBaseCooldownMs() - reduction);
		} else {
			abMpCost     = -1;
			abCooldownMs = -1;
		}

		// Cast-resolution skips the CD gate — the cooldown was set at cast-start to (now + castMs + cooldownMs).
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

		// Bail only when BOTH the new Ability data AND the legacy item are missing — otherwise
		// classes with no legacy ability item would silently kill the cast after client paid mana.
		final GameItem legacyAbility = player.getAbility();
		final GameItem abilityItem = legacyAbility != null
				? GameDataManager.GAME_ITEMS.get(legacyAbility.getItemId())
				: null;
		if (ab == null && abilityItem == null) return;
		final Effect effect = abilityItem != null ? abilityItem.getEffect() : null;

		if (ab == null) {
			final Long lastAbilityUsage = mgr.getPlayerAbilityState().get(playerId);
			if (lastAbilityUsage == null
					|| (now - lastAbilityUsage >= effect.getCooldownDuration())) {
				mgr.getPlayerAbilityState().put(playerId, now);
			} else {
				log.debug("Ability {} is on cooldown (legacy)", abilityItem);
				return;
			}
		}

		// MP is charged at cast-start, not at resolution. INVINCIBLE = free casts.
		if (!isCastResolution && !player.hasEffect(StatusEffectType.INVINCIBLE)) {
			final int mpCost = abMpCost >= 0 ? abMpCost : effect.getMpCost();
			if (player.getMana() < mpCost) return;
			player.setMana(player.getMana() - mpCost);
			// Force PlayerStatePacket to ship — otherwise post-regen mana matching cached mana
			// skips the send and the client gates further casts on its stale-low local view.
			mgr.invalidatePlayerStateCache(player.getId());
		}

		// Cast-time gate: schedule resolution, SLOW for cast duration, broadcast cast-start.
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
			// Push slot cooldown forward to include the cast time.
			final long[] cds2 = player.getAbilityCooldowns();
			if (cds2 != null && slot < cds2.length) {
				cds2[slot] = now + castMs + abCooldownMs;
			}
			mgr.enqueueServerPacketToRealm(targetRealm,
					new AbilityCastStartPacket(
							playerId, ab.getId(), (byte) slot, (int) castMs, pos.x, pos.y));
			return;
		}
		if (ab != null && player.getMetrics() != null) {
			player.getMetrics().recordCastCompleted(ab.getId());
		}
		// When an Ability is bound, it is FULLY authoritative for projectile + self-effect.
		int effPgId;
		StatusEffectType effSelfStatus;
		int effSelfDurationMs;
		boolean effHasSelfStatus;
		final List<Object[]> extraSelfStatuses = new ArrayList<>();
		if (ab != null) {
			effPgId           = -1;
			effSelfStatus     = null;
			effSelfDurationMs = 0;
			effHasSelfStatus  = false;
			int selfDurationBonusMs = 0;
			if (ab.getScalings() != null) {
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"DURATION".equalsIgnoreCase(sc.getTarget())) continue;
					final int statVal = resolveScalingInput(player, ab, sc);
					selfDurationBonusMs += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
				}
			}
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
							final int dur = (int) e.getBaseDurationMs() + selfDurationBonusMs;
							if (!effHasSelfStatus) {
								effSelfStatus    = st;
								effSelfDurationMs = dur;
								effHasSelfStatus = true;
							} else {
								extraSelfStatuses.add(new Object[] { st, dur });
							}
						}
					} catch (NumberFormatException ignore) { }
				}
			}
		} else {
			effPgId           = (abilityItem.getDamage() != null) ? abilityItem.getDamage().getProjectileGroupId() : -1;
			effSelfStatus     = (effect != null) ? effect.getEffectId() : null;
			effSelfDurationMs = (effect != null) ? (int) effect.getDuration() : 0;
			effHasSelfStatus  = effect != null && effect.isSelf();
		}
		final ProjectileGroup group = (effPgId >= 0)
				? GameDataManager.PROJECTILE_GROUPS.get(effPgId)
				: null;

		final ShotContext abilityCtx = new ShotContext();
		if (abilityItem != null) {
			final Gemstone g = GemstoneRegistry.forItem(abilityItem);
			if (g != null) g.modifyShot(abilityCtx, player, abilityItem);
		}

		final boolean fromSky = ab != null && ab.getTags() != null && ab.getTags().contains("from_sky");
		final boolean holyVisual = ab != null && ab.getTags() != null && ab.getTags().contains("holy");
		final float SKY_FALL_HEIGHT = 320f;
		if (fromSky && !holyVisual) {
			for (int offX = -6; offX <= 6; offX += 3) {
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						pos.x + offX, pos.y - SKY_FALL_HEIGHT,
						pos.x + offX, pos.y, (short) 800));
			}
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_SMOKE_POOF, pos.x, pos.y, 112f, (short) 1100));
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST, pos.x, pos.y, 80f, (short) 700, (byte) 6));
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST, pos.x, pos.y, 48f, (short) 500, (byte) 6));
		} else if (fromSky && holyVisual) {
			// WIZARD_BURST (radial) not KNIGHT_SHOCKWAVE (directional) so it reads as ground impact.
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_PALADIN_SEAL,
					pos.x, pos.y, 130f, (short) 1400, (byte) 3));
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_WIZARD_BURST,
					pos.x, pos.y, 90f, (short) 700, (byte) 3));
		}

		if (ab != null && ab.getTags() != null) {
			for (String tag : ab.getTags()) {
				if (tag == null || !tag.toLowerCase().startsWith("visual_at_self:")) continue;
				try {
					final short eff = Short.parseShort(tag.substring("visual_at_self:".length()).trim());
					final Vector2f pcenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
							eff, pcenter.x, pcenter.y, 48f, (short) 600));
				} catch (NumberFormatException ignore) { }
				break;
			}

			if (ab.getTags().contains("knight_slam")) {
				final Vector2f from = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dxK = pos.x - from.x, dyK = pos.y - from.y;
				final float lenK = (float) Math.sqrt(dxK * dxK + dyK * dyK);
				if (lenK > 0.001f) {
					final float FRONT_OFFSET = 30f;
					final float MAX_REACH    = 110f;
					final float endDist      = Math.min(lenK, MAX_REACH);
					final float fx0 = from.x + dxK / lenK * FRONT_OFFSET;
					final float fy0 = from.y + dyK / lenK * FRONT_OFFSET;
					final float fx1 = from.x + dxK / lenK * endDist;
					final float fy1 = from.y + dyK / lenK * endDist;
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
							CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
							fx0, fy0, fx1, fy1, (short) 280));
				}
			}

			if (ab.getTags().contains("taunt_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_TAUNT_ROAR, pc.x, pc.y, 44f, (short) 700, (byte) 5));
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "TAUNTING");
			}

			if (ab.getTags().contains("brace_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BRACE_STANCE, pc.x, pc.y, 56f, (short) 700, (byte) 1));
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "BRACED");
			}

			if (ab.getTags().contains("dash_trail")) {
				final Vector2f from = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dx = pos.x - from.x, dy = pos.y - from.y;
				final float len = (float) Math.sqrt(dx * dx + dy * dy);
				if (len > 0.001f) { dx = dx / len * 160f; dy = dy / len * 160f; }
				else              { dx = 160f; dy = 0f; }
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						from.x, from.y, from.x + dx, from.y + dy, (short) 500));
			}

			if (ab.getTags().contains("shadow_dash")) {
				// GROUNDED vetoes movement abilities.
				if (player.hasEffect(StatusEffectType.GROUNDED)) {
					mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
					return;
				}
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				float dx = pos.x - origin.x, dy = pos.y - origin.y;
				final float len = (float) Math.sqrt(dx * dx + dy * dy);
				final float MAX_DASH = 192f;
				if (len > 0.001f) { dx = dx / len; dy = dy / len; }
				else              { dx = 1f; dy = 0f; }
				final float dashDist = Math.min(MAX_DASH, len < 0.001f ? MAX_DASH : len);
				// Walk in 16px increments until hitting a wall — last clear step is landing.
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
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						origin.x, origin.y, endCenter.x, endCenter.y, (short) 500));
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_SMOKE_POOF, origin.x, origin.y, 40f, (short) 400, (byte) 0));
			}

			if (ab.getTags().contains("shuriken_volley")) {
				final int sp = player.getSkillLevel(ab.getId());
				final int tier = Math.min(5, Math.max(0, sp));
				final int groupId = 1000 + tier;
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				final float baseA = Bullet.getAngle(origin, pos);
				// Bullet ctor stores -angle so flight unit = (-sin(baseA), cos(baseA));
				// backward unit for stagger = (sin(baseA), -cos(baseA)).
				final float bx = (float) Math.sin(baseA);
				final float by = (float) -Math.cos(baseA);
				final int   STARS = 3;
				final float STEP = 36f;
				int dmg = ab.getBaseDamage();
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
					final int sv = resolveScalingInput(player, ab, sc);
					dmg += (int) sc.curveEnum().apply(sv, sc.getCoeff(), sc.getCap());
				}
				final short damage = (short) Math.min(Short.MAX_VALUE, Math.max(0, dmg));
				final List<Short> flags = new ArrayList<>();
				for (int i = 0; i < STARS; i++) {
					final Vector2f src = new Vector2f(
							origin.x + bx * STEP * i,
							origin.y + by * STEP * i);
					mgr.addProjectile(realmId, 0L, player.getId(), groupId,
							0, src, baseA, (short) 22, 6f, 2048f,
							damage, false, flags, (short) 0, (short) 0, player.getId());
				}
			}
		}

		final boolean aoeTargeted = ab != null && ab.getTags() != null && ab.getTags().contains("aoe_targeted");
		final boolean aoeAlly    = ab != null && ab.getTags() != null && ab.getTags().contains("aoe_ally");
		if (aoeTargeted || aoeAlly) {
			float aoeRadius = (ab.getBaseRadius() > 0) ? ab.getBaseRadius() : 96f;
			if (ab != null) {
				for (AbilityScaling sc : ab.scalingList()) {
					if (!"RADIUS".equalsIgnoreCase(sc.getTarget())) continue;
					final int statVal = resolveScalingInput(player, ab, sc);
					aoeRadius += sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
				}
			}
			final Vector2f effCenter;
			if (aoeAlly) {
				effCenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			} else {
				effCenter = pos;
			}
			// from_sky owns its own visual chain above — skip generic AoE ring.
			if (!fromSky) {
				short visualEffect = CreateEffectPacket.EFFECT_STASIS_FIELD;
				byte  vTier        = 1;
				if (ab.getTags().contains("fire"))     { visualEffect = CreateEffectPacket.EFFECT_WIZARD_BURST;   vTier = 4; }
				if (ab.getTags().contains("curse"))    { visualEffect = CreateEffectPacket.EFFECT_CURSE_RADIUS;   vTier = 6; }
				if (ab.getTags().contains("heal"))     { visualEffect = CreateEffectPacket.EFFECT_HEAL_RADIUS;    vTier = 2; }
				if (ab.getTags().contains("cleanse"))  { visualEffect = CreateEffectPacket.EFFECT_WATER_FOUNTAIN; vTier = 1; }
				if (ab.getTags().contains("bless"))    { visualEffect = CreateEffectPacket.EFFECT_PALADIN_SEAL;   vTier = 3; }
				if (ab.getTags().contains("holy"))     { visualEffect = CreateEffectPacket.EFFECT_PALADIN_SEAL;   vTier = 3; }
				if (ab.getTags().contains("frost"))    { visualEffect = CreateEffectPacket.EFFECT_FROST_NOVA;     vTier = 1; }
				if (ab.getTags().contains("poison"))   { visualEffect = CreateEffectPacket.EFFECT_POISON_CLOUD;   vTier = 2; }
				if (ab.getTags().contains("drain"))    { visualEffect = CreateEffectPacket.EFFECT_LIFE_DRAIN;     vTier = 5; }
				if (ab.getTags().contains("bone"))     { visualEffect = CreateEffectPacket.EFFECT_BONE_SPIKES;    vTier = 0; }
				if (ab.getTags().contains("lightning")){ visualEffect = CreateEffectPacket.EFFECT_LIGHTNING_STRIKE;vTier = 3; }
				if (ab.getTags().contains("arcane"))   { visualEffect = CreateEffectPacket.EFFECT_MANA_BOLT;      vTier = 6; }
				if (ab.getTags().contains("time"))     { visualEffect = CreateEffectPacket.EFFECT_TIME_STOP;      vTier = 1; }
				if (ab.getTags().contains("smite"))    { visualEffect = CreateEffectPacket.EFFECT_SMITE_FLASH;    vTier = 3; }
				if (ab.getTags().contains("death_bloom")){visualEffect = CreateEffectPacket.EFFECT_DEATH_BLOSSOM; vTier = 6; }
				if (ab.getTags().contains("bloom"))    { visualEffect = CreateEffectPacket.EFFECT_INSPIRE_BLOOM;  vTier = 3; }
				if (ab.getTags().contains("slash"))    { visualEffect = CreateEffectPacket.EFFECT_RECKLESS_SLASH; vTier = 5; }
				if (ab.getTags().contains("shuriken")) { visualEffect = CreateEffectPacket.EFFECT_STAR_SHURIKEN;  vTier = 0; }
				if (ab.getTags().contains("snare_trap")){visualEffect = CreateEffectPacket.EFFECT_SNARE_GEAR;     vTier = 4; }
				if (ab.getTags().contains("explosion")){ visualEffect = CreateEffectPacket.EFFECT_COMBUSTION_TRAP;vTier = 4; }
				if (ab.getTags().contains("warcry"))   { visualEffect = CreateEffectPacket.EFFECT_WAR_CRY_WAVE;   vTier = 5; }
				if (ab.getTags().contains("caltrops")) { visualEffect = CreateEffectPacket.EFFECT_CALTROPS;       vTier = 0; }
				if (ab.getTags().contains("smoke"))    { visualEffect = CreateEffectPacket.EFFECT_SMOKE_POOF;     vTier = 0; }
				if (ab.getTags().contains("sanctuary"))      { visualEffect = CreateEffectPacket.EFFECT_SANCTUARY_DOME;  vTier = 3; }
				if (ab.getTags().contains("vampiric"))       { visualEffect = CreateEffectPacket.EFFECT_VAMPIRIC_LATCH;  vTier = 5; }
				if (ab.getTags().contains("rapier_stab"))    { visualEffect = CreateEffectPacket.EFFECT_RAPIER_STAB;     vTier = 0; }
				if (ab.getTags().contains("low_swing"))      { visualEffect = CreateEffectPacket.EFFECT_LOW_SWING;       vTier = 5; }
				if (ab.getTags().contains("disarm_flourish")){ visualEffect = CreateEffectPacket.EFFECT_DISARM_FLOURISH; vTier = 3; }
				if (ab.getTags().contains("divine_beam"))    { visualEffect = CreateEffectPacket.EFFECT_DIVINE_BEAM;     vTier = 3; }
				if (ab.getTags().contains("fortify_aura"))   { visualEffect = CreateEffectPacket.EFFECT_FORTIFY_AURA;    vTier = 2; }
				if (ab.getTags().contains("ground_pound"))   { visualEffect = CreateEffectPacket.EFFECT_GROUND_POUND;    vTier = 4; }
				if (ab.getTags().contains("beast"))          { visualEffect = CreateEffectPacket.EFFECT_BEAST_CLAWS;     vTier = 5; }
				if (ab.getTags().contains("arcane_aura"))    { visualEffect = CreateEffectPacket.EFFECT_ARCANE_AURA;     vTier = 6; }
				if (ab.getTags().contains("haste"))          { visualEffect = CreateEffectPacket.EFFECT_HASTE_WIND;      vTier = 1; }
				if (ab.getTags().contains("rampage"))        { visualEffect = CreateEffectPacket.EFFECT_RAMPAGE_AURA;    vTier = 5; }
				if (ab.getTags().contains("storm"))          { visualEffect = CreateEffectPacket.EFFECT_STORM_AURA;      vTier = 3; }
				if (ab.getTags().contains("death_pact"))     { visualEffect = CreateEffectPacket.EFFECT_DEATH_PACT_AURA; vTier = 6; }
				short visualDurationMs = 1500;
				if (visualEffect == CreateEffectPacket.EFFECT_SANCTUARY_DOME) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_VAMPIRIC_LATCH) visualDurationMs = 2000;
				else if (visualEffect == CreateEffectPacket.EFFECT_RAPIER_STAB) visualDurationMs = 350;
				else if (visualEffect == CreateEffectPacket.EFFECT_LOW_SWING)   visualDurationMs = 500;
				else if (visualEffect == CreateEffectPacket.EFFECT_DISARM_FLOURISH) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_DIVINE_BEAM) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_FORTIFY_AURA) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_GROUND_POUND) visualDurationMs = 700;
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						visualEffect, effCenter.x, effCenter.y, aoeRadius, visualDurationMs, vTier));
				// Hunter's Mark reticle IS the outline — skip redundant stasis ring.
				if (ab.getTags().contains("outline_ring") && !ab.getTags().contains("mark")) {
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
							CreateEffectPacket.EFFECT_STASIS_FIELD,
							effCenter.x, effCenter.y, aoeRadius, (short) 1500, (byte) 6));
				}
			}
			if (ab.getTags().contains("rain_arrows")) {
				final int ARROW_COUNT = 14;
				final int ARROW_PID = 88;
				final Random rng = ThreadLocalRandom.current();
				for (int i = 0; i < ARROW_COUNT; i++) {
					final double r  = aoeRadius * Math.sqrt(rng.nextDouble());
					final double th = rng.nextDouble() * 2.0 * Math.PI;
					final float offX = (float)(r * Math.cos(th));
					final float offY = (float)(r * Math.sin(th));
					// Bullet ctor stores -angle; angle=0 → (sin,cos)=(0,1) → +Y motion (falling).
					final Vector2f src = new Vector2f(
							effCenter.x + offX, effCenter.y + offY - 280f);
					mgr.addProjectile(targetRealm.getRealmId(), 0L, player.getId(),
							ARROW_PID, -1, src, 0f, (short) 16, 9f, 320f,
							(short) 0, false, new ArrayList<>(),
							(short) 0, (short) 0, player.getId());
				}
			}
			// DAMAGE scaling must run even when baseDamage==0 — many migrated abilities
			// rely solely on stat scaling for their damage.
			int abDamage = ab.getBaseDamage();
			for (AbilityScaling sc : ab.scalingList()) {
				if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
				final int statVal = resolveScalingInput(player, ab, sc);
				abDamage += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
			}
			final short finalDmg = (short) Math.min(Short.MAX_VALUE, Math.max(0, abDamage));
			final boolean armorPierce = ab.getTags().contains("armor_pierce");
			final TextEffect dmgTextEffectBase = armorPierce ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
			// Both branches can run for one cast (e.g. Necro Soul Drain debuffs enemies + buffs allies).
			if (aoeTargeted) {
				int debuffBonusMs = 0;
				if (ab.getScalings() != null) {
					for (AbilityScaling sc : ab.scalingList()) {
						if (!"DURATION".equalsIgnoreCase(sc.getTarget())) continue;
						final int statVal = resolveScalingInput(player, ab, sc);
						debuffBonusMs += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
					}
				}
				final PassiveAbility _cp = player.getClassPassive();
				if (_cp != null && _cp.getId() == 12005 /* Necromancer Cost of Living */) {
					debuffBonusMs += (player.getComputedStats().getWis() / 50) * 1000;
				}
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
								mgr.applyStatusWithFeedback(targetRealm, enemy, EntityType.ENEMY,
										st, aoeEff.getBaseDurationMs() + debuffBonusMs);
							}
						} catch (NumberFormatException ignore) { }
					}
					if (finalDmg > 0) {
						enemy.setHealth(enemy.getHealth() - finalDmg);
						final TextEffect dmgFx = enemy.hasEffect(StatusEffectType.ARMOR_BROKEN)
								? TextEffect.ARMOR_BREAK : dmgTextEffectBase;
						mgr.broadcastTextEffect(EntityType.ENEMY, enemy, dmgFx, "-" + finalDmg);
						if (enemy.getHealth() <= 0) dead.add(enemy);
					}
				}
				for (final Enemy e : dead) {
					mgr.enemyDeath(targetRealm, e);
				}
			}
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
				// Priest "Blessed One" (12008) — +WIS% as a final multiplier on total heal.
				final PassiveAbility _bocp = player.getClassPassive();
				if (_bocp != null && _bocp.getId() == 12008 && healAmount > 0) {
					final int wis = player.getComputedStats().getWis();
					healAmount = healAmount + (healAmount * wis) / 100;
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
							mgr.broadcastTextEffect(EntityType.PLAYER, ally, TextEffect.HEAL, "+" + actual);
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
								mgr.applyStatusWithFeedback(targetRealm, ally, EntityType.PLAYER,
										st, aoeEff.getBaseDurationMs());
							}
						} catch (NumberFormatException ignore) { }
					}
				}
			}
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
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
						final double a = (2 * Math.PI) * i / Math.max(1, total);
						final float r = 28f;
						final Vector2f drop = new Vector2f(
								pos.x + (float) Math.cos(a) * r,
								pos.y + (float) Math.sin(a) * r);
						targetRealm.addLootContainer(new LootContainer(LootTier.BROWN, drop, potion));
					}
				}
			}
			return;
		}
		// Pure-Ability projectile branch — no legacy item backing.
		if (abilityItem == null && ab != null && group != null) {
			final Vector2f dest = new Vector2f(pos.x, pos.y);
			final Vector2f source0 = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
			final float angle = Bullet.getAngle(source0, dest);
			int dmg = ab.getBaseDamage();
			for (AbilityScaling sc : ab.scalingList()) {
				if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
				final int statVal = resolveScalingInput(player, ab, sc);
				dmg += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
			}
			// Fold ability's STATUS_APPLY ENEMIES_HIT into ShotContext so bullets actually
			// apply declared debuffs on contact (otherwise only projectile-group effects fire).
			int _projDebuffBonusMs = 0;
			for (AbilityScaling sc : ab.scalingList()) {
				if (!"DURATION".equalsIgnoreCase(sc.getTarget())) continue;
				final int statVal = resolveScalingInput(player, ab, sc);
				_projDebuffBonusMs += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
			}
			final PassiveAbility _cpProj = player.getClassPassive();
			if (_cpProj != null && _cpProj.getId() == 12005 /* Cost of Living */) {
				_projDebuffBonusMs += (player.getComputedStats().getWis() / 50) * 1000;
			}
			for (AbilityEffect aoeEff : ab.effectList()) {
				if (!"STATUS_APPLY".equalsIgnoreCase(aoeEff.getType())) continue;
				if (!"ENEMIES_HIT".equalsIgnoreCase(aoeEff.getTarget())) continue;
				try {
					final short statusId = Short.parseShort(String.valueOf(aoeEff.getStatusId()).trim());
					final int durMs = (int) Math.min(Integer.MAX_VALUE, aoeEff.getBaseDurationMs() + _projDebuffBonusMs);
					abilityCtx.addOnHitStatus(statusId, durMs);
				} catch (NumberFormatException ignore) { }
			}
			final short rolledDamage = CombatMath.applyShotDamageMods(
					(short) Math.min(Short.MAX_VALUE, Math.max(0, dmg)), abilityCtx);
			for (final Projectile p : group.getProjectiles()) {
				final short offset = (short) (p.getSize() / (short) 2);
				final int totalBullets = 1 + abilityCtx.getExtraProjectiles();
				final float SPREAD = 0.10f;
				final float baseA = angle + Float.parseFloat(p.getAngle());
				for (int i = 0; i < totalBullets; i++) {
					final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
					spawnAbilityBullet(mgr, realmId, player, effPgId, p,
							source0.clone(-offset, -offset), baseA + deltaA, rolledDamage, abilityCtx);
				}
			}
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
			return;
		}
		// Pure self-buff Ability with no projectile/AoE — just apply SELF statuses.
		if (abilityItem == null && ab != null) {
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
			return;
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
					// Ability-data damage path: baseDamage + DAMAGE scalings. Player STR NOT auto-added.
					int dmg = ab.getBaseDamage();
					for (AbilityScaling sc : ab.scalingList()) {
						if (!"DAMAGE".equalsIgnoreCase(sc.getTarget())) continue;
						final int statVal = resolveScalingInput(player, ab, sc);
						dmg += (int) sc.curveEnum().apply(statVal, sc.getCoeff(), sc.getCap());
					}
					rolledDamage = (short) Math.min(Short.MAX_VALUE, Math.max(0, dmg));
				} else {
					rolledDamage = abilityItem.getDamage().getInRange();
					rolledDamage += RealmManagerServer.statByIndex(player.getComputedStats(),
							abilityItem.getScalingStat());
				}
				rolledDamage = CombatMath.applyShotDamageMods(rolledDamage, abilityCtx);
				if (p.getPositionMode() != ProjectilePositionMode.TARGET_PLAYER) {
					source = dest;
				} else {
					// Push spawn ~60px forward along aim line so bullet doesn't materialise on player sprite.
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
				// Symmetric fan around aim line — aim hits the center regardless of extra projectiles.
				{
					final int totalBullets = 1 + abilityCtx.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = angle + Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = CombatMath.applyShotDamageMods(rolledDamage, abilityCtx);
						spawnAbilityBullet(mgr, realmId, player, effPgId, p,
								source.clone(-offset, -offset), baseA + deltaA, rolled, abilityCtx);
					}
				}
			}
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}

		} else if ((abilityItem.getDamage() != null) && (group != null)) {
			final Vector2f dest = new Vector2f(pos.x, pos.y);
			for (final Projectile p : group.getProjectiles()) {

				final short offset = (short) (p.getSize() / (short) 2);
				short rolledDamage = abilityItem.getDamage().getInRange();
				rolledDamage += RealmManagerServer.statByIndex(player.getComputedStats(),
						abilityItem.getScalingStat());
				rolledDamage = CombatMath.applyShotDamageMods(rolledDamage, abilityCtx);
				{
					final int totalBullets = 1 + abilityCtx.getExtraProjectiles();
					final float SPREAD = 0.10f;
					final float baseA = Float.parseFloat(p.getAngle());
					for (int i = 0; i < totalBullets; i++) {
						final float deltaA = (i - (totalBullets - 1) / 2f) * SPREAD;
						final short rolled = CombatMath.applyShotDamageMods(rolledDamage, abilityCtx);
						spawnAbilityBullet(mgr, realmId, player, effPgId, p,
								dest.clone(-offset, -offset), baseA + deltaA, rolled, abilityCtx);
					}
				}
			}

		} else if (effSelfStatus != null) {
			// GROUNDED blocks teleport-class abilities (same veto as shadow_dash).
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& player.hasEffect(StatusEffectType.GROUNDED)) {
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
				return;
			}
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& !targetRealm.getTileManager().collidesAtPosition(pos, player.getSize())
					&& !targetRealm.getTileManager().isVoidTile(pos, 0, 0)) {
				final Vector2f origin = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLINK_GLYPH, origin.x, origin.y, 56f, (short) 700, (byte) 6));
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BLINK_GLYPH, pos.x, pos.y, 56f, (short) 900, (byte) 6));
				player.setPos(pos);
			} else if (!effSelfStatus.equals(StatusEffectType.TELEPORT) && effHasSelfStatus) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
		}
		// Legacy item-script fallback ONLY when no Ability data bound — otherwise tag-based
		// visuals own the cast and running the legacy script doubles them up.
		if (ab == null) {
			final UseableItemScriptBase script = mgr.getItemScript(abilityItem.getItemId());
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

	private static void spawnAbilityBullet(final RealmManagerServer mgr, long realmId, Player player, int projectileGroupId, Projectile p,
			Vector2f src, float angle, short damage, ShotContext ctx) {
		final Bullet b = mgr.addProjectile(realmId, 0L, player.getId(), projectileGroupId,
				p.getProjectileId(), src, angle, p.getSize(), p.getMagnitude(), p.getRange(),
				damage, false, p.getFlags(), p.getAmplitude(), p.getFrequency(), player.getId());
		if (b == null) return;
		final List<ProjectileEffect> merged = new ArrayList<>();
		if (p.getEffects() != null) merged.addAll(p.getEffects());
		if (ctx != null) {
			for (ShotContext.OnHitStatus oh : ctx.getOnHitStatuses()) {
				final ProjectileEffect pe = new ProjectileEffect();
				pe.setEffectId(oh.getEffectId());
				pe.setDuration(oh.getDurationMs());
				merged.add(pe);
			}
		}
		if (!merged.isEmpty()) b.setEffects(merged);
	}

	private static int resolveScalingInput(Player player, Ability ab, AbilityScaling sc) {
		final int idx = sc.statIndex();
		if (idx == 8) {
			return (player == null || ab == null) ? 0 : player.getSkillLevel(ab.getId());
		}
		return RealmManagerServer.statByIndex(player == null ? null : player.getComputedStats(), idx);
	}
}
