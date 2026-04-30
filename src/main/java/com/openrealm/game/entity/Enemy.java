package com.openrealm.game.entity;

import java.util.List;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.contants.ProjectilePositionMode;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.AttackPattern;
import com.openrealm.game.model.EnemyModel;
import com.openrealm.game.model.EnemyPhase;
import com.openrealm.game.model.MovementPattern;
import com.openrealm.game.model.Projectile;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.script.EnemyScriptBase;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.client.packet.UpdatePacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerClient;
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
    /** Hard cap on any enemy movement speed (tiles/sec). Prevents charge/enrage
     *  phases from exceeding player run speed at moderate SPD stats. */
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
    // Spiral pattern: accumulated angle offset per attack (reset on phase change)
    private float[] attackAngleAccumulators = null;
    // Phase transition: brief invulnerability + pause when switching phases
    private String lastPhaseName = null;
    private long phaseTransitionUntil = 0;
    private static final long PHASE_TRANSITION_DURATION_MS = 1200;

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

    public void applyUpdate(UpdatePacket packet, PlayState state) {
        this.name = packet.getPlayerName();
        this.stats = packet.getStats().asStats();
        this.health = packet.getHealth();
        this.mana = packet.getMana();
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.health / (float) this.stats.getHp();
        }
    }

    public void applyState(com.openrealm.net.client.packet.PlayerStatePacket packet) {
        this.health = packet.getHealth();
        this.mana = packet.getMana();
        this.setEffectIds(packet.getEffectIds());
        this.setEffectTimes(packet.getEffectTimes());
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.health / (float) this.stats.getHp();
        }
    }

    // ========== PHASE RESOLUTION ==========

    private EnemyPhase getActivePhase() {
        List<EnemyPhase> phases = this.model.getPhases();
        if (phases == null || phases.isEmpty()) return null;
        float hpPct = (this.stats.getHp() > 0) ? (float) this.health / (float) this.stats.getHp() : 0f;
        // Phases ordered by hpThreshold descending in JSON. Active = first phase where hpPct <= threshold
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
            // Add slight zigzag — offset angle by a wobble so enemies don't beeline
            float angle = (float) Math.atan2(player.pos.y - this.pos.y, player.pos.x - this.pos.x);
            float wobble = (float) Math.sin(this.orbitAngle * 3) * 0.4f;
            this.orbitAngle += 0.05f;
            angle += wobble;
            this.dx = (float) Math.cos(angle) * speed;
            this.dy = (float) Math.sin(angle) * speed;
            updateDirectionFlags();
        } else if (dist < this.attackRange) {
            // In attack range — circle slowly instead of standing still
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
            // Move toward/away from orbit radius
            if (dist > radius) {
                setDirectionToward(player.pos, speed);
            } else {
                setDirectionAway(player.pos, speed);
            }
        }

        // Tangential movement
        float angleToPlayer = (float) Math.atan2(this.pos.y - player.pos.y, this.pos.x - player.pos.x);
        float tangentAngle = cw ? (angleToPlayer + (float) (Math.PI / 2)) : (angleToPlayer - (float) (Math.PI / 2));
        float tangentX = (float) Math.cos(tangentAngle) * speed;
        float tangentY = (float) Math.sin(tangentAngle) * speed;

        // Blend: mostly tangent, some radial correction
        float radialWeight = Math.min(1f, Math.abs(dist - radius) / 30f);
        this.dx = this.dx * radialWeight + tangentX * (1f - radialWeight);
        this.dy = this.dy * radialWeight + tangentY * (1f - radialWeight);
        updateDirectionFlags();
    }

    private void moveStrafe(Player player, float speed, float preferredRange) {
        float dist = this.pos.distanceTo(player.pos);

        // Maintain preferred range
        if (dist > preferredRange + 20) {
            setDirectionToward(player.pos, speed * 0.6f);
        } else if (dist < preferredRange - 20) {
            setDirectionAway(player.pos, speed * 0.6f);
        } else {
            // At preferred range — strafe with periodic direction reversal
            float angle = (float) Math.atan2(this.pos.y - player.pos.y, this.pos.x - player.pos.x);
            // Reverse strafe direction every ~3 seconds
            boolean strafeRight = ((int)(this.orbitAngle / 3.0f) % 2) == 0;
            float strafeAngle = angle + (strafeRight ? (float)(Math.PI / 2) : (float)(-Math.PI / 2));
            this.orbitAngle += 0.016f; // ~1 second per unit
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
        // Change direction at random intervals (30-90 ticks) for natural feel
        int wanderInterval = 30 + (int)(Math.abs(this.getId()) % 60);
        if (this.idleTime >= wanderInterval) {
            float angle = Realm.RANDOM.nextFloat() * (float) (Math.PI * 2);
            this.dx = (float) Math.cos(angle) * speed;
            this.dy = (float) Math.sin(angle) * speed;
            updateDirectionFlags();
            this.idleTime = 0;
        } else {
            this.idleTime++;
            // Slight curve to current path (not perfectly straight)
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
            // Too far from spawn — return home
            setDirectionToward(this.spawnPos, speed * 1.2f);
        } else if (distToPlayer < this.attackRange * 1.5f) {
            // Player is close — orbit around spawn point while attacking
            float angleFromSpawn = (float) Math.atan2(this.pos.y - this.spawnPos.y, this.pos.x - this.spawnPos.x);
            this.orbitAngle += speed * 0.015f;
            float targetX = this.spawnPos.x + (float) Math.cos(angleFromSpawn + 0.05f) * anchorRadius * 0.6f;
            float targetY = this.spawnPos.y + (float) Math.sin(angleFromSpawn + 0.05f) * anchorRadius * 0.6f;
            float ddx = targetX - this.pos.x, ddy = targetY - this.pos.y;
            float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            if (len > 1) { this.dx = (ddx / len) * speed; this.dy = (ddy / len) * speed; }
            updateDirectionFlags();
        } else if (distToPlayer < this.chaseRange) {
            // Player in chase range — approach but not past anchor boundary
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

        // Script-based attacks override everything
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

        // Phase-based attacks
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
            // Legacy: single attackId
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

        // Apply source noise
        if (ap.getSourceNoise() > 0) {
            source.x += (Realm.RANDOM.nextFloat() - 0.5f) * ap.getSourceNoise();
            source.y += (Realm.RANDOM.nextFloat() - 0.5f) * ap.getSourceNoise();
        }

        Vector2f dest = player.getBounds().getPos().clone(player.getSize() / 2, player.getSize() / 2);

        // Compute base angle
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

        // Apply spiral offset (accumulated angleIncrementPerFiring)
        baseAngle += spiralOffset;

        int shotCount = Math.max(1, ap.getShotCount());
        int burstCount = ap.getBurstCount();
        final int sc = Math.max(1, ap.getSpeedCount());
        final float sMin = ap.getMinSpeedMult();
        final float sMax = ap.getMaxSpeedMult();

        if ("RING".equals(ap.getAimMode())) {
            // Evenly spaced around full circle
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
            // PLAYER or FIXED mode
            if (shotCount <= 1) {
                // Single shot (existing behavior)
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
                // Fan of shotCount projectiles
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
        for (int i = 0; i < projCount; i++) {
            Projectile p = group.getProjectiles().get(i);
            float angle;
            if (p.getPositionMode().equals(ProjectilePositionMode.TARGET_PLAYER)) {
                angle = baseAngle + Float.parseFloat(p.getAngle());
            } else {
                angle = Float.parseFloat(p.getAngle());
            }

            // Apply spawn offset (rotated by firing angle)
            Vector2f projSource = source.clone();
            if (p.getSpawnOffsetX() != 0 || p.getSpawnOffsetY() != 0) {
                float cos = (float) Math.cos(-baseAngle);
                float sin = (float) Math.sin(-baseAngle);
                float rotX = p.getSpawnOffsetX() * cos - p.getSpawnOffsetY() * sin;
                float rotY = p.getSpawnOffsetX() * sin + p.getSpawnOffsetY() * cos;
                projSource.x += rotX;
                projSource.y += rotY;
            }

            // Wrap spawn logic for potential delay
            final float finalAngle = angle;
            final int projIndex = i;
            final Vector2f finalSource = projSource;

            Runnable spawnAction = () -> {
                boolean isOrbital = p.hasFlag(ProjectileFlag.ORBITAL.flagId);
                // Speed stacking: fire multiple bullets at same angle with different speeds
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

            // Apply spawn delay if configured
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

        if (this.getPos().distanceTo(player.getPos()) < this.chaseRange
                && this.getPos().distanceTo(player.getPos()) >= this.attackRange) {
            if (this.pos.y > player.pos.y + 1) { this.up = true; this.dy = -CHASE_SPEED; } else { this.up = false; }
            if (this.pos.y < player.pos.y - 1) { this.down = true; this.dy = CHASE_SPEED; } else { this.down = false; }
            if (this.pos.x > player.pos.x + 1) { this.left = true; this.dx = -CHASE_SPEED; } else { this.left = false; }
            if (this.pos.x < player.pos.x - 1) { this.right = true; this.dx = CHASE_SPEED; } else { this.right = false; }
        }
    }

    // ========== UPDATE LOOPS ==========

    public void update(RealmManagerClient mgr, double time) {
        super.update(time);
        if (this.stats != null && this.stats.getHp() > 0) {
            this.healthpercent = (float) this.getHealth() / (float) this.stats.getHp();
        }
        if (this.stats != null && this.stats.getMp() > 0) {
            this.manapercent = (float) this.getMana() / (float) this.stats.getMp();
        }
        // Dead reckoning: extrapolate position using velocity between server corrections.
        // Server now only sends corrections when actual position diverges from predicted,
        // so the client must advance entities locally to maintain smooth movement.
        this.extrapolate();
    }

    // AI decision tick — runs at the staggered AI rate (e.g. 32Hz). Sets
    // dx/dy and schedules attacks. Movement APPLICATION lives in tickMove()
    // and runs every server tick (64Hz) so motion stays smooth even when
    // AI is staggered. dx/dy persist between AI ticks: a CHASE_SPEED set
    // on one AI tick is applied on both physics ticks before the next AI
    // tick, preserving the same effective movement rate as un-staggered AI.
    public void update(long realmId, RealmManagerServer mgr, double time) {
        final Realm targetRealm = mgr.getRealms().get(realmId);
        final Player player = mgr.getClosestPlayer(targetRealm.getRealmId(), this.getPos(), this.chaseRange);
        super.update(time);
        if (player == null) {
            // No target in chase range — stop moving. Without this, dx/dy from
            // the last AI tick (while a player WAS in range) would persist and
            // tickMove would keep applying it, causing the enemy to drift away
            // (and clients to extrapolate it through walls / back into view).
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

        // Resolve active phase
        EnemyPhase phase = this.getActivePhase();

        // Phase transition: when the active phase changes, briefly pause the enemy
        // with invulnerability so the player has a visual cue that the fight is
        // intensifying. The INVINCIBLE effect makes the sprite glow white-gold.
        if (phase != null && this.lastPhaseName != null && !phase.getName().equals(this.lastPhaseName)) {
            this.phaseTransitionUntil = System.currentTimeMillis() + PHASE_TRANSITION_DURATION_MS;
            this.addEffect(StatusEffectType.INVINCIBLE, PHASE_TRANSITION_DURATION_MS);
            this.attackCooldowns = null; // reset attack timers for new phase
            this.attackAngleAccumulators = null; // reset spiral accumulators for new phase
        }
        if (phase != null) {
            this.lastPhaseName = phase.getName();
        }

        // During phase transition: freeze in place, skip attacks
        final boolean inPhaseTransition = System.currentTimeMillis() < this.phaseTransitionUntil;

        // Movement — STASIS freezes movement AND attacks (like PARALYZED + STUNNED combined)
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

        // Attacks — STASIS and phase transitions also prevent attacking
        final boolean notInvisible = !player.hasEffect(StatusEffectType.INVISIBLE);
        if (notInvisible && !inPhaseTransition && !this.hasEffect(StatusEffectType.STUNNED) && !this.hasEffect(StatusEffectType.STASIS)) {
            this.processAttacks(player, phase, mgr, targetRealm);
        } else {
            this.attack = false;
        }
    }

    // Lightweight per-tick movement applier. Called every server tick (64Hz)
    // even when AI is staggered, so enemies don't visibly stutter between AI
    // decisions. Uses dx/dy set by the most recent update() AI call.
    public void tickMove(Realm targetRealm) {
        if (this.dx == 0 && this.dy == 0) return;
        // Re-check freeze state in case STASIS/PARALYZED was applied between
        // AI ticks. Without this, a freshly-paralyzed enemy could continue
        // sliding for one tick on its last dx/dy before the AI tick zeroes it.
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

    @Override
    public void updateEffectState() {
        if (this.getSpriteSheet() == null) return;
        if (this.hasEffect(StatusEffectType.INVINCIBLE)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.INVINCIBLE)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.INVINCIBLE);
            }
        } else if (this.hasEffect(StatusEffectType.STASIS)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.STASIS)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.STASIS);
            }
        } else if (this.hasEffect(StatusEffectType.PARALYZED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.GRAYSCALE)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.GRAYSCALE);
            }
        } else if (this.hasEffect(StatusEffectType.STUNNED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.DECAY)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.DECAY);
            }
        } else if (this.hasEffect(StatusEffectType.ARMOR_BROKEN)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.ARMOR_BROKEN)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.ARMOR_BROKEN);
            }
        } else if (this.hasEffect(StatusEffectType.CURSED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.CURSED)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.CURSED);
            }
        } else if (this.hasEffect(StatusEffectType.POISONED)) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.POISONED)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.POISONED);
            }
        } else if (this.hasNoEffects()) {
            if (!this.getSpriteSheet().hasEffect(Sprite.EffectEnum.NORMAL)) {
                this.getSpriteSheet().setEffect(Sprite.EffectEnum.NORMAL);
            }
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        this.updateEffectState();
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame != null) {
            float wx = this.pos.getWorldVar().x;
            float wy = this.pos.getWorldVar().y;
            float ox = 2.5f;
            com.openrealm.game.graphics.ShaderManager.applyEffect(batch, Sprite.EffectEnum.SILHOUETTE);
            if (this.left) {
                batch.draw(frame, wx + this.size + ox, wy, -this.size, this.size);
                batch.draw(frame, wx + this.size - ox, wy, -this.size, this.size);
                batch.draw(frame, wx + this.size, wy + ox, -this.size, this.size);
                batch.draw(frame, wx + this.size, wy - ox, -this.size, this.size);
            } else {
                batch.draw(frame, wx + ox, wy, this.size, this.size);
                batch.draw(frame, wx - ox, wy, this.size, this.size);
                batch.draw(frame, wx, wy + ox, this.size, this.size);
                batch.draw(frame, wx, wy - ox, this.size, this.size);
            }
            com.openrealm.game.graphics.ShaderManager.clearEffect(batch);
            Sprite.EffectEnum currentEffect = this.getSpriteSheet().getCurrentEffect();
            com.openrealm.game.graphics.ShaderManager.applyEffect(batch, currentEffect);
            if (this.left) {
                batch.draw(frame, wx + this.size, wy, -this.size, this.size);
            } else {
                batch.draw(frame, wx, wy, this.size, this.size);
            }
            com.openrealm.game.graphics.ShaderManager.clearEffect(batch);
        }
    }
}
