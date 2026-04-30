package com.openrealm.game.entity;

import java.time.Instant;

import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.realm.Realm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Streamable
public class Portal {
    private long id;
    private short portalId;
    private long fromRealmId;
    private long toRealmId;
    private long expires;
    private Vector2f pos;
    private String targetNodeId;

    public Portal(long id, short portalId, Vector2f pos) {
        this.id = id;
        this.portalId = portalId;
        this.fromRealmId = 0;
        this.toRealmId = 0;
        this.pos = pos;
        this.expires = Instant.now().toEpochMilli() + 35000;
    }

    public Portal(short portalId, Vector2f pos) {
        this.portalId = portalId;
        this.pos = pos;
        this.fromRealmId = 0;
        this.toRealmId = 0;
        this.expires = Instant.now().toEpochMilli() + 35000;
    }

    public void linkPortal(Realm from, Realm to) {
        if (from != null) {
            this.setFromRealm(from);
        }
        if (to != null) {
            this.setToRealm(to);
        }
    }

    private void setFromRealm(Realm from) {
        this.setFromRealm(from.getRealmId());
    }

    private void setToRealm(Realm to) {
        this.setToRealm(to.getRealmId());
    }

    private void setFromRealm(long fromRealmId) {
        this.fromRealmId = fromRealmId;
    }

    private void setToRealm(long toRealmId) {
        this.toRealmId = toRealmId;
    }

    public short getPortalId() {
        return this.portalId;
    }

    public boolean isExpired() {
        return Instant.now().toEpochMilli() >= this.expires;
    }

    public void setNeverExpires() {
        this.expires = Long.MAX_VALUE;
    }

    public boolean equals(Portal other) {
        return (this.id == other.getId()) && (this.portalId == other.getPortalId())
                && this.fromRealmId == other.getFromRealmId() && this.getToRealmId() == other.getToRealmId()
                && this.getPos().equals(other.getPos()) && (this.expires == other.getExpires());
    }
}
