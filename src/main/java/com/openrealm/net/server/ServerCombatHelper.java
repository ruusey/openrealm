package com.openrealm.net.server;

import java.time.Instant;
import java.util.UUID;

import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.contants.EntityType;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.TextEffect;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.entity.Enemy;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.entity.item.gem.Gemstone;
import com.openrealm.game.entity.item.gem.GemstoneRegistry;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.ProjectileEffect;
import com.openrealm.game.model.ability.AbilityScaling;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.model.ability.PassiveTrigger;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Combat-side bullet impact handlers extracted from {@link RealmManagerServer}.
 * Mirrors the pattern of {@link ServerCommandHandler} / {@link ServerItemHelper}
 * / {@link ServerGameLogic}: static entry points, {@code mgr} passed in for
 * any realm-state mutation. Logic is byte-for-byte identical to the original
 * — only the {@code this.X} → {@code mgr.X} indirection changed.
 *
 * Owns:
 *   {@link #processPlayerHit} — enemy bullet hits a player.
 *   {@link #processEnemyHit}  — player bullet hits an enemy.
 *   {@link #tryDeflect}       — Knight passive bullet-reflect proc.
 *   {@link #trySpawnBunshinClone} — "Kage Bunshin" clone-on-hit proc — orphaned
 *       legacy passive (id 11013, no class currently binds it). Kept as a
 *       reference implementation; harmless because the passive lookup never
 *       matches under the current 12-class roster.
 */
public final class ServerCombatHelper {

    // ─── Kage Bunshin tuning (unimplemented legacy passive 11013) ─────────
    private static final long CLONE_DURATION_MS      = 3000L;
    private static final long TAUNT_DURATION_MS      = 3000L;
    private static final long INVINCIBLE_DURATION_MS = 3000L;

    // ─── Heavy Debuffer / Duelist "Precision Striker" ──────────────────────
    // Bullets from a player with this passive ignore floor(caster.DEX / N)
    // of the target's DEF before the 15% damage floor is applied.
    private static final int PRECISION_STRIKER_DEX_DIVISOR = 10;

    private ServerCombatHelper() {}

    // ──────────────────────────────────────────────────────────────────────
    //   Entry points
    // ──────────────────────────────────────────────────────────────────────

    /** Enemy bullet hits a player. Resolves Deflect / Light On Your Feet
     *  dodge, damage with armor mitigation, gemstone onPlayerHit hooks,
     *  on-hit statuses, death, and the (orphaned) Kage Bunshin clone proc. */
    public static void processPlayerHit(final RealmManagerServer mgr,
            final long realmId, final Bullet b, final Player p) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        final Player player = targetRealm.getPlayer(p.getId());
        if (player == null) return;
        if (!(RealmManagerServer.circleHit(b, player) && b.isEnemy() && !b.isPlayerHit())) return;

        // Phase 3 — Knight's Deflect passive. DEF-scaled chance to reflect
        // the bullet back at its source enemy instead of taking damage.
        if (tryDeflect(mgr, targetRealm, b, player)) return;

        // Ninja "Light On Your Feet" (12010) — if it's been 30s since the
        // last incoming hit, dodge this one entirely and grant SPEEDY 3s.
        final PassiveAbility ninjaP = player.getClassPassive();
        if (ninjaP != null && ninjaP.getId() == 12010) {
            final long nowMs = Instant.now().toEpochMilli();
            if (nowMs - player.getLastDamageTakenMs() > 30000L) {
                b.setPlayerHit(true);
                targetRealm.getExpiredBullets().add(b.getId());
                targetRealm.removeBullet(b);
                mgr.applyStatusWithFeedback(targetRealm, player, EntityType.PLAYER,
                        StatusEffectType.SPEEDY, 3000L);
                mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "DODGE");
                player.setLastDamageTakenMs(nowMs);
                return;
            }
        }

        final Stats stats = player.getComputedStats();
        b.setPlayerHit(true);
        // Difficulty-scaled raw damage. Dungeon instances have a 1.0-lower
        // threshold than overworld zones so the same difficulty number hits
        // harder inside a dungeon.
        float rawDmg = b.getDamage();
        if (b.getSrcEntityId() != 0L) {
            final Enemy srcEnemy = targetRealm.getEnemies().get(b.getSrcEntityId());
            if (srcEnemy != null) {
                rawDmg *= CombatMath.difficultyDamageMult(srcEnemy.getDifficulty(),
                        targetRealm.isDungeonInstance());
            }
        }
        final boolean armorPiercing = b.hasFlag(ProjectileFlag.ARMOR_PIERCING);
        final boolean armorBroken = player.hasEffect(StatusEffectType.ARMOR_BROKEN);
        // WEAKEN on the firing enemy — outgoing damage reduced by 35%.
        if (b.getSrcEntityId() != 0L) {
            final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
            if (src != null && src.hasEffect(StatusEffectType.WEAKEN)) {
                rawDmg = (short) (rawDmg * 0.65);
            }
        }
        // Defense can only mitigate 85% of incoming damage (15% min-damage floor).
        final short minDmg = CombatMath.minDamageFloor((short) rawDmg);
        short dmgToInflict;
        if (armorPiercing || armorBroken) {
            dmgToInflict = (short) rawDmg;
        } else {
            dmgToInflict = (short) (rawDmg - stats.getDef());
            if (dmgToInflict < minDmg) dmgToInflict = minDmg;
        }

        final TextEffect dmgTextEffect = (armorPiercing || armorBroken)
                ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
        mgr.sendTextEffectToPlayer(player, dmgTextEffect, "-" + dmgToInflict);

        player.setHealth(player.getHealth() - dmgToInflict);
        // Ninja "Light On Your Feet" 30s dodge timer restarts on every real hit.
        player.setLastDamageTakenMs(Instant.now().toEpochMilli());

        // Gemstone onPlayerHit hook — Thorns, on-hit-self triggers, etc.
        if (dmgToInflict > 0) {
            final Enemy attacker = b.getSrcEntityId() != 0L
                    ? targetRealm.getEnemy(b.getSrcEntityId()) : null;
            for (int slot = 0; slot < Player.EQUIPMENT_SLOT_COUNT; slot++) {
                final GameItem eq = player.getInventory()[slot];
                if (eq == null) continue;
                final Gemstone g = GemstoneRegistry.forItem(eq);
                if (g != null) g.onPlayerHit(player, dmgToInflict, attacker, eq);
            }
        }
        // Force the next PlayerState broadcast to ship the post-damage HP.
        mgr.invalidatePlayerStateCache(player.getId());
        // Kage Bunshin clone proc — roll AFTER damage. The clone is an escape
        // tool, not a damage shield.
        trySpawnBunshinClone(mgr, targetRealm, b, player);
        targetRealm.getExpiredBullets().add(b.getId());
        targetRealm.removeBullet(b);

        // Apply on-hit status effects from the projectile's effects list.
        if (b.getEffects() != null) {
            for (final ProjectileEffect pe : b.getEffects()) {
                final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
                if (effectType != null) {
                    p.addEffect(effectType, pe.getDuration());
                    if (effectType == StatusEffectType.PARALYZED) {
                        p.setDx(0); p.setDy(0);
                    }
                    mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, effectType.name());
                }
            }
        }
        if (p.getDeath()) {
            mgr.playerDeath(targetRealm, player);
        }
    }

    /** Player bullet hits an enemy. Resolves armor-pen, Precision Striker,
     *  status amplifiers (DAMAGING, WEAKEN, CURSED, VULNERABLE), per-hit
     *  passive procs (Lethal Wound, Sleight of Hand, Leech Energy), the
     *  gemstone onHitTarget hook, piercing flag, on-hit statuses, and
     *  enemy death. */
    public static void processEnemyHit(final RealmManagerServer mgr,
            final long realmId, final Bullet b, final Enemy e) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        final EnemyModel model = GameDataManager.ENEMIES.get(e.getEnemyId());
        if (targetRealm.hasHitEnemy(b.getId(), e.getId())
                || targetRealm.getExpiredEnemies().contains(e.getId())) return;
        if (e.hasEffect(StatusEffectType.STASIS) || e.hasEffect(StatusEffectType.INVINCIBLE)) return;
        if (!(RealmManagerServer.circleHit(b, e) && !b.isEnemy())) return;

        final boolean armorPiercing = b.hasFlag(ProjectileFlag.ARMOR_PIERCING);
        final boolean armorBroken = e.hasEffect(StatusEffectType.ARMOR_BROKEN);
        final short minDmg = CombatMath.minDamageFloor(b.getDamage());
        short dmgToInflict;
        if (armorPiercing || armorBroken) {
            dmgToInflict = (short) b.getDamage();
        } else {
            // Precision Striker (11014 legacy Heavy Debuffer, 12003 Duelist):
            // grant floor(caster.DEX / 10) of armor penetration.
            int effectiveDef = model.getStats().getDef();
            if (b.getSrcEntityId() != 0L) {
                final Player src = mgr.getPlayerById(b.getSrcEntityId());
                if (src != null) {
                    final PassiveAbility pass = src.getClassPassive();
                    if (pass != null && (pass.getId() == 11014 || pass.getId() == 12003)) {
                        final int pen = src.getComputedStats().getDex() / PRECISION_STRIKER_DEX_DIVISOR;
                        effectiveDef = Math.max(0, effectiveDef - pen);
                    }
                }
            }
            dmgToInflict = (short) (b.getDamage() - effectiveDef);
            if (dmgToInflict < minDmg) dmgToInflict = minDmg;
        }

        // Track damage for loot credit.
        if (b.getSrcEntityId() != 0L && targetRealm.getOverseer() != null) {
            targetRealm.getOverseer().trackDamage(e.getId(), b.getSrcEntityId(), dmgToInflict);
        }

        if (b.getSrcEntityId() != 0L) {
            final Player fromPlayer = mgr.getPlayerById(b.getSrcEntityId());
            if (fromPlayer != null && fromPlayer.hasEffect(StatusEffectType.DAMAGING)) {
                dmgToInflict = (short) (dmgToInflict * 1.5);
            }
            // WEAKEN on the source — outgoing damage reduced by 35%.
            if (fromPlayer != null && fromPlayer.hasEffect(StatusEffectType.WEAKEN)) {
                dmgToInflict = (short) (dmgToInflict * 0.65);
            }
        }
        if (e.hasEffect(StatusEffectType.CURSED)) {
            dmgToInflict = (short) (dmgToInflict * 1.25);
        }
        if (e.hasEffect(StatusEffectType.VULNERABLE)) {
            dmgToInflict = (short) (dmgToInflict * 1.40);
        }

        targetRealm.hitEnemy(b.getId(), e.getId());

        // Phase 3 passive — on-hit triggers.
        if (b.getSrcEntityId() != 0L) {
            final Player srcPlayer = mgr.getPlayerById(b.getSrcEntityId());
            if (srcPlayer != null) {
                final PassiveAbility pa = srcPlayer.getClassPassive();
                // Cultist "Leech Energy" (12011) — unconditional MP restore.
                if (pa != null && pa.getId() == 12011) {
                    final int mpRestore = 1 + (srcPlayer.getComputedStats().getDex() / 100);
                    final int maxMp = srcPlayer.getComputedStats().getMp();
                    if (srcPlayer.getMana() < maxMp) {
                        srcPlayer.setMana(Math.min(maxMp, srcPlayer.getMana() + mpRestore));
                    }
                }
                if (pa != null) {
                    for (PassiveTrigger trig : pa.triggerList()) {
                        if (!"ON_BULLET_HIT_ENEMY".equalsIgnoreCase(trig.getEvent())) continue;
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
                                final int statVal = RealmManagerServer.statByIndex(srcPlayer.getComputedStats(), statIdx);
                                procChance += statVal * sc.getCoeff();
                            }
                            for (AbilityScaling sc : trig.getConditions()) {
                                if ("PROC_CHANCE".equalsIgnoreCase(sc.getTarget()) && sc.getCap() > 0) {
                                    procChance = Math.min(procChance, sc.getCap());
                                }
                            }
                        }
                        if (procChance > 0 && Math.random() < procChance) {
                            if (pa.getId() == 11007) {
                                mgr.applyStatusWithFeedback(targetRealm, e, EntityType.ENEMY,
                                        StatusEffectType.POISONED, 3000);
                            }
                            if (pa.getId() == 11012) {
                                mgr.applyStatusWithFeedback(targetRealm, e, EntityType.ENEMY,
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

        // Gemstone onHitTarget hook — lifesteal, bonus statuses, etc.
        if (b.getSrcEntityId() != 0L && dmgToInflict > 0) {
            final Player srcP = mgr.getPlayerById(b.getSrcEntityId());
            if (srcP != null && srcP.getInventory()[0] != null) {
                final Gemstone g = GemstoneRegistry.forItem(srcP.getInventory()[0]);
                if (g != null) g.onHitTarget(srcP, e, b, dmgToInflict, srcP.getInventory()[0]);
            }
        }
        // Piercing flag: pass-through enemies get one-hit-per-enemy de-dup via
        // hasHitEnemy; player projectiles without pierce stay alive for one
        // additional enemy; everything else expires on first contact.
        if (b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) {
            if (!b.isEnemyHit()) b.setEnemyHit(true);
        } else if (b.hasFlag(ProjectileFlag.PLAYER_PROJECTILE) && !b.isEnemyHit()) {
            b.setEnemyHit(true);
        } else {
            targetRealm.getExpiredBullets().add(b.getId());
            targetRealm.removeBullet(b);
        }
        // Apply on-hit status effects from the projectile's effects list.
        if (b.getEffects() != null) {
            for (final ProjectileEffect pe : b.getEffects()) {
                final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
                if (effectType != null) {
                    e.addEffect(effectType, pe.getDuration());
                    mgr.broadcastTextEffect(targetRealm, EntityType.ENEMY, e,
                            TextEffect.PLAYER_INFO, effectType.name());
                }
            }
        }
        // Damage text at the bullet's CURRENT impact point so fast-moving
        // enemies don't snap the floating number to their new position.
        final TextEffect dmgTextEffect = (armorPiercing || armorBroken)
                ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
        final float impactX = b.getPos().x + b.getSize() * 0.5f;
        final float impactY = b.getPos().y + b.getSize() * 0.5f;
        mgr.broadcastTextEffect(targetRealm, EntityType.ENEMY, e, dmgTextEffect,
                "-" + dmgToInflict, impactX, impactY);
        if (e.getDeath()) {
            targetRealm.getExpiredBullets().add(b.getId());
            mgr.enemyDeath(targetRealm, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //   Passive procs (called from processPlayerHit)
    // ──────────────────────────────────────────────────────────────────────

    /** Knight Deflect passive (ON_PROJECTILE_HIT_SELF + PROC_CHANCE scaling).
     *  Returns true iff the bullet was reflected, in which case the caller
     *  must short-circuit damage application. */
    public static boolean tryDeflect(final RealmManagerServer mgr,
            final Realm targetRealm, final Bullet b, final Player player) {
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
                    final int statVal = RealmManagerServer.statByIndex(player.getComputedStats(), statIdx);
                    double raw = statVal * sc.getCoeff();
                    procChance = sc.getCap() > 0 ? Math.min(raw, sc.getCap()) : raw;
                    break;
                }
            }
            if (procChance <= 0) return false;
            if (Math.random() >= procChance) return false;

            // Procced — redirect the bullet. Clients only learn about bullets
            // on spawn, so mutating in-place doesn't update the client render
            // — expire the original and spawn a fresh player-owned bullet.
            final Vector2f pcenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
            final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
            final Vector2f target;
            if (src != null && !src.getDeath()) {
                target = src.getPos().clone(src.getSize() / 2, src.getSize() / 2);
            } else {
                // Source enemy unknown / dead — flip the bullet 180°.
                final double flipped = b.getAngle() + Math.PI;
                final float fx = (float) Math.sin(flipped) * 100f;
                final float fy = (float) Math.cos(flipped) * 100f;
                target = new Vector2f(pcenter.x + fx, pcenter.y + fy);
            }
            // Reflected damage bonus: 1 + DEF * 0.005, per design doc §6.1.
            final double mul = 1.0 + (player.getComputedStats().getDef() * 0.005);
            final short deflectDamage = (short) Math.min(Short.MAX_VALUE, (int) (b.getDamage() * mul));
            targetRealm.getExpiredBullets().add(b.getId());
            targetRealm.removeBullet(b);
            final float newAngle = Bullet.getAngle(pcenter, target);
            // Triple-up the bullet in a tight fan so the deflect reads as a
            // fanned counter-volley rather than a single round trip.
            final float SPREAD = 0.10f;
            for (int i = -1; i <= 1; i++) {
                mgr.addProjectile(targetRealm.getRealmId(), 0L, player.getId(), b.getProjectileId(),
                        -1, pcenter, newAngle + i * SPREAD,
                        (short) b.getSize(), b.getMagnitude(), 300f,
                        deflectDamage, false, b.getFlags(),
                        (short) 0, (short) 0, player.getId());
            }
            // Deflect visuals — chain-lightning toward attacker, wizard-burst
            // at player center, smaller burst at the attacker's center.
            mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
                    CreateEffectPacket.EFFECT_CHAIN_LIGHTNING,
                    pcenter.x, pcenter.y, target.x, target.y, (short) 500));
            mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                    CreateEffectPacket.EFFECT_WIZARD_BURST,
                    pcenter.x, pcenter.y, 36f, (short) 500, (byte) 5));
            mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                    CreateEffectPacket.EFFECT_WIZARD_BURST,
                    target.x, target.y, 28f, (short) 400, (byte) 5));
            mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "DEFLECT");
            return true;
        }
        return false;
    }

    /** Kage Bunshin clone proc (passive 11013) — spawns a 3s INVINCIBLE+TAUNT
     *  clone that walks toward the attacker. The source player STILL takes
     *  the hit — the clone is an escape tool, not a damage shield. Returns
     *  true iff a clone was actually spawned.
     *
     *  No class currently binds passive 11013 under the post-rewrite roster,
     *  so this never fires in practice. Kept as a reference implementation
     *  of a clone-spawn passive for when (or if) Bunshin gets reintroduced. */
    public static boolean trySpawnBunshinClone(final RealmManagerServer mgr,
            final Realm targetRealm, final Bullet b, final Player player) {
        final PassiveAbility passive = player.getClassPassive();
        if (passive == null) return false;
        boolean isCloneTrigger = false;
        double procChance = 0;
        for (PassiveTrigger trig : passive.triggerList()) {
            if (!"ON_PROJECTILE_HIT_SELF".equalsIgnoreCase(trig.getEvent())) continue;
            // Distinguish from Knight's Deflect (same trigger, different
            // behavior). Match on passive id so a future clone-themed passive
            // doesn't accidentally route through both branches.
            if (passive.getId() != 11013) continue;
            isCloneTrigger = true;
            if (trig.getConditions() == null) break;
            for (AbilityScaling sc : trig.getConditions()) {
                if (!"PROC_CHANCE".equalsIgnoreCase(sc.getTarget())) continue;
                final int statIdx = sc.statIndex();
                if (statIdx < 0) continue;
                final int statVal = RealmManagerServer.statByIndex(player.getComputedStats(), statIdx);
                final double raw = statVal * sc.getCoeff();
                procChance = sc.getCap() > 0 ? Math.min(raw, sc.getCap()) : raw;
                break;
            }
            break;
        }
        if (!isCloneTrigger || procChance <= 0) return false;
        if (Math.random() >= procChance) return false;

        // Compute walk vector toward the attacker. Fall back to away-from-
        // bullet if the source enemy is gone.
        final Vector2f spawnCenter = player.getPos().clone(player.getSize() / 2f, player.getSize() / 2f);
        float vx, vy;
        final Enemy src = (b.getSrcEntityId() != 0L) ? targetRealm.getEnemies().get(b.getSrcEntityId()) : null;
        if (src != null && !src.getDeath()) {
            final Vector2f srcCenter = src.getPos().clone(src.getSize() / 2f, src.getSize() / 2f);
            float dirX = srcCenter.x - spawnCenter.x;
            float dirY = srcCenter.y - spawnCenter.y;
            final float mag = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (mag > 0.001f) { dirX /= mag; dirY /= mag; } else { dirX = 1f; dirY = 0f; }
            vx = dirX; vy = dirY;
        } else {
            // Walk along the bullet's forward heading (sin, cos).
            vx = (float) Math.sin(b.getAngle());
            vy = (float) Math.cos(b.getAngle());
        }
        // Per-tick step matching the source player's movement speed.
        final float tilesPerSec = 4.0f + 5.6f * (player.getComputedStats().getSpd() / 75.0f);
        final float pxPerTick = tilesPerSec * 32.0f / 64.0f;
        final float dx = vx * pxPerTick;
        final float dy = vy * pxPerTick;

        final CharacterClass cls = CharacterClass.valueOf(player.getClassId());
        if (cls == null) return false;
        final long cloneId = Realm.RANDOM.nextLong();
        final Player clone = new Player(cloneId, spawnCenter.clone(), player.getSize(), cls);
        clone.setName(player.getName());
        clone.setHeadless(true);
        clone.setBot(true);
        clone.setAccountUuid(UUID.randomUUID().toString());
        clone.setCharacterUuid(UUID.randomUUID().toString());
        clone.addEffect(StatusEffectType.INVINCIBLE, INVINCIBLE_DURATION_MS);
        clone.addEffect(StatusEffectType.TAUNT_TARGET, TAUNT_DURATION_MS);
        clone.setRight(dx > 0.01f);
        clone.setLeft(dx < -0.01f);
        clone.setDown(dy > 0.01f);
        clone.setUp(dy < -0.01f);

        targetRealm.addPlayer(clone);
        targetRealm.registerClone(cloneId, player.getId(), dx, dy, CLONE_DURATION_MS);

        mgr.enqueueServerPacketToRealm(targetRealm,
                CreateEffectPacket.aoeEffect(CreateEffectPacket.EFFECT_SMOKE_POOF,
                        spawnCenter.x, spawnCenter.y, 40f, (short) 600));
        mgr.sendTextEffectToPlayer(player, TextEffect.PLAYER_INFO, "BUNSHIN");
        return true;
    }
}
