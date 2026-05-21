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

/**
 * Ability resolution — branches across SELF buffs, AoE enemy / ally, projectile
 * spawns, legacy-item-backed abilities, and the cast-time gates. Extracted
 * from {@link RealmManagerServer} so the ~900 lines of branching logic live
 * next to the other ServerXxxHelper handlers and the realm manager stops
 * being a god class.
 *
 * Logic is byte-for-byte identical to the original; only the {@code this.X}
 * → {@code mgr.X} indirection changed.
 */
@Slf4j
public final class ServerAbilityHelper {

    private ServerAbilityHelper() {}

	public static void useAbility(final RealmManagerServer mgr, final long realmId, final long playerId, final Vector2f pos,
			final byte abilityIndex, final boolean isCastResolution) {
		final Realm targetRealm = mgr.getRealms().get(realmId);

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
			final Long lastAbilityUsage = mgr.getPlayerAbilityState().get(playerId);
			if (lastAbilityUsage == null
					|| (now - lastAbilityUsage >= effect.getCooldownDuration())) {
				mgr.getPlayerAbilityState().put(playerId, now);
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
			// Force the next PlayerStatePacket to ship even if a healer regen
			// happens to restore mana back to the cached value before the next
			// 8Hz mark. Without this the client's predicted-deduction view
			// stays low because equalsState() sees the post-regen mana ==
			// cached mana and skips the send — and the client then gates
			// further casts on its stale-low local value.
			mgr.invalidatePlayerStateCache(player.getId());
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
			mgr.enqueueServerPacketToRealm(targetRealm,
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
			// Resolve DURATION-target scalings once so each SELF status here
			// (and the projectile/aoe branches farther down) extends correctly
			// by the caster's stat. Without this Ninja Smokebomb's 13.33 DEX
			// coeff DURATION scaling was discarded and INVISIBLE/SPEEDY stayed
			// at their raw baseDurationMs no matter how much DEX the player had.
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

		// Phase 1B: ability is class-bound, not equipped. Abilities have no
		// gemstone socket today, so the ShotContext is created empty — if a
		// future kit lets a Gemstone modify ability projectiles, populate
		// here. Null-guard for classes with no legacy ability item (Heavy_*).
		final ShotContext abilityCtx = new ShotContext();
		if (abilityItem != null) {
			final Gemstone g = GemstoneRegistry.forItem(abilityItem);
			if (g != null) g.modifyShot(abilityCtx, player, abilityItem);
		}

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
			// Holy Beam / Divine Verdict — single big paladin-seal beam
			// descending at the cursor. Avoid KNIGHT_SHOCKWAVE as the impact
			// (case 11 is directional and renders as a forward thrust arrow
			// even when emitted via aoeEffect, producing a stray gold arrow).
			// WIZARD_BURST is radial so it reads as a ground-impact flash.
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
					CreateEffectPacket.EFFECT_PALADIN_SEAL,
					pos.x, pos.y, 130f, (short) 1400, (byte) 3));
			mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
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
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
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
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
							CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
							fx0, fy0, fx1, fy1, (short) 280));
				}
			}

			// soul_harvest spawn removed 2026-05-19 — see TODO at top of file.

			// "taunt_visual" — small red circle blink at player + "TAUNTING" text.
			if (ab.getTags().contains("taunt_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_TAUNT_ROAR, pc.x, pc.y, 44f, (short) 700, (byte) 5));
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "TAUNTING");
			}

			// "brace_visual" — dedicated renderer case (BRACE_STANCE): a small
			// translucent shield-arc in front of the player + 4 ground tick marks.
			// Reads as "raise shield / brace for impact", not a circle.
			if (ab.getTags().contains("brace_visual")) {
				final Vector2f pc = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						CreateEffectPacket.EFFECT_BRACE_STANCE, pc.x, pc.y, 56f, (short) 700, (byte) 1));
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "BRACED");
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
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
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
					mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
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
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
						CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
						origin.x, origin.y, endCenter.x, endCenter.y, (short) 500));
				// Faint smoke at the origin so the "vanish here / appear there"
				// read is sharper than just the streak.
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
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
					mgr.addProjectile(realmId, 0L, player.getId(), groupId,
							0, src, baseA, (short) 22, 6f, 2048f,
							damage, false, flags, (short) 0, (short) 0, player.getId());
				}
			}

			// blade_orbit + blade_blender spawns removed 2026-05-19 — see TODO at top of file.
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
				// Bespoke phase-3 visuals — hand-tuned procedural effects in
				// renderer.js + PlayState.java. Tier byte is mostly cosmetic.
				if (ab.getTags().contains("sanctuary"))      { visualEffect = CreateEffectPacket.EFFECT_SANCTUARY_DOME;  vTier = 3; } // golden dome
				if (ab.getTags().contains("vampiric"))       { visualEffect = CreateEffectPacket.EFFECT_VAMPIRIC_LATCH;  vTier = 5; } // red drain
				// Heavy class visuals (2026-05-14)
				if (ab.getTags().contains("rapier_stab"))    { visualEffect = CreateEffectPacket.EFFECT_RAPIER_STAB;     vTier = 0; } // silver
				if (ab.getTags().contains("low_swing"))      { visualEffect = CreateEffectPacket.EFFECT_LOW_SWING;       vTier = 5; } // red steel
				if (ab.getTags().contains("disarm_flourish")){ visualEffect = CreateEffectPacket.EFFECT_DISARM_FLOURISH; vTier = 3; } // gold + white
				if (ab.getTags().contains("divine_beam"))    { visualEffect = CreateEffectPacket.EFFECT_DIVINE_BEAM;     vTier = 3; } // gold pillar
				if (ab.getTags().contains("fortify_aura"))   { visualEffect = CreateEffectPacket.EFFECT_FORTIFY_AURA;    vTier = 2; } // green regen
				if (ab.getTags().contains("ground_pound"))   { visualEffect = CreateEffectPacket.EFFECT_GROUND_POUND;    vTier = 4; } // brown dust
				// 2026-05-19 — extra tag mappings so each of the 12 new classes can
				// pick a within-class unique cast visual without sharing icons.
				if (ab.getTags().contains("beast"))          { visualEffect = CreateEffectPacket.EFFECT_BEAST_CLAWS;     vTier = 5; } // primal claws
				if (ab.getTags().contains("arcane_aura"))    { visualEffect = CreateEffectPacket.EFFECT_ARCANE_AURA;     vTier = 6; } // purple arcane
				if (ab.getTags().contains("haste"))          { visualEffect = CreateEffectPacket.EFFECT_HASTE_WIND;      vTier = 1; } // wind streak
				if (ab.getTags().contains("rampage"))        { visualEffect = CreateEffectPacket.EFFECT_RAMPAGE_AURA;    vTier = 5; } // berserker red
				if (ab.getTags().contains("storm"))          { visualEffect = CreateEffectPacket.EFFECT_STORM_AURA;      vTier = 3; } // storm clouds
				if (ab.getTags().contains("death_pact"))     { visualEffect = CreateEffectPacket.EFFECT_DEATH_PACT_AURA; vTier = 6; } // dark crimson
				// A few effects benefit from a longer-than-default on-screen
				// life (Sanctuary's INVINCIBLE buff lasts 5s). Bumping the
				// per-packet duration keeps the visual matched to the gameplay
				// window.
				short visualDurationMs = 1500;
				if (visualEffect == CreateEffectPacket.EFFECT_SANCTUARY_DOME) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_VAMPIRIC_LATCH) visualDurationMs = 2000;
				// Heavy class visuals — match life to the gameplay flavor
				else if (visualEffect == CreateEffectPacket.EFFECT_RAPIER_STAB) visualDurationMs = 350;
				else if (visualEffect == CreateEffectPacket.EFFECT_LOW_SWING)   visualDurationMs = 500;
				else if (visualEffect == CreateEffectPacket.EFFECT_DISARM_FLOURISH) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_DIVINE_BEAM) visualDurationMs = 900;
				else if (visualEffect == CreateEffectPacket.EFFECT_FORTIFY_AURA) visualDurationMs = 5000;
				else if (visualEffect == CreateEffectPacket.EFFECT_GROUND_POUND) visualDurationMs = 700;
				mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
						visualEffect, effCenter.x, effCenter.y, aoeRadius, visualDurationMs, vTier));
				// "outline_ring" tag — extra outline at radius. For Hunter's Mark
				// the reticle IS the outline so skip the redundant stasis ring.
				if (ab.getTags().contains("outline_ring") && !ab.getTags().contains("mark")) {
					mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
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
					mgr.addProjectile(targetRealm.getRealmId(), 0L, player.getId(),
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
			// Enemy branch — runs whenever the ability is aoe_targeted. Used to
			// be gated by `!aoeAlly` (mutually exclusive enemy/ally branches),
			// which broke abilities that legitimately do BOTH — e.g. Necro Soul
			// Drain debuffs nearby enemies AND grants MANA_FOUNT to nearby
			// allies in one cast. The effect/scaling lists already filter by
			// target (ENEMIES_HIT vs ALLIES_HIT), so running both branches is
			// safe and matches the data.
			if (aoeTargeted) {
				// Compute the debuff-duration bonus ONCE per cast: ability's
				// DURATION-target scalings (e.g. Wither's WIS*20) + the
				// Necromancer "Cost of Living" passive's WIS/50s extension to
				// any party debuff the player applies. Without this, Wither's
				// status durations stayed at their raw baseDurationMs and the
				// passive was a silent no-op (passives.json had no triggers).
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
				// Priest "Blessed One" (12008) — healing this player applies is
				// amplified by (WIS)%. Applied AFTER scalings so it acts as a
				// final multiplier on the total heal, not just the flat base.
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
			// Self-effect from STATUS_APPLY SELF (already derived above).
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
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
		// New-system pure-Ability projectile branch (2026-05-18) — no legacy
		// GameItem backing. Fires when the ability has a PROJECTILE_GROUP
		// effect and ab.getBaseDamage() > 0 (damage comes from Ability data).
		// Spawns at the player's center, oriented toward cursor.
		// Pure-Ability projectile branch — fires whenever an Ability declares a
		// PROJECTILE_GROUP and no legacy item backs it. Damage comes from
		// baseDamage + DAMAGE scalings (an ability with scalings only — e.g.
		// Fire Breath at "scalings: [WIS coeff 1.2 -> DAMAGE]" and no flat
		// baseDamage — was previously skipped because we gated on baseDamage>0,
		// so the cast resolved as a no-op despite obviously firing).
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
			// Resolve DURATION-target scalings + Necromancer "Cost of Living"
			// passive bonus, then fold the ability's STATUS_APPLY ENEMIES_HIT
			// entries into the ShotContext so spawned bullets actually apply
			// the declared debuffs on contact. Previously the projectile
			// branch only read p.getEffects() from the projectile-group def,
			// so any STATUS_APPLY listed on the Ability itself was a no-op
			// (Necro Wretch's WEAKEN + SLOWED never landed).
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
			// Self statuses on the projectile-bearing ability (e.g. Bolas Throw doesn't have any,
			// but pattern accommodates future kits like "fire while ARMORED").
			if (effHasSelfStatus && effSelfStatus != null) {
				mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER, effSelfStatus, effSelfDurationMs);
				for (Object[] xs : extraSelfStatuses) {
					mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
							(StatusEffectType) xs[0], (Integer) xs[1]);
				}
			}
			return;
		}
		// Pure self-buff Ability with no projectile and no AoE tag — just
		// apply SELF statuses (visual_at_self tag, if any, already emitted
		// earlier in this method). Without this, line 3230 below NPEs on
		// abilityItem.getDamage().
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
					// Ability item scaling — same stat indirection as weapons.
					rolledDamage += RealmManagerServer.statByIndex(player.getComputedStats(),
							abilityItem.getScalingStat());
				}
				rolledDamage = CombatMath.applyShotDamageMods(rolledDamage, abilityCtx);
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
			// Apply self-effect if present (e.g., warrior helmet SPEEDY buff)
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
				// Ability item scaling — defaults to STR (4) for legacy items.
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

			// If the ability is non damaging or script-only (rogue cloak, priest tome, sorcerer scepter,
			// wizard blink) — drive off the derived effect so each hotbar slot can have its own.
		} else if (effSelfStatus != null) {
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& player.hasEffect(StatusEffectType.GROUNDED)) {
				// GROUNDED also blocks wizard-blink / sorcerer-flicker style
				// teleports — same veto as shadow_dash.
				mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "GROUNDED");
				return;
			}
			if (effSelfStatus.equals(StatusEffectType.TELEPORT)
					&& !targetRealm.getTileManager().collidesAtPosition(pos, player.getSize())
					&& !targetRealm.getTileManager().isVoidTile(pos, 0, 0)) {
				// Emit a violet runic glyph at BOTH origin and destination so
				// the teleport reads as "vanish here / appear there".
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
		// Invoke any item specific scripts — ONLY when no Ability data is
		// bound for the slot (legacy fallback). When a new Ability is in
		// play, the tag-based visuals (taunt_visual, brace_visual,
		// knight_slam, etc.) own the cast effect; running the legacy item
		// script on top emitted a second visual for every ability — for
		// Knight that was the shield-bash forward-thrust arrow firing
		// alongside Taunt/Brace/Phalanx and making all four casts look the
		// same.
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
