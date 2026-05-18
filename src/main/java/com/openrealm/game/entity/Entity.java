package com.openrealm.game.entity;

import java.time.Instant;
import java.util.Set;

import com.openrealm.game.contants.StatusEffectType;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public abstract class Entity extends GameObject {
    protected boolean up = false;
    protected boolean down = false;
    protected boolean right = false;
    protected boolean left = false;
    protected boolean attack = false;
    protected String lastAnimSet = "idle_side";
    protected String lastMovementDirection = "side";

    public boolean xCol = false;
    public boolean yCol = false;

    protected int attackSpeed = 1050;
    protected int attackDuration = 650;
    protected double attacktime;
    protected boolean canAttack = true;
    protected boolean attacking = false;
    /** Epoch millis until which the entity is considered "attacking" for animation. */
    protected long attackingUntil = 0;
    /** Duration in ms that the attacking flag stays true after a shot. */
    private static final long ATTACK_ANIM_DURATION_MS = 350;
    protected float aimX = 0;
    protected float aimY = 0;

    public int health = 100;
    public int mana = 100;
    public float healthpercent = 1;
    public float manapercent = 1;

    protected Rectangle hitBounds;

    private Short[] effectIds;
    private Long[] effectTimes;
    /**
     * Per-effect magnitude for statuses that need it (currently EMPOWERED —
     * Heavy Buffer's Guiding Light aura, where each refresh carries a bonus
     * derived from the caster's WIS). Parallel to {@link #effectIds} —
     * effectMagnitudes[i] is the magnitude of effectIds[i]. 0 means
     * "no magnitude" / "use the status's hardcoded default". Server-side
     * only; not serialized to the wire (the wire just carries the status id
     * for icon display, server is authoritative for stat math).
     */
    private Short[] effectMagnitudes;

    public Entity(long id, Vector2f origin, int size) {
        super(id, origin, size);
        this.hitBounds = new Rectangle(origin, size, size);
        this.resetEffects();
    }

    public void removeEffect(short effectId) {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effectId) {
                this.effectIds[i] = (short) -1;
                this.effectTimes[i] = (long) -1;
                if (this.effectMagnitudes != null) this.effectMagnitudes[i] = 0;
            }
        }
    }

    public void removeExpiredEffects() {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] != -1) {
                if (Instant.now().toEpochMilli() > this.effectTimes[i]) {
                    this.effectIds[i] = (short) -1;
                    this.effectTimes[i] = (long) -1;
                    if (this.effectMagnitudes != null) this.effectMagnitudes[i] = 0;
                }
            }
        }
    }

    /** Read the magnitude associated with an active effect, or 0 if the
     *  effect isn't active or wasn't applied with a magnitude. */
    public short getEffectMagnitude(StatusEffectType effect) {
        if (this.effectIds == null || this.effectMagnitudes == null) return 0;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId) return this.effectMagnitudes[i];
        }
        return 0;
    }

    public boolean hasEffect(StatusEffectType effect) {
        if (this.effectIds == null)
            return false;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId)
                return true;
        }
        return false;
    }

    public boolean hasNoEffects() {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] > -1)
                return false;
        }
        return true;
    }

    public void resetEffects() {
        this.effectIds = new Short[] { -1, -1, -1, -1, -1, -1, -1, -1 };
        this.effectTimes = new Long[] { -1l, -1l, -1l, -1l, -1l, -1l, -1l, -1l };
        this.effectMagnitudes = new Short[] { 0, 0, 0, 0, 0, 0, 0, 0 };
    }

    /** Set of status effect ids that are considered debuffs — used by the
     *  WARDED / VULNERABLE gates in addEffect. Beneficial statuses (HEALING,
     *  SPEEDY, INVINCIBLE, PROTECTED, BRACED, ARMORED, MANA_FOUNT, etc.) are
     *  NOT in this set so they apply normally even on warded targets. */
    private static final Set<Short> DEBUFF_IDS = Set.of(
            StatusEffectType.PARALYZED.effectId,
            StatusEffectType.STUNNED.effectId,
            StatusEffectType.DAZED.effectId,
            StatusEffectType.CURSED.effectId,
            StatusEffectType.POISONED.effectId,
            StatusEffectType.SLOWED.effectId,
            StatusEffectType.ARMOR_BROKEN.effectId,
            StatusEffectType.WEAKEN.effectId,
            StatusEffectType.BLIND.effectId,
            StatusEffectType.VULNERABLE.effectId,
            StatusEffectType.GROUNDED.effectId
    );

    private boolean hasEffectId(short id) {
        if (this.effectIds == null) return false;
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == id) {
                final long end = this.effectTimes[i];
                if (end == Long.MAX_VALUE || end > Instant.now().toEpochMilli()) return true;
            }
        }
        return false;
    }

    public void addEffect(StatusEffectType effect, long duration) {
        addEffect(effect, duration, (short) 0);
    }

    /**
     * Apply a status with a per-instance magnitude. Used by EMPOWERED so the
     * Heavy Buffer's Guiding Light aura can carry the caster's WIS-derived
     * boost. For statuses that don't read magnitude (BRACED, SLOWED, etc.)
     * the value is harmless extra bookkeeping.
     *
     * When refreshing an already-active effect, the LARGER magnitude wins
     * so a stronger caster doesn't get downgraded by a weaker one mid-tick.
     */
    public void addEffect(StatusEffectType effect, long duration, short magnitude) {
        // WARDED — silently drop any incoming debuff. Beneficial statuses
        // (heals, speed buffs) still apply because they're not in DEBUFF_IDS.
        if (DEBUFF_IDS.contains(effect.effectId)
                && hasEffectId(StatusEffectType.WARDED.effectId)) {
            return;
        }
        // GROUNDED auto-applies SLOWED for the same duration — the debuff's
        // "movement lock + can't dash" semantics need both flags. Recurse
        // BEFORE the WARDED/VULNERABLE checks so this entity's status table
        // ends up with both rows. SLOWED itself is debuff-listed so it'll
        // still respect WARDED if SLOWED gets applied independently later.
        if (effect == StatusEffectType.GROUNDED) {
            addEffect(StatusEffectType.SLOWED, duration);
        }
        // VULNERABLE — incoming debuffs get DOUBLE duration. Stacks before
        // the WARDED check above is moot (VULNERABLE is itself a debuff so
        // its application can be warded). Applied only to debuffs so it
        // doesn't shortcut a heal into a 2× heal.
        long effDuration = duration;
        if (DEBUFF_IDS.contains(effect.effectId)
                && hasEffectId(StatusEffectType.VULNERABLE.effectId)
                && duration != Long.MAX_VALUE) {
            effDuration = duration * 2L;
        }
        final long expireTime = (effDuration == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : Instant.now().toEpochMilli() + effDuration;

        if (effect == StatusEffectType.POISONED) {
            for (int i = 0; i < this.effectIds.length; i++) {
                if (this.effectIds[i] == -1) {
                    this.effectIds[i] = effect.effectId;
                    this.effectTimes[i] = expireTime;
                    if (this.effectMagnitudes != null) this.effectMagnitudes[i] = magnitude;
                    return;
                }
            }
            return;
        }

        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == effect.effectId) {
                if (expireTime > this.effectTimes[i]) {
                    this.effectTimes[i] = expireTime;
                }
                // Refresh magnitude with the larger value so a stronger
                // caster's aura tick doesn't get clobbered by a weaker one.
                if (this.effectMagnitudes != null
                        && magnitude > this.effectMagnitudes[i]) {
                    this.effectMagnitudes[i] = magnitude;
                }
                return;
            }
        }
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == -1) {
                this.effectIds[i] = effect.effectId;
                this.effectTimes[i] = expireTime;
                if (this.effectMagnitudes != null) this.effectMagnitudes[i] = magnitude;
                return;
            }
        }
    }

    public boolean getDeath() {
        return this.health <= 0;
    }

    public int getDirection() {
        if ((this.isUp()) || (this.isLeft()))
            return 1;
        return -1;
    }

    /**
     * Mark this entity as attacking for ATTACK_ANIM_DURATION_MS.
     * Used by the server when a player shoots to broadcast the attack
     * animation state to other clients via ObjectMovePacket.
     */
    public void triggerAttackAnimation() {
        this.attackingUntil = System.currentTimeMillis() + ATTACK_ANIM_DURATION_MS;
        this.attacking = true;
    }

    /**
     * Override Lombok's isAttacking() — also checks the timer-based flag
     * set by triggerAttackAnimation() for network-broadcast attack state.
     */
    public boolean isAttacking() {
        if (this.attackingUntil > 0 && System.currentTimeMillis() > this.attackingUntil) {
            this.attacking = false;
            this.attackingUntil = 0;
        }
        return this.attacking;
    }

    public void update(double time) {
    }
}
