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
import com.openrealm.game.entity.Entity;
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

public final class ServerCombatHelper {

    private static final long CLONE_DURATION_MS      = 3000L;
    private static final long TAUNT_DURATION_MS      = 3000L;
    private static final long INVINCIBLE_DURATION_MS = 3000L;

    private static final int PRECISION_STRIKER_DEX_DIVISOR = 10;

    /** Assassin Imbue Poison: per-hit DoT magnitude = BASE + attacker DEX.
     *  Tuned lighter than the throw-vial poison (T0=150+str over 3s) and
     *  the BLEEDING DoT — meant to chip steadily through a basic-attack
     *  rotation, not nuke. Total damage spreads across the duration in
     *  200ms ticks. */
    private static final int  IMBUE_POISON_BASE         = 80;
    private static final long IMBUE_POISON_DURATION_MS  = 5000L;

    /** Player-vs-player damage scaling inside a PvP realm. Mirrors the design spec
     *  "all weapon/ability damage scaled to 1/10th" so PvP fights last long enough
     *  to be tactical rather than a one-shot exchange. */
    private static final float PVP_DAMAGE_SCALE = 0.1f;

    /** Fraction of a hit's damage re-dealt as a damage-over-time effect when that hit
     *  applies POISONED/BLEEDING (Venom gem, bleed weapons/abilities). Scaled off the
     *  hit rather than the target's max HP so it never melts high-HP bosses. */
    static final float ON_HIT_DOT_FRACTION = 0.5f;

    private ServerCombatHelper() {}

    /** Applies the combat status-effect damage modifiers in the canonical order:
     *  attacker DAMAGING (×1.5) then WEAKEN (×0.65), then target CURSED (×1.25)
     *  then VULNERABLE (×1.40). Centralized so a hit landing on a player and a hit
     *  landing on an enemy modify damage identically — attacker/target may be either
     *  an {@link Enemy} or a {@link Player}. Pass null where there is no attacker. */
    static short applyStatusDamageModifiers(final short base, final Entity attacker, final Entity target) {
        short dmg = base;
        if (attacker != null) {
            if (attacker.hasEffect(StatusEffectType.DAMAGING)) dmg = (short) (dmg * 1.5);
            if (attacker.hasEffect(StatusEffectType.WEAKEN))   dmg = (short) (dmg * 0.65);
        }
        if (target != null) {
            if (target.hasEffect(StatusEffectType.CURSED))     dmg = (short) (dmg * 1.25);
            if (target.hasEffect(StatusEffectType.VULNERABLE)) dmg = (short) (dmg * 1.40);
        }
        return dmg;
    }

    public static void processPlayerHit(final RealmManagerServer mgr,
            final long realmId, final Bullet b, final Player p) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        final Player player = targetRealm.getPlayer(p.getId());
        if (player == null) return;
        if (!RealmManagerServer.circleHit(b, player) || b.isPlayerHit()) return;

        // PvP player-vs-player path: bullet originated from another player. PvE bullets
        // from players never hit other players (gated on b.isEnemy() below).
        if (!b.isEnemy()) {
            if (!targetRealm.isPvp()) return;
            processPvpPlayerHit(mgr, targetRealm, b, player);
            return;
        }

        // Enemy → player path: in a PvP realm, swallow bullets fired by a friendly
        // minion (same team as the target) so own-side waves can't team-kill.
        if (targetRealm.isPvp() && b.getSrcEntityId() != 0L && player.getPvpTeamId() != 0) {
            final Enemy srcEnemy = targetRealm.getEnemies().get(b.getSrcEntityId());
            if (srcEnemy != null && srcEnemy.getTeamId() != 0
                    && srcEnemy.getTeamId() == player.getPvpTeamId()) {
                b.setPlayerHit(true);
                targetRealm.getExpiredBullets().add(b.getId());
                targetRealm.removeBullet(b);
                return;
            }
        }

        if (tryDeflect(mgr, targetRealm, b, player)) return;

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
        if (b.getSrcEntityId() != 0L) {
            final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
            if (src != null && src.hasEffect(StatusEffectType.WEAKEN)) {
                rawDmg = (short) (rawDmg * 0.65);
            }
        }
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
        player.setLastDamageTakenMs(Instant.now().toEpochMilli());

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
        mgr.invalidatePlayerStateCache(player.getId());
        trySpawnBunshinClone(mgr, targetRealm, b, player);
        targetRealm.getExpiredBullets().add(b.getId());
        targetRealm.removeBullet(b);

        if (b.getEffects() != null) {
            for (final ProjectileEffect pe : b.getEffects()) {
                final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
                if (effectType != null) {
                    p.addEffect(effectType, pe.getDuration());
                    if (effectType == StatusEffectType.PARALYZED) {
                        p.setDx(0); p.setDy(0);
                    }
                    // Broadcast so nearby players see the debuff land, mirroring how
                    // enemy debuffs are surfaced — not just to the player who was hit.
                    mgr.broadcastTextEffect(targetRealm, EntityType.PLAYER, player,
                            TextEffect.PLAYER_INFO, effectType.name());
                }
            }
        }
        if (p.getDeath()) {
            mgr.playerDeath(targetRealm, player);
        }
    }

    public static void processEnemyHit(final RealmManagerServer mgr,
            final long realmId, final Bullet b, final Enemy e) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        final EnemyModel model = GameDataManager.ENEMIES.get(e.getEnemyId());
        if (targetRealm.hasHitEnemy(b.getId(), e.getId())
                || targetRealm.getExpiredEnemies().contains(e.getId())) return;
        if (e.hasEffect(StatusEffectType.STASIS) || e.hasEffect(StatusEffectType.INVINCIBLE)) return;
        if (!(RealmManagerServer.circleHit(b, e) && !b.isEnemy())) return;

        // PvP friendly-fire: player bullet hitting an allied minion (same teamId) is a no-op.
        if (targetRealm.isPvp() && e.getTeamId() != 0 && b.getSrcEntityId() != 0L) {
            final Player srcPlayer = mgr.getPlayerById(b.getSrcEntityId());
            if (srcPlayer != null && srcPlayer.getPvpTeamId() != 0
                    && srcPlayer.getPvpTeamId() == e.getTeamId()) {
                targetRealm.hitEnemy(b.getId(), e.getId());
                if (!b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) {
                    targetRealm.getExpiredBullets().add(b.getId());
                    targetRealm.removeBullet(b);
                }
                return;
            }
        }

        final boolean armorPiercing = b.hasFlag(ProjectileFlag.ARMOR_PIERCING);
        final boolean armorBroken = e.hasEffect(StatusEffectType.ARMOR_BROKEN);
        final short minDmg = CombatMath.minDamageFloor(b.getDamage());
        short dmgToInflict;
        if (armorPiercing || armorBroken) {
            dmgToInflict = (short) b.getDamage();
        } else {
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

        final Player fromPlayer = b.getSrcEntityId() != 0L ? mgr.getPlayerById(b.getSrcEntityId()) : null;
        if (b.getSrcEntityId() != 0L) {
            ServerSoulboundHelper.trackDamage(targetRealm, e.getId(), b.getSrcEntityId(), dmgToInflict);
        }

        dmgToInflict = applyStatusDamageModifiers(dmgToInflict, fromPlayer, e);

        // Assassin Imbue Poison: every basic-attack hit lays a fresh poison DoT on
        // the target. Skip if already POISONED so rapid fire doesn't stack 30
        // overlapping ticks per second.
        if (fromPlayer != null && fromPlayer.hasEffect(StatusEffectType.IMBUED_POISON)
                && !e.hasEffect(StatusEffectType.POISONED)) {
            final int dot = IMBUE_POISON_BASE + fromPlayer.getComputedStats().getDex();
            e.addEffect(StatusEffectType.POISONED, IMBUE_POISON_DURATION_MS);
            targetRealm.registerDot(e.getId(), StatusEffectType.POISONED, dot,
                    IMBUE_POISON_DURATION_MS, fromPlayer.getId());
        }

        targetRealm.hitEnemy(b.getId(), e.getId());

        if (b.getSrcEntityId() != 0L) {
            final Player srcPlayer = mgr.getPlayerById(b.getSrcEntityId());
            if (srcPlayer != null) {
                final PassiveAbility pa = srcPlayer.getClassPassive();
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
                            case 11007: procChance = 0.15; break;
                            case 11012: procChance = 0.12; break;
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

        if (b.getSrcEntityId() != 0L && dmgToInflict > 0) {
            final Player srcP = mgr.getPlayerById(b.getSrcEntityId());
            if (srcP != null && srcP.getInventory()[0] != null) {
                final Gemstone g = GemstoneRegistry.forItem(srcP.getInventory()[0]);
                if (g != null) g.onHitTarget(srcP, e, b, dmgToInflict, srcP.getInventory()[0]);
            }
        }
        // PASS_THROUGH_ENEMIES uses hasHitEnemy de-dup; non-piercing bullets expire on first contact.
        if (b.hasFlag(ProjectileFlag.PASS_THROUGH_ENEMIES)) {
            if (!b.isEnemyHit()) b.setEnemyHit(true);
        } else {
            targetRealm.getExpiredBullets().add(b.getId());
            targetRealm.removeBullet(b);
        }
        if (b.getEffects() != null) {
            for (final ProjectileEffect pe : b.getEffects()) {
                final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
                if (effectType != null) {
                    e.addEffect(effectType, pe.getDuration());
                    mgr.broadcastTextEffect(targetRealm, EntityType.ENEMY, e,
                            TextEffect.PLAYER_INFO, effectType.name());
                    // Poison/bleed projectiles (Venom gem, bleed weapons) tick damage
                    // over time, scaled off this hit. Refresh-guarded by registerDot.
                    if (dmgToInflict > 0 && (effectType == StatusEffectType.POISONED
                            || effectType == StatusEffectType.BLEEDING)) {
                        final int dotTotal = Math.max(1, Math.round(dmgToInflict * ON_HIT_DOT_FRACTION));
                        targetRealm.registerDot(e.getId(), effectType, dotTotal,
                                pe.getDuration(), b.getSrcEntityId());
                    }
                }
            }
        }
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

    /** Knight Deflect — returns true iff bullet was reflected (caller must skip damage). */
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

            // Clients only see bullets on spawn — expire the original and spawn a new one.
            final Vector2f pcenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
            final Enemy src = targetRealm.getEnemies().get(b.getSrcEntityId());
            final Vector2f target;
            if (src != null && !src.getDeath()) {
                target = src.getPos().clone(src.getSize() / 2, src.getSize() / 2);
            } else {
                final double flipped = b.getAngle() + Math.PI;
                final float fx = (float) Math.sin(flipped) * 100f;
                final float fy = (float) Math.cos(flipped) * 100f;
                target = new Vector2f(pcenter.x + fx, pcenter.y + fy);
            }
            final double mul = 1.0 + (player.getComputedStats().getDef() * 0.005);
            final short deflectDamage = (short) Math.min(Short.MAX_VALUE, (int) (b.getDamage() * mul));
            targetRealm.getExpiredBullets().add(b.getId());
            targetRealm.removeBullet(b);
            final float newAngle = Bullet.getAngle(pcenter, target);
            final float SPREAD = 0.10f;
            for (int i = -1; i <= 1; i++) {
                mgr.addProjectile(targetRealm.getRealmId(), 0L, player.getId(), b.getProjectileId(),
                        -1, pcenter, newAngle + i * SPREAD,
                        (short) b.getSize(), b.getMagnitude(), 300f,
                        deflectDamage, false, b.getFlags(),
                        (short) 0, (short) 0, player.getId());
            }
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

    /** Kage Bunshin clone proc (passive 11013) — orphaned, no class currently binds it. */
    public static boolean trySpawnBunshinClone(final RealmManagerServer mgr,
            final Realm targetRealm, final Bullet b, final Player player) {
        final PassiveAbility passive = player.getClassPassive();
        if (passive == null) return false;
        boolean isCloneTrigger = false;
        double procChance = 0;
        for (PassiveTrigger trig : passive.triggerList()) {
            if (!"ON_PROJECTILE_HIT_SELF".equalsIgnoreCase(trig.getEvent())) continue;
            // Same trigger as Knight Deflect — match on passive id to disambiguate.
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
            vx = (float) Math.sin(b.getAngle());
            vy = (float) Math.cos(b.getAngle());
        }
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

    /** Player-vs-player damage path used only inside a PvP realm. Applies the
     *  {@link #PVP_DAMAGE_SCALE} multiplier, skips friendly fire on self/teammates,
     *  bypasses defense (raw scaled damage is the design intent), and routes lethal
     *  hits through {@link RealmManagerServer#playerDeath} so the PvP no-permadeath
     *  short-circuit there can hand off to {@code PvpMatchManager}. */
    private static void processPvpPlayerHit(final RealmManagerServer mgr,
            final Realm targetRealm, final Bullet b, final Player player) {
        if (b.getSrcEntityId() == player.getId()) return;
        final Player srcPlayer = mgr.getPlayerById(b.getSrcEntityId());
        if (srcPlayer != null && srcPlayer.getPvpTeamId() != 0
                && srcPlayer.getPvpTeamId() == player.getPvpTeamId()) {
            return;
        }
        if (player.hasEffect(StatusEffectType.INVINCIBLE)) return;

        b.setPlayerHit(true);
        short dmgToInflict = (short) Math.max(1, Math.round(b.getDamage() * PVP_DAMAGE_SCALE));
        // Status effects modify the hit exactly as they do on an enemy target:
        // attacker DAMAGING/WEAKEN, victim CURSED/VULNERABLE. PvP already bypasses
        // DEF, so a victim's ARMOR_BROKEN adds no extra damage — only its blue
        // ARMOR_BREAK text, which must still appear.
        dmgToInflict = applyStatusDamageModifiers(dmgToInflict, srcPlayer, player);
        if (dmgToInflict < 1) dmgToInflict = 1;
        final TextEffect dmgFx = player.hasEffect(StatusEffectType.ARMOR_BROKEN)
                ? TextEffect.ARMOR_BREAK : TextEffect.DAMAGE;
        // Broadcast (not just-to-target) so the SHOOTER sees the damage number — PvE flow
        // uses broadcastTextEffect for the same reason on enemy hits.
        final float impactX = b.getPos().x + b.getSize() * 0.5f;
        final float impactY = b.getPos().y + b.getSize() * 0.5f;
        mgr.broadcastTextEffect(targetRealm, EntityType.PLAYER, player,
                dmgFx, "-" + dmgToInflict, impactX, impactY);
        // Visual impact effect so the bullet doesn't look like it passes through.
        mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.aoeEffect(
                CreateEffectPacket.EFFECT_WIZARD_BURST,
                impactX, impactY, 24f, (short) 280, (byte) 1));
        player.setHealth(player.getHealth() - dmgToInflict);
        player.setLastDamageTakenMs(Instant.now().toEpochMilli());

        // Apply projectile-borne debuffs (ability STATUS_APPLY ENEMIES_HIT lands here too).
        // POISONED/BLEEDING become ticking DoTs via PvpEffectsManager; every other
        // status applies plainly. Broadcast the effect name (not just to the victim) so
        // the attacker sees the debuff land — matches how enemy debuffs are surfaced.
        if (b.getEffects() != null) {
            for (final ProjectileEffect pe : b.getEffects()) {
                final StatusEffectType effectType = StatusEffectType.valueOf(pe.getEffectId());
                if (effectType == null) continue;
                if (effectType == StatusEffectType.POISONED) {
                    PvpEffectsManager.applyPoison(mgr, targetRealm, player, srcPlayer, pe.getDuration());
                    continue;
                }
                if (effectType == StatusEffectType.BLEEDING) {
                    PvpEffectsManager.applyBleed(mgr, targetRealm, player, srcPlayer, pe.getDuration());
                    continue;
                }
                player.addEffect(effectType, pe.getDuration());
                if (effectType == StatusEffectType.PARALYZED) {
                    player.setDx(0); player.setDy(0);
                }
                mgr.broadcastTextEffect(targetRealm, EntityType.PLAYER, player,
                        TextEffect.PLAYER_INFO, effectType.name());
            }
        }

        // Assassin Imbue Poison: an attacker carrying it poisons on every basic hit,
        // mirroring the enemy path. PvpEffectsManager refreshes rather than stacks.
        if (srcPlayer != null && srcPlayer.hasEffect(StatusEffectType.IMBUED_POISON)
                && !player.hasEffect(StatusEffectType.POISONED)) {
            PvpEffectsManager.applyPoison(mgr, targetRealm, player, srcPlayer, IMBUE_POISON_DURATION_MS);
        }

        // Gemstone on-hit hooks fire in PvP exactly as in PvE: the attacker's weapon
        // gem reacts to dealing damage (e.g. Vampiric lifesteal) and the victim's gems
        // react to taking it (e.g. Thorns reflect).
        if (dmgToInflict > 0) {
            final GameItem weapon = srcPlayer != null ? srcPlayer.getInventory()[0] : null;
            if (weapon != null) {
                final Gemstone wg = GemstoneRegistry.forItem(weapon);
                if (wg != null) wg.onHitTarget(srcPlayer, player, b, dmgToInflict, weapon);
            }
            for (int slot = 0; slot < Player.EQUIPMENT_SLOT_COUNT; slot++) {
                final GameItem eq = player.getInventory()[slot];
                if (eq == null) continue;
                final Gemstone g = GemstoneRegistry.forItem(eq);
                if (g != null) g.onPlayerHit(player, dmgToInflict, srcPlayer, eq);
            }
        }

        mgr.invalidatePlayerStateCache(player.getId());
        targetRealm.getExpiredBullets().add(b.getId());
        targetRealm.removeBullet(b);

        // A victim gem (e.g. Thorns) can damage the attacker — route a lethal reflect
        // through playerDeath so PvP death handling fires for them too.
        if (srcPlayer != null && srcPlayer.getDeath()) {
            mgr.playerDeath(targetRealm, srcPlayer);
        }
        if (player.getDeath()) {
            mgr.playerDeath(targetRealm, player);
        }
    }
}
