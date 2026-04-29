package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.openrealm.game.contants.ProjectileFlag;
import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ProjectileGroup;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class Bullet extends GameObject  {
	private long srcEntityId;
    private int projectileId;
    private float angle;
    private float magnitude;
    private float range;
    private short damage;
    private boolean isEnemy;
    private boolean playerHit;
    private boolean enemyHit;
    private float tfAngle = (float) (Math.PI / 2);

    /**
     * Projectile behavior flags (ProjectileFlag IDs): PLAYER_PROJECTILE(10),
     * PARAMETRIC(12), INVERTED_PARAMETRIC(13), ORBITAL(20).
     * Controls movement — NOT on-hit effects.
     */
    private List<Short> flags;
    /**
     * On-hit status effects (StatusEffect IDs + durations).
     * Applied to the target entity when this bullet hits.
     * NOT behavior flags — those go in {@link #flags}.
     */
    private List<com.openrealm.game.model.ProjectileEffect> effects;

    private boolean invert = false;

    private long timeStep = 0;
    private short amplitude = 4;
    private short frequency = 25;

    // Orbital projectile: orbits around a fixed center point
    private float orbitCenterX;
    private float orbitCenterY;
    private float orbitRadius;
    private float orbitPhase; // starting angle in radians for this projectile

    private long createdTime;
    /**
     * Server-tick at which this bullet was spawned. Used for O(1) tick-counter
     * based expiry instead of wall-clock comparison via Instant.now() — saves
     * ~12 K syscalls/sec when many bullets are in flight.
     */
    private long createdTick;
    /**
     * Cached sin/cos of {@link #angle}. Angle is invariant for straight and
     * parametric projectiles, so caching at construction (and on direction-
     * change) eliminates two trig calls per bullet per tick. Orbital bullets
     * use a different code path and don't read these.
     */
    private float sinAngle;
    private float cosAngle;
    private long lastUpdateNanos = System.nanoTime();

    public Bullet() {
    	super(0l,null,0);
    }
    public Bullet(long id, int bulletId, Vector2f origin, int size) {
        super(id, origin, size);
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, int size, float angle, float magnitude, float range,
            short damage, boolean isEnemy, boolean playerHit, boolean enemyHit, List<Short> flags, boolean invert,
            long timeStep, short amplitude, short frequency) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.angle = angle;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.isEnemy = isEnemy;
        this.playerHit = playerHit;
        this.enemyHit = enemyHit;
        this.flags = flags;
        this.invert = invert;
        this.timeStep = timeStep;
        this.amplitude = amplitude;
        this.frequency = frequency;
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, Vector2f dest, short size, float magnitude, float range,
            short damage, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -Bullet.getAngle(origin, dest);
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, Vector2f dest, short size, float magnitude, float range,
            short damage, short amplitude, short frequency, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -Bullet.getAngle(origin, dest);
        this.amplitude = amplitude;
        this.frequency = frequency;
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public Bullet(long id, int projectileId, Vector2f origin, float angle, short size, float magnitude, float range,
            short damage, boolean isEnemy) {
        super(id, origin, size);
        this.projectileId = projectileId;
        this.magnitude = magnitude;
        this.range = range;
        this.damage = damage;
        this.angle = -angle;
        this.isEnemy = isEnemy;
        this.flags = new ArrayList<>();
        this.createdTime = Instant.now().toEpochMilli();
        cacheAngle();
    }

    public static float getAngle(Vector2f source, Vector2f target) {
        double angle = (Math.atan2(target.y - source.y, target.x - source.x));
        angle -= Math.PI / 2;
        return (float) angle;
    }

    /** Recompute the cached sin/cos after {@link #angle} is set. Avoids per-
     *  tick trig calls in {@link #update(float)} and {@link #updateParametric(float)}. */
    public void cacheAngle() {
        this.sinAngle = (float) Math.sin(this.angle);
        this.cosAngle = (float) Math.cos(this.angle);
    }

    public void setAngle(float angle) {
        this.angle = angle;
        cacheAngle();
    }

    public boolean hasFlag(short flag) {
        return (this.flags != null) && (this.flags.contains(flag));
    }
    
    public boolean hasFlag(ProjectileFlag flag) {
        return (this.flags != null) && (this.flags.contains(flag.flagId));
    }

    public boolean hasFlag(StatusEffectType flag) {
        return (this.flags != null) && (this.flags.contains(flag.effectId));
    }

    public boolean isEnemy() {
        return this.isEnemy;
    }

    public float getAngle() {
        return this.angle;
    }

    public float getMagnitude() {
        return this.magnitude;
    }

    // 10-second lifetime ceiling (640 ticks @ 64Hz). Tick-counter based to
    // avoid the wall-clock syscall that used to run per-bullet per-tick.
    private static final long MAX_LIFETIME_TICKS = 640L;

    public boolean remove() {
        return this.range <= 0.0;
    }

    /** Tick-counter aware expiry — pass the realm's current tickCounter. */
    public boolean remove(long currentTick) {
        if (this.range <= 0.0) return true;
        // createdTick == 0 indicates a legacy bullet not initialized via the
        // tick-aware spawn path; fall back to the wall-clock check so the
        // 10-second cap still works for those.
        if (this.createdTick != 0L) {
            return (currentTick - this.createdTick) > MAX_LIFETIME_TICKS;
        }
        return ((Instant.now().toEpochMilli()) - this.createdTime) > 10000L;
    }

    public short getDamage() {
        return this.damage;
    }

    @Override
    // Legacy entry point — kept so other callers that still pass no arg keep
    // working. The realm tick now precomputes bulletScale once per tick and
    // calls update(float) below, avoiding ~12 K nanoTime syscalls/sec when
    // many bullets are in flight.
    public void update() {
        final long now = System.nanoTime();
        final float dt = Math.min((now - this.lastUpdateNanos) / 1_000_000_000.0f, 0.1f);
        this.lastUpdateNanos = now;
        update(dt * 64.0f);
    }

    /**
     * Hot path — bulletScale is computed ONCE per tick at the realm level
     * and passed to every bullet. Removes the per-bullet System.nanoTime()
     * + float division that used to run 12 800 times/sec at 200 bullets.
     * Also reads cached {@link #sinAngle}/{@link #cosAngle} instead of
     * calling Math.sin/cos every tick — the angle of straight + parametric
     * projectiles is invariant for the bullet's whole lifetime.
     */
    public void update(float bulletScale) {
        if (this.hasFlag(ProjectileFlag.ORBITAL)) {
            this.updateOrbital(bulletScale);
        } else if (this.hasFlag(ProjectileFlag.PARAMETRIC)
                || this.hasFlag(ProjectileFlag.INVERTED_PARAMETRIC)) {
            this.updateParametric(bulletScale);
        } else {
            // Straight-line projectile — uses cached sin/cos.
            final float step = this.magnitude * bulletScale;
            final float velX = this.sinAngle * step;
            final float velY = this.cosAngle * step;
            // dist == magnitude * bulletScale because (sinA² + cosA²) == 1.
            this.range -= step;
            this.pos.addX(velX);
            this.pos.addY(velY);
            this.dx = velX;
            this.dy = velY;
        }
    }

    /**
     * Parametric projectile update - applies sinusoidal oscillation perpendicular
     * to the direction of travel, creating wavy projectile patterns (e.g. RotMG staff shots).
     *
     * The oscillation is computed as a position offset along the perpendicular axis,
     * so each tick we apply the CHANGE in offset (delta) rather than a raw velocity.
     * Negative amplitude naturally inverts the wave (no special flag needed).
     *
     * Perpendicular axis to forward (sin(a), cos(a)) is (cos(a), -sin(a)).
     */
    public void updateParametric(float bulletScale) {
        // Compute perpendicular offset BEFORE advancing timeStep
        float prevOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));

        this.timeStep = (long) ((this.timeStep + this.frequency * bulletScale) % 360);

        float currOffset = (float) (this.amplitude * Math.sin(Math.toRadians(this.timeStep)));
        float perpDelta = (currOffset - prevOffset) * (this.invert ? -1 : 1);

        // Forward velocity along the travel direction (cached sin/cos —
        // angle is invariant for the lifetime of a parametric projectile).
        float forwardX = this.sinAngle * this.magnitude * bulletScale;
        float forwardY = this.cosAngle * this.magnitude * bulletScale;

        // Perpendicular direction (90 degrees from forward).
        float perpX = this.cosAngle;
        float perpY = -this.sinAngle;

        // Combine forward motion + perpendicular oscillation
        float velX = forwardX + perpX * perpDelta;
        float velY = forwardY + perpY * perpDelta;

        // Decrease range by forward distance only (not oscillation)
        this.range -= this.magnitude * bulletScale;

        this.pos.addX(velX);
        this.pos.addY(velY);
        this.dx = velX;
        this.dy = velY;
    }

    /**
     * Orbital projectile update — positions the bullet on a circle around orbitCenter.
     * Uses frequency as angular speed (degrees/tick) and amplitude as orbit radius.
     * The initial angle for each projectile in the ring is set via orbitPhase.
     */
    public void updateOrbital(float bulletScale) {
        this.orbitPhase += (float) Math.toRadians(this.frequency * bulletScale);
        float newX = this.orbitCenterX + this.orbitRadius * (float) Math.cos(this.orbitPhase);
        float newY = this.orbitCenterY + this.orbitRadius * (float) Math.sin(this.orbitPhase);
        this.dx = newX - this.pos.x;
        this.dy = newY - this.pos.y;
        this.pos.x = newX;
        this.pos.y = newY;
        // Decrease range by arc length traveled per tick
        this.range -= this.orbitRadius * Math.abs(Math.toRadians(this.frequency * bulletScale));
    }

    /**
     * Configure this bullet as an orbital projectile.
     * @param centerX orbit center X
     * @param centerY orbit center Y
     * @param radius orbit radius in pixels
     * @param startPhase starting angle in radians (evenly spaced for ring patterns)
     */
    public void setupOrbital(float centerX, float centerY, float radius, float startPhase) {
        this.orbitCenterX = centerX;
        this.orbitCenterY = centerY;
        this.orbitRadius = radius;
        this.orbitPhase = startPhase;
        // Set initial position on the orbit
        this.pos.x = centerX + radius * (float) Math.cos(startPhase);
        this.pos.y = centerY + radius * (float) Math.sin(startPhase);
    }

    @Override
    public void render(SpriteBatch batch) {
        if (this.getSpriteSheet() == null) return;
        TextureRegion frame = this.getSpriteSheet().getCurrentFrame();
        if (frame == null) return;

        final ProjectileGroup group = GameDataManager.PROJECTILE_GROUPS.get(this.getProjectileId());
        final float angleOffset = Float.parseFloat(group.getAngleOffset());

        // Convert angle to degrees for LibGDX (counter-clockwise positive)
        float rotationDeg;
        if (angleOffset > 0.0f) {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + (this.tfAngle + angleOffset));
        } else {
            rotationDeg = (float) Math.toDegrees(-this.getAngle() + this.tfAngle);
        }

        float wx = this.pos.getWorldVar().x;
        float wy = this.pos.getWorldVar().y;
        float halfSize = this.size / 2f;

        // draw with rotation around center
        batch.draw(frame, wx, wy, halfSize, halfSize, this.size, this.size, 1f, 1f, rotationDeg);
    }
}
