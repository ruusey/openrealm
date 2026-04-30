package com.openrealm.game.entity;

import java.time.Instant;

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
            }
        }
    }

    public void removeExpiredEffects() {
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] != -1) {
                if (Instant.now().toEpochMilli() > this.effectTimes[i]) {
                    this.effectIds[i] = (short) -1;
                    this.effectTimes[i] = (long) -1;
                }
            }
        }
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
    }

    public void addEffect(StatusEffectType effect, long duration) {
        final long expireTime = (duration == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : Instant.now().toEpochMilli() + duration;

        if (effect == StatusEffectType.POISONED) {
            for (int i = 0; i < this.effectIds.length; i++) {
                if (this.effectIds[i] == -1) {
                    this.effectIds[i] = effect.effectId;
                    this.effectTimes[i] = expireTime;
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
                return;
            }
        }
        for (int i = 0; i < this.effectIds.length; i++) {
            if (this.effectIds[i] == -1) {
                this.effectIds[i] = effect.effectId;
                this.effectTimes[i] = expireTime;
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
