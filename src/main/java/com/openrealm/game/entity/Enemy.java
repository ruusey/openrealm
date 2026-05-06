package com.openrealm.game.entity;

import java.util.List;
import java.util.UUID;

import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.ProjectilePositionMode;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.AttackPattern;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.EnemyPhase;
import com.openrealm.game.model.MovementPattern;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.script.EnemyScriptBase;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;
import com.openrealm.util.WorkerThread;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class Enemy extends Entity {
    private static final int IDLE_FRAMES = 10;
    private static final float CHASE_SPEED = 1.4f;
    /** Hard cap on any enemy movement speed (tiles/sec). */
    private static final float MAX_ENEMY_SPEED = 5.0f;
    protected EnemyModel model;
    protected int chaseRange;
    protected int attackRange;
    protected int xOffset;
    protected int yOffset;

    private long lastShotTick = 0;
    private int enemyId;
    private int weaponId = -1;
    private int idleTime = 0;
    private float difficulty = 1.0f;
    private Stats stats;

    // Phase-based state
    private float orbitAngle = 0f;
    private boolean charging = false;
    private long chargePauseUntil = 0;
    private Vector2f spawnPos = null;
    private long[] attackCooldowns = null;
    private float[] attackAngleAccumulators = null;
    private String lastPhaseName = null;
    private long phaseTransitionUntil = 0;
    private static final long PHASE_TRANSITION_DURATION_MS = 1200;
    private String uuid;

    // Marks enemies created by an admin command (/spawn, /event, etc.) so
    // /clearspawn can wipe them without touching map-static NPCs. Defaults
    // false; never persisted.
    private transient boolean adminSpawned = false;

    public Enemy() {
        super(0, null, 0);
    }

    public Enemy(long id, int enemyId, Vector2f origin, int size, int weaponId) {
        super(id, origin, size);
        this.model = GameDataManager.ENEMIES.get(enemyId);
        this.enemyId = enemyId;
        this.weaponId = weaponId;
        this.stats = this.model.getStats().clone();
        this.health = stats.getHp();
        this.mana = stats.getMp();
        if (origin != null) {
            this.spawnPos = origin.clone();
        }
        // Initialize fields previously only in Monster subclass
        this.chaseRange = (int) this.model.getChaseRange();
        this.attackRange = (int) this.model.getAttackRange();
        this.right = true;
        this.uuid = UUID.randomUUID().toString();
    }

    public void applyStats(Stats stats) {
        this.health = stats.getHp();
        this.mana = stats.getMp();
        this.stats.setHp(stats.getHp());
        this.stats.setMp(stats.getMp());
        this.stats.setDef(stats.getDef());
        this.stats.setAtt(stats.getAtt());
        this.stats.setSpd(stats.getSpd());
        this.stats.setDex(stats.getDex());
        this.stats.setVit(stats.getVit());
        this.stats.setWis(stats.getWis());
    }

    @Override
    public int getHealth() {
        return this.health;
    }

    @Override
    public int getMana() {
        return this.mana;
    }

    public int getMaxHealth() {
        return (this.stats != null) ? this.stats.getHp() : this.health;
    }

    // ========== PHASE RESOLUTION ==========

    private EnemyPhase getActivePhase() {
        List<EnemyPhase> phases = this.model.getPhases();
        if (phases == null || phases.isEmpty()) return null;
        float hpPct = (this.stats.getHp() > 0) ? (float) this.health / (float) this.stats.getHp() : 0f;
        for (int i = phases.size() - 1; i >= 0; i--) {
            if (hpPct <= phases.get(i).getHpThreshold()) {
                return phases.get(i);
            }
        }
        return phases.get(0);
    }

    private float getPhaseSpeed(EnemyPhase phase) {
        float speed;
        if (phase != null && phase.getMovement() != null) {
            speed = phase.getMovement().getSpeed();
        } else {
            speed = (this.model.getMaxSpeed() > 0) ? this.model.getMaxSpeed() : CHASE_SPEED;
        }
        if (phase != null && phase.getMovement() != null
                && "CHASE".equalsIgnoreCase(phase.getMovement().getType())) {
            final List<EnemyPhase> phases = (this.model != null) ? this.model.getPhases() : null;
            if (phases != null && phases.size() >= 3) {
                int idx = -1;
                for (int i = 0; i < phases.size(); i++) {
                    if (phases.get(i) == phase) { idx = i; break; }
                }
                if (idx == 1) {
                    final EnemyPhase phase3 = phases.get(2);
                    if (phase3 != null && phase3.getMovement() != null) {
                        speed = Math.min(speed, phase3.getMovement().getSpeed() * 0.5f);
                    }
                }
            }
        }
        if (this.hasEffect(StatusEffectType.SLOWED)) speed *= 0.5f;
        return Math.min(speed, MAX_ENEMY_SPEED);
    }

    // ========== MOVEMENT PATTERNS ==========

    private void applyMovement(Player player, EnemyPhase phase) {
        if (player == null || player.hasEffect(StatusEffectType.INVISIBLE)) {
            this.dx = 0;
            this.dy = 0;
            this.up = false;
            this.down = false;
            this.left = false;
            this.right = false;
            return;
        }

        MovementPattern mv = (phase != null) ? phase.getMovement() : null;
        String type = (mv != null) ? mv.getType() : "CHASE";
        float speed = getPhaseSpeed(phase);
        float dist = this.pos.distanceTo(player.pos);

        switch (type) {
            case "ORBIT":
                moveOrbit(player, speed, mv.getRadius(), mv.getDirection());
                break;
            case "STRAFE":
                moveStrafe(player, speed, mv.getPreferredRange());
                break;
            case "CHARGE":
                moveCharge(player, speed, mv.getChargeDistanceMin(), mv.getPauseMs());
                break;
            case "FLEE":
                moveFlee(player, speed, mv.getFleeRange());
                break;
            case "WANDER":
                moveWander(speed);
                break;
            case "ANCHOR":
                moveAnchor(player, speed, mv.getAnchorRadius());
                break;
            case "FIGURE_EIGHT":
                moveFigureEight(player, speed, mv.getRadius());
                break;
            case "CHASE":
            default:
                moveChase(player, speed);
                break;
        }
    }

    private void moveChase(Player player, float speed) {
        float dist = this.pos.distanceTo(player.pos);
        if (dist < this.chaseRange && dist >= this.attackRange) {
            float angle = (float) Math.atan2(player.pos.y - this.pos.y, player.pos.x - this.pos.x);
            float wobble = (float) Math.sin(this.orbitAngle * 3) * 0.4f;
            this.orbitAngle += 0.05f;
            angle += wobble;
            this.dx = (float) Math.cos(angle) * speed;
            this.dy = (float) Math.sin(angle) * speed;
            updateDirectionFlags();
        } else if (dist < this.attackRange) {
            float angle = (float) Math.atan2(this.pos.y - player.pos.y, this.pos.x - player.pos.x);
            float tangent = angle + (float) (Math.PI / 2);
            this.dx = (float) Math.cos(tangent) * speed * 0.3f;
            this.dy = (float) Math.sin(tangent) * speed * 0.3f;
            updateDirectionFlags();
        }
    }

    private void moveOrbit(Player player, float speed, float radius, String dir) {
        float dist = this.pos.distanceTo(player.pos);
        boolean cw = "CW".equalsIgnoreCase(dir);

        if (Math.abs(dist - radius) > 10) {
            if (dist > radius) {
                setDirectionToward(player.pos, speed);
            } else {
                setDirectionAway(player.pos, speed);
            }
        }

        float angleToPlayer = (float) Math.atan2(this.pos.y - player.pos.y, this.pos.x - player.pos.x);
        float tangentAngle = cw ? (angleToPlayer + (float) (Math.PI / 2)) : (angleToPlayer - (float) (Math.PI / 2));
        float tangentX = (float) Math.cos(tangentAngle) * speed;
        float tangentY = (float) Math.sin(tangentAngle) * speed;

        float radialWeight = Math.min(1f, Math.abs(dist - radius) / 30f);
        this.dx = this.dx * radialWeight + tangentX * (1f - radialWeight);
        this.dy = this.dy * radialWeight + tangentY * (1f - radialWeight);
        updateDirectionFlags();
    }

    private void moveStrafe(Player player, float speed, float preferredRange) {
        float dist = this.pos.distanceTo(player.pos);

        if (dist > preferredRange + 20) {
            setDirectionToward(player.pos, speed * 0.6f);
        } else if (dist < preferredRange - 20) {
            setDirectionAway(player.pos, speed * 0.6f);
        } else {
            float angle = (float) Math.atan2(this.pos.y - player.pos.y, this.pos.x - player.pos.x);
            boolean strafeRight = ((int)(this.orbitAngle / 3.0f) % 2) == 0;
            float strafeAngle = angle + (strafeRight ? (float)(Math.PI / 2) : (float)(-Math.PI / 2));
            this.orbitAngle += 0.016f;
            this.dx = (float) Math.cos(strafeAngle) * speed;
            this.dy = (float) Math.sin(strafeAngle) * speed;
            updateDirectionFlags();
        }
    }

    private void moveCharge(Player player, float speed, float chargeDistMin, int pauseMs) {
        long now = System.currentTimeMillis();
        if (now < this.chargePauseUntil) {
            this.dx = 0;
            this.dy = 0;
            return;
        }

        float dist = this.pos.distanceTo(player.pos);
        if (!this.charging && dist < this.chaseRange) {
            this.charging = true;
        }

        if (this.charging) {
            setDirectionToward(player.pos, Math.min(speed * 1.3f, MAX_ENEMY_SPEED));
            if (dist < chargeDistMin) {
                this.charging = false;
                this.chargePauseUntil = now + pauseMs;
                this.dx = 0;
                this.dy = 0;
            }
        }
    }

    private void moveFlee(Player player, float speed, float fleeRange) {
        float dist = this.pos.distanceTo(player.pos);
        if (dist < fleeRange) {
            setDirectionAway(player.pos, speed);
        } else if (dist < this.chaseRange && dist >= this.attackRange) {
            setDirectionToward(player.pos, speed * 0.5f);
        }
    }

    private void moveWander(float speed) {
        int wanderInterval = 30 + (int)(Math.abs(this.getId()) % 60);
        if (this.idleTime >= wanderInterval) {
            float angle = Realm.RANDOM.nextFloat() * (float) (Math.PI * 2);
            this.dx = (float) Math.cos(angle) * speed;
            this.dy = (float) Math.sin(angle) * speed;
            updateDirectionFlags();
            this.idleTime = 0;
        } else {
            this.idleTime++;
            float curve = (float) Math.sin(this.idleTime * 0.1f) * speed * 0.15f;
            this.dx += curve * 0.1f;
            updateDirectionFlags();
        }
    }

    private void moveAnchor(Player player, float speed, float anchorRadius) {
        if (this.spawnPos == null) {
            this.spawnPos = this.pos.clone();
        }
        float distFromSpawn = this.pos.distanceTo(this.spawnPos);
        float distToPlayer = this.pos.distanceTo(player.pos);

        if (distFromSpawn > anchorRadius) {
            setDirectionToward(this.spawnPos, speed * 1.2f);
        } else if (distToPlayer < this.attackRange * 1.5f) {
            float angleFromSpawn = (float) Math.atan2(this.pos.y - this.spawnPos.y, this.pos.x - this.spawnPos.x);
            this.orbitAngle += speed * 0.015f;
            float targetX = this.spawnPos.x + (float) Math.cos(angleFromSpawn + 0.05f) * anchorRadius * 0.6f;
            float targetY = this.spawnPos.y + (float) Math.sin(angleFromSpawn + 0.05f) * anchorRadius * 0.6f;
            float ddx = targetX - this.pos.x, ddy = targetY - this.pos.y;
            float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            if (len > 1) { this.dx = (ddx / len) * speed; this.dy = (ddy / len) * speed; }
            updateDirectionFlags();
        } else if (distToPlayer < this.chaseRange) {
            setDirectionToward(player.pos, speed * 0.7f);
        } else {
            moveWander(speed * 0.4f);
        }
    }

    private void moveFigureEight(Player player, float speed, float radius) {
        this.orbitAngle += speed * 0.02f;
        float fig8X = (float) (Math.sin(this.orbitAngle) * radius);
        float fig8Y = (float) (Math.sin(this.orbitAngle * 2) * radius * 0.5f);

        float targetX = player.pos.x + fig8X;
        float targetY = player.pos.y + fig8Y;

        float ddx = targetX - this.pos.x;
        float ddy = targetY - this.pos.y;
        float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (len > 0) {
            this.dx = (ddx / len) * speed;
            this.dy = (ddy / len) * speed;
        }
        updateDirectionFlags();
    }

    // ========== MOVEMENT HELPERS ==========

    private void setDirectionToward(Vector2f target, float speed) {
        float ddx = target.x - this.pos.x;
        float ddy = target.y - this.pos.y;
        float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (len > 0) {
            this.dx = (ddx / len) * speed;
            this.dy = (ddy / len) * speed;
        }
        updateDirectionFlags();
    }

    private void setDirectionAway(Vector2f target, float speed) {
        float ddx = this.pos.x - target.x;
        float ddy = this.pos.y - target.y;
        float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (len > 0) {
            this.dx = (ddx / len) * speed;
            this.dy = (ddy / len) * speed;
        }
        updateDirectionFlags();
    }

    private void updateDirectionFlags() {
        this.up = this.dy < -0.1f;
        this.down = this.dy > 0.1f;
        this.left = this.dx < -0.1f;
        this.right = this.dx > 0.1f;
    }

    // ========== ATTACK PATTERNS ==========

    private void processAttacks(Player player, EnemyPhase phase, RealmManagerServer mgr, Realm targetRealm) {
        if (player.hasEffect(StatusEffectType.INVISIBLE)) return;
        if (this.hasEffect(StatusEffectType.STUNNED)) return;

        float dist = this.pos.distanceTo(player.pos);

        EnemyScriptBase script = mgr.getEnemyScript(this.enemyId);
        if (script != null) {
            if (dist < this.attackRange) {
                int dex = (int) ((6.5 * (this.model.getStats().getDex() + 17.3)) / 75);
                boolean canShoot = ((System.currentTimeMillis() - this.lastShotTick) > (1000 / dex));
                if (canShoot) {
                    this.lastShotTick = System.currentTimeMillis();
                    this.attack = true;
                    final Player target = player;
                    WorkerThread.doAsync(() -> {
                        try {
                            script.attack(targetRealm, this, target);
                        } catch (Exception e) {
                            Enemy.log.error("Failed to invoke enemy attack script. Reason: {}", e);
                        }
                    });
                }
            }
            return;
        }

        List<AttackPattern> attacks = (phase != null && phase.getAttacks() != null) ? phase.getAttacks() : null;

        if (attacks != null && !attacks.isEmpty()) {
            if (this.attackCooldowns == null || this.attackCooldowns.length != attacks.size()) {
                this.attackCooldowns = new long[attacks.size()];
                this.attackAngleAccumulators = new float[attacks.size()];
            }
            long now = System.currentTimeMillis();
            boolean anyAttacked = false;
            for (int i = 0; i < attacks.size(); i++) {
                AttackPattern ap = attacks.get(i);
                if (dist < ap.getMinRange() || dist > ap.getMaxRange()) continue;
                if ((now - this.attackCooldowns[i]) < ap.getCooldownMs()) continue;

                this.attackCooldowns[i] = now;
                anyAttacked = true;
                float spiralOffset = this.attackAngleAccumulators[i];
                this.attackAngleAccumulators[i] += ap.getAngleIncrementPerFiring();
                fireAttackPattern(ap, player, mgr, targetRealm, spiralOffset);
            }
            this.attack = anyAttacked;
        } else {
            if (dist < this.attackRange && this.weaponId >= 0) {
                int dex = Math.max(1, (int) ((6.5 * (this.model.getStats().getDex() + 17.3)) / 75));
                boolean canShoot = ((System.currentTimeMillis() - this.lastShotTick) > (1000 / dex));
                if (canShoot) {
                    this.lastShotTick = System.currentTimeMillis();
                    this.attack = true;
                    fireLegacyAttack(player, mgr, targetRealm);
                }
            } else {
                this.attack = false;
            }
        }
    }

    private void fireAttackPattern(AttackPattern ap, Player player, RealmManagerServer mgr, Realm targetRealm, float spiralOffset) {
        ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(ap.getProjectileGroupId());
        if (group == null) return;

        Vector2f source = this.getPos().clone(this.getSize() / 2, this.getSize() / 2);

        if (ap.getSourceNoise() > 0) {
            source.x += (Realm.RANDOM.nextFloat() - 0.5f) * ap.getSourceNoise();
            source.y += (Realm.RANDOM.nextFloat() - 0.5f) * ap.getSourceNoise();
        }

        Vector2f dest = player.getBounds().getPos().clone(player.getSize() / 2, player.getSize() / 2);

        float baseAngle;
        if ("FIXED".equals(ap.getAimMode())) {
            baseAngle = ap.getFixedAngle();
        } else {
            if (ap.isPredictive() && (player.getDx() != 0 || player.getDy() != 0)) {
                float travelTime = source.distanceTo(dest) / 5f;
                dest.x += player.getDx() * travelTime;
                dest.y += player.getDy() * travelTime;
            }
            baseAngle = Bullet.getAngle(source, dest);
        }

        baseAngle += spiralOffset;

        int shotCount = Math.max(1, ap.getShotCount());
        int burstCount = ap.getBurstCount();
        final int sc = Math.max(1, ap.getSpeedCount());
        final float sMin = ap.getMinSpeedMult();
        final float sMax = ap.getMaxSpeedMult();

        if ("RING".equals(ap.getAimMode())) {
            if (burstCount <= 1) {
                for (int s = 0; s < shotCount; s++) {
                    float angle = baseAngle + (float) (s * 2 * Math.PI / shotCount);
                    fireProjectileGroup(group, source.clone(), angle, player, mgr, targetRealm, sc, sMin, sMax);
                }
            } else {
                final float[] angles = new float[shotCount];
                for (int s = 0; s < shotCount; s++) {
                    angles[s] = baseAngle + (float) (s * 2 * Math.PI / shotCount);
                }
                final Vector2f srcCopy = source.clone();
                WorkerThread.doAsync(() -> {
                    try {
                        for (int b = 0; b < burstCount; b++) {
                            float offset = ap.getAngleOffsetPerBurst() * b;
                            for (float a : angles) {
                                fireProjectileGroup(group, srcCopy.clone(), a + offset, player, mgr, targetRealm, sc, sMin, sMax);
                            }
                            if (b < burstCount - 1 && ap.getBurstDelayMs() > 0) {
                                Thread.sleep(ap.getBurstDelayMs());
                            }
                        }
                    } catch (Exception e) {
                        Enemy.log.error("Failed burst fire. Reason: {}", e);
                    }
                });
            }
        } else {
            if (shotCount <= 1) {
                if (burstCount <= 1) {
                    fireProjectileGroup(group, source, baseAngle, player, mgr, targetRealm, sc, sMin, sMax);
                    if (ap.isMirror()) {
                        fireProjectileGroup(group, source.clone(), -baseAngle, player, mgr, targetRealm, sc, sMin, sMax);
                    }
                } else {
                    final float angle = baseAngle;
                    final Vector2f srcCopy = source.clone();
                    WorkerThread.doAsync(() -> {
                        try {
                            for (int b = 0; b < burstCount; b++) {
                                float burstAngle = angle + (ap.getAngleOffsetPerBurst() * b);
                                fireProjectileGroup(group, srcCopy.clone(), burstAngle, player, mgr, targetRealm, sc, sMin, sMax);
                                if (ap.isMirror()) {
                                    fireProjectileGroup(group, srcCopy.clone(), -burstAngle, player, mgr, targetRealm, sc, sMin, sMax);
                                }
                                if (b < burstCount - 1 && ap.getBurstDelayMs() > 0) {
                                    Thread.sleep(ap.getBurstDelayMs());
                                }
                            }
                        } catch (Exception e) {
                            Enemy.log.error("Failed burst fire. Reason: {}", e);
                        }
                    });
                }
            } else {
                float halfSpread = ap.getSpreadAngle() / 2f;
                if (burstCount <= 1) {
                    for (int s = 0; s < shotCount; s++) {
                        float t = shotCount > 1 ? (float) s / (shotCount - 1) : 0.5f;
                        float angle = baseAngle - halfSpread + t * ap.getSpreadAngle();
                        fireProjectileGroup(group, source.clone(), angle, player, mgr, targetRealm, sc, sMin, sMax);
                        if (ap.isMirror()) {
                            float mirrorAngle = baseAngle - (angle - baseAngle);
                            if (Math.abs(angle - mirrorAngle) > 0.01f) {
                                fireProjectileGroup(group, source.clone(), mirrorAngle, player, mgr, targetRealm, sc, sMin, sMax);
                            }
                        }
                    }
                } else {
                    final float angle = baseAngle;
                    final Vector2f srcCopy = source.clone();
                    WorkerThread.doAsync(() -> {
                        try {
                            for (int b = 0; b < burstCount; b++) {
                                float offset = ap.getAngleOffsetPerBurst() * b;
                                for (int s = 0; s < shotCount; s++) {
                                    float t = shotCount > 1 ? (float) s / (shotCount - 1) : 0.5f;
                                    float fanAngle = angle - halfSpread + t * ap.getSpreadAngle() + offset;
                                    fireProjectileGroup(group, srcCopy.clone(), fanAngle, player, mgr, targetRealm, sc, sMin, sMax);
                                }
                                if (b < burstCount - 1 && ap.getBurstDelayMs() > 0) {
                                    Thread.sleep(ap.getBurstDelayMs());
                                }
                            }
                        } catch (Exception e) {
                            Enemy.log.error("Failed burst fire. Reason: {}", e);
                        }
                    });
                }
            }
        }
    }

    private void fireProjectileGroup(ProjectileGroup group, Vector2f source, float baseAngle,
            Player player, RealmManagerServer mgr, Realm targetRealm) {
        fireProjectileGroup(group, source, baseAngle, player, mgr, targetRealm, 1, 1.0f, 1.0f);
    }

    private void fireProjectileGroup(ProjectileGroup group, Vector2f source, float baseAngle,
            Player player, RealmManagerServer mgr, Realm targetRealm,
            int speedCount, float minSpeedMult, float maxSpeedMult) {
        final int projCount = group.getProjectiles().size();
        // DAZED on an enemy halves the number of projectiles per attack
        // instead of reducing fire rate (the player-DAZED behaviour).
        // Stepping by 2 through the group preserves the spread pattern
        // (every other projectile drops out) so a 5-cone becomes a sparser
        // 3-cone, a 4-cone becomes a 2-cone, etc., rather than a
        // one-sided burst that would happen if we just truncated.
        final int projStep = (this.hasEffect(StatusEffectType.DAZED) && projCount > 1) ? 2 : 1;
        for (int i = 0; i < projCount; i += projStep) {
            Projectile p = group.getProjectiles().get(i);
            // Enemy projectiles always aim toward the player, with the
            // per-projectile angle treated as a fan/offset relative to
            // that direction. The positionMode flag is a player-ability
            // concept (cursor-targeted spells like wizard's meteor) and
            // does not apply to enemy fire — without this fix, any
            // projectile group with positionMode=ABSOLUTE/RELATIVE fired
            // straight south regardless of player position (Stone
            // Guardian cone attack was the reported case).
            final float angle = baseAngle + Float.parseFloat(p.getAngle());

            Vector2f projSource = source.clone();
            if (p.getSpawnOffsetX() != 0 || p.getSpawnOffsetY() != 0) {
                float cos = (float) Math.cos(-baseAngle);
                float sin = (float) Math.sin(-baseAngle);
                float rotX = p.getSpawnOffsetX() * cos - p.getSpawnOffsetY() * sin;
                float rotY = p.getSpawnOffsetX() * sin + p.getSpawnOffsetY() * cos;
                projSource.x += rotX;
                projSource.y += rotY;
            }

            final float finalAngle = angle;
            final int projIndex = i;
            final Vector2f finalSource = projSource;

            Runnable spawnAction = () -> {
                boolean isOrbital = p.hasFlag(ProjectileFlag.ORBITAL.flagId);
                for (int s = 0; s < speedCount; s++) {
                    float speedMult = speedCount > 1
                            ? minSpeedMult + (maxSpeedMult - minSpeedMult) * ((float) s / (speedCount - 1))
                            : 1.0f;

                    if (isOrbital) {
                        float orbitRadius = Math.abs(p.getAmplitude());
                        short effectiveFreq = (short) (p.getAmplitude() < 0 ? -p.getFrequency() : p.getFrequency());
                        float startPhase = (float) (projIndex * 2 * Math.PI / projCount);
                        Vector2f center = Enemy.this.getPos().clone(Enemy.this.getSize() / 2, Enemy.this.getSize() / 2);
                        Vector2f orbSpawnPos = new Vector2f(
                                center.x + orbitRadius * (float) Math.cos(startPhase),
                                center.y + orbitRadius * (float) Math.sin(startPhase));
                        Bullet b = mgr.addProjectile(targetRealm.getRealmId(), 0l, player.getId(),
                                group.getProjectileGroupId(), p.getProjectileId(), orbSpawnPos, startPhase,
                                p.getSize(), 0f, p.getRange(), p.getDamage(), true, p.getFlags(),
                                (short) orbitRadius, effectiveFreq, Enemy.this.getId());
                        if (b != null) {
                            b.setupOrbital(center.x, center.y, orbitRadius, startPhase);
                            if (p.getEffects() != null) b.setEffects(p.getEffects());
                        }
                    } else {
                        float mag = p.getMagnitude() * speedMult;
                        Bullet b = mgr.addProjectile(targetRealm.getRealmId(), 0l, player.getId(),
                                group.getProjectileGroupId(), p.getProjectileId(), finalSource.clone(), finalAngle,
                                p.getSize(), mag, p.getRange(), p.getDamage(), true, p.getFlags(),
                                p.getAmplitude(), p.getFrequency(), Enemy.this.getId());
                        if (b != null && p.getEffects() != null) b.setEffects(p.getEffects());
                    }
                }
            };

            if (p.getSpawnDelayMs() > 0) {
                WorkerThread.doAsync(() -> {
                    try {
                        Thread.sleep(p.getSpawnDelayMs());
                        spawnAction.run();
                    } catch (Exception e) {
                        Enemy.log.error("Failed delayed projectile spawn. Reason: {}", e);
                    }
                });
            } else {
                spawnAction.run();
            }
        }
    }

    private void fireLegacyAttack(Player player, RealmManagerServer mgr, Realm targetRealm) {
        Vector2f dest = player.getBounds().getPos().clone(player.getSize() / 2, player.getSize() / 2);
        Vector2f source = this.getPos().clone(this.getSize() / 2, this.getSize() / 2);
        float angle = Bullet.getAngle(source, dest);
        ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(this.weaponId);
        if (group == null) return;
        fireProjectileGroup(group, source, angle, player, mgr, targetRealm);
    }

    // ========== LEGACY CHASE (for backwards compat without phases) ==========

    public void chase(Player player) {
        if (player == null || player.hasEffect(StatusEffectType.INVISIBLE)) {
            this.up = false;
            this.dy = 0;
            this.dx = 0;
            this.down = false;
            this.right = false;
            this.left = false;
            return;
        }

        // Stationary NPCs (Healer, Decoy) declare maxSpeed=0 in their model.
        // Without this guard chase() still pushed them at the hard-coded
        // CHASE_SPEED toward the player. Once the player walked out of
        // viewport the client kept extrapolating the last non-zero velocity
        // and the healer drifted past the map boundary.
        if (this.model != null && this.model.getMaxSpeed() == 0f) {
            this.dx = 0;
            this.dy = 0;
            this.up = false;
            this.down = false;
            this.left = false;
            this.right = false;
            return;
        }

        if (this.getPos().distanceTo(player.getPos()) < this.chaseRange
                && this.getPos().distanceTo(player.getPos()) >= this.attackRange) {
            if (this.pos.y > player.pos.y + 1) { this.up = true; this.dy = -CHASE_SPEED; } else { this.up = false; }
            if (this.pos.y < player.pos.y - 1) { this.down = true; this.dy = CHASE_SPEED; } else { this.down = false; }
            if (this.pos.x > player.pos.x + 1) { this.left = true; this.dx = -CHASE_SPEED; } else { this.left = false; }
            if (this.pos.x < player.pos.x - 1) { this.right = true; this.dx = CHASE_SPEED; } else { this.right = false; }
        }
    }

    // ========== UPDATE LOOPS ==========

    public void update(long realmId, RealmManagerServer mgr, double time) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        // Scripted NPCs (e.g. Enemy67 vault healer) need to see hidden admins so
        // friendly scripts still trigger; everything else honours the /hide filter.
        final boolean includeHidden = mgr.getEnemyScript(this.enemyId) != null;
        final Player player = mgr.getClosestPlayer(targetRealm.getRealmId(), this.getPos(), this.chaseRange, includeHidden);
        super.update(time);
        if (player == null) {
            this.dx = 0;
            this.dy = 0;
            this.up = false;
            this.down = false;
            this.left = false;
            this.right = false;
            return;
        }

        float currentHealthPercent = (float) this.getHealth() / (float) this.getStats().getHp();
        float currentManaPercent = (this.getStats().getMp() > 0) ? (float) this.getMana() / (float) this.getStats().getMp() : 0;
        this.setHealthpercent(currentHealthPercent);
        this.setManapercent(currentManaPercent);
        this.healthpercent = currentHealthPercent;

        EnemyPhase phase = this.getActivePhase();

        if (phase != null && this.lastPhaseName != null && !phase.getName().equals(this.lastPhaseName)) {
            this.phaseTransitionUntil = System.currentTimeMillis() + PHASE_TRANSITION_DURATION_MS;
            this.addEffect(StatusEffectType.INVINCIBLE, PHASE_TRANSITION_DURATION_MS);
            this.attackCooldowns = null;
            this.attackAngleAccumulators = null;
        }
        if (phase != null) {
            this.lastPhaseName = phase.getName();
        }

        final boolean inPhaseTransition = System.currentTimeMillis() < this.phaseTransitionUntil;

        final boolean frozen = inPhaseTransition || this.hasEffect(StatusEffectType.PARALYZED) || this.hasEffect(StatusEffectType.STASIS);
        if (frozen) {
            this.up = false;
            this.down = false;
            this.right = false;
            this.left = false;
            this.dx = 0;
            this.dy = 0;
        } else if (phase != null && phase.getMovement() != null) {
            this.applyMovement(player, phase);
        } else {
            this.chase(player);
        }

        final boolean notInvisible = !player.hasEffect(StatusEffectType.INVISIBLE);
        if (notInvisible && !inPhaseTransition && !this.hasEffect(StatusEffectType.STUNNED) && !this.hasEffect(StatusEffectType.STASIS)) {
            this.processAttacks(player, phase, mgr, targetRealm);
        } else {
            this.attack = false;
        }
    }

    public void tickMove(Realm targetRealm) {
        if (this.dx == 0 && this.dy == 0) return;
        if (System.currentTimeMillis() < this.phaseTransitionUntil) return;
        if (this.hasEffect(StatusEffectType.PARALYZED) || this.hasEffect(StatusEffectType.STASIS)) return;

        if (!targetRealm.getTileManager().collisionTile(this, this.dx, 0)
                && !targetRealm.getTileManager().collidesXLimit(this, this.dx)
                && !targetRealm.getTileManager().isVoidTile(
                        this.pos.clone(this.getSize() / 2, this.getSize() / 2), this.dx, 0)) {
            this.pos.x += this.dx;
        }
        if (!targetRealm.getTileManager().collisionTile(this, 0, this.dy)
                && !targetRealm.getTileManager().collidesYLimit(this, this.dy)
                && !targetRealm.getTileManager().isVoidTile(
                        this.pos.clone(this.getSize() / 2, this.getSize() / 2), 0, this.dy)) {
            this.pos.y += this.dy;
        }
    }

    public void idle(boolean applyMovement) {
        if (this.idleTime >= Enemy.IDLE_FRAMES) {
            this.up = Realm.RANDOM.nextBoolean();
            this.down = Realm.RANDOM.nextBoolean();
            this.left = Realm.RANDOM.nextBoolean();
            this.right = Realm.RANDOM.nextBoolean();
            if (this.up) this.dy = -CHASE_SPEED;
            if (this.down) this.dy = CHASE_SPEED;
            if (this.right) this.dx = CHASE_SPEED;
            if (this.left) this.dx = -CHASE_SPEED;
            this.idleTime = 0;
        } else {
            this.idleTime++;
        }
        if (applyMovement) {
            this.pos.x += this.dx;
            this.pos.y += this.dy;
        }
    }
}
