package com.openrealm.game.entity;

import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.entity.NetObjectMovement;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public abstract class GameObject {
    protected long id;
    protected Rectangle bounds;
    protected Vector2f pos;
    protected int size;
    protected int spriteX;
    protected int spriteY;

    protected float dx;
    protected float dy;

    protected boolean teleported = false;
    protected String name = "";

    public boolean discovered;

    public GameObject(long id, Vector2f origin, int spriteX, int spriteY, int size) {
        this(id, origin, size);
    }

    public GameObject(long id, Vector2f origin, int size) {
        this.id = id;
        this.bounds = new Rectangle(origin, size, size);
        this.pos = origin;
        this.size = size;
    }

    public void setPos(Vector2f pos) {
        this.pos = pos;
        this.bounds = new Rectangle(pos, this.size, this.size);
        this.teleported = true;
    }

    public boolean getTeleported() {
        return this.teleported;
    }

    public void setTeleported(final boolean teleported) {
        this.teleported = teleported;
    }

    public void addForce(float a, boolean vertical) {
        if (!vertical) {
            this.dx -= a;
        } else {
            this.dy -= a;
        }
    }

    public void update() {

    }

    public void applyMovementLerp(float velX, float velY, float pct) {
        final float lerpX = this.lerp(this.pos.x, this.pos.x + velX, pct);
        final float lerpY = this.lerp(this.pos.y, this.pos.y + velY, pct);

        this.pos = new Vector2f(lerpX, lerpY);
    }

    private static final float SNAP_DISTANCE_SQ = (3 * 32) * (3 * 32);

    public void applyMovementLerp(NetObjectMovement packet, float pct) {
        float dx = packet.getPosX() - this.pos.x;
        float dy = packet.getPosY() - this.pos.y;
        if (dx * dx + dy * dy > SNAP_DISTANCE_SQ) {
            this.pos.x = packet.getPosX();
            this.pos.y = packet.getPosY();
        } else {
            this.pos.x = this.lerp(this.pos.x, packet.getPosX(), pct);
            this.pos.y = this.lerp(this.pos.y, packet.getPosY(), pct);
        }
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
    }

    public void applyMovementLerp(NetObjectMovement packet) {
        final float lerpX = this.lerp(this.pos.x, packet.getPosX(), 0.65f);
        final float lerpY = this.lerp(this.pos.y, packet.getPosY(), 0.65f);

        this.pos = new Vector2f(lerpX, lerpY);
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
    }

    public void applyMovement(NetObjectMovement packet) {
        this.pos = new Vector2f(packet.getPosX(), packet.getPosY());
        this.bounds = new Rectangle(this.pos, this.size, this.size);
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
    }

    protected float correctionOffsetX = 0f;
    protected float correctionOffsetY = 0f;
    private static final float CORRECTION_BLEND_RATE = 0.15f;
    private static final float CORRECTION_SNAP_THRESHOLD_SQ = (3 * 32) * (3 * 32);

    public void applyServerCorrection(NetObjectMovement packet) {
        float errorX = packet.getPosX() - this.pos.x;
        float errorY = packet.getPosY() - this.pos.y;

        if (errorX * errorX + errorY * errorY > CORRECTION_SNAP_THRESHOLD_SQ) {
            this.pos.x = packet.getPosX();
            this.pos.y = packet.getPosY();
            this.correctionOffsetX = 0f;
            this.correctionOffsetY = 0f;
        } else {
            this.correctionOffsetX += errorX;
            this.correctionOffsetY += errorY;
        }
        this.dx = packet.getVelX();
        this.dy = packet.getVelY();
        this.bounds = new Rectangle(this.pos, this.size, this.size);
    }

    public void extrapolate() {
        this.pos.x += this.dx;
        this.pos.y += this.dy;
        this.blendCorrectionOffset();
    }

    public void blendCorrectionOffset() {
        if (this.correctionOffsetX != 0f || this.correctionOffsetY != 0f) {
            float blendX = this.correctionOffsetX * CORRECTION_BLEND_RATE;
            float blendY = this.correctionOffsetY * CORRECTION_BLEND_RATE;
            this.pos.x += blendX;
            this.pos.y += blendY;
            this.correctionOffsetX -= blendX;
            this.correctionOffsetY -= blendY;

            if (this.correctionOffsetX * this.correctionOffsetX +
                this.correctionOffsetY * this.correctionOffsetY < 0.01f) {
                this.correctionOffsetX = 0f;
                this.correctionOffsetY = 0f;
            }
        }
        this.bounds = new Rectangle(this.pos, this.size, this.size);
    }

    private float lerp(float start, float end, float pct) {
        return (start + ((end - start) * pct));
    }

    public Vector2f getCenteredPosition() {
        return this.pos.clone((this.getSize() / 2), this.getSize() / 2);
    }

    @Override
    public Vector2f clone() {
        Vector2f newVector = new Vector2f(this.pos.x, this.pos.y);
        return newVector;
    }

    @Override
    public String toString() {
        return "$" + this.name;
    }
}
