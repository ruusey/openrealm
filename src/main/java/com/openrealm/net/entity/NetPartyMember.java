package com.openrealm.net.entity;

import com.openrealm.net.Streamable;
import com.openrealm.net.core.SerializableField;
import com.openrealm.net.core.SerializableFieldType;
import com.openrealm.net.core.nettypes.SerializableInt;
import com.openrealm.net.core.nettypes.SerializableLong;
import com.openrealm.net.core.nettypes.SerializableShort;
import com.openrealm.net.core.nettypes.SerializableString;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 4 — one row in a PartyUpdatePacket. Holds just the fields the party UI
 * needs to render a member's name + HP/MP bar + status icons. Position +
 * realmId let us draw a "different realm" indicator when a teammate isn't on
 * the same map.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Streamable
public class NetPartyMember extends SerializableFieldType<NetPartyMember> {
    @SerializableField(order = 0, type = SerializableLong.class)
    private long playerId;
    @SerializableField(order = 1, type = SerializableString.class)
    private String name;
    @SerializableField(order = 2, type = SerializableInt.class)
    private int classId;
    @SerializableField(order = 3, type = SerializableInt.class)
    private int health;
    @SerializableField(order = 4, type = SerializableInt.class)
    private int maxHealth;
    @SerializableField(order = 5, type = SerializableInt.class)
    private int mana;
    @SerializableField(order = 6, type = SerializableInt.class)
    private int maxMana;
    @SerializableField(order = 7, type = SerializableInt.class)
    private int level;
    /** Realm the member is currently in. UI greys out members whose realm
     *  differs from the local player's so you know they're not nearby. */
    @SerializableField(order = 8, type = SerializableLong.class)
    private long realmId;
    /** Compact active-effect list. Short array so the UI can render status
     *  icons without needing a separate state packet. */
    @SerializableField(order = 9, type = SerializableShort.class, isCollection = true)
    private Short[] effectIds;

    /** Ability ids bound to this member's hotbar slots 0..3. Lets the party
     *  panel paint the same icon the owner sees on their own ability bar. */
    @SerializableField(order = 10, type = SerializableInt.class, isCollection = true)
    private Integer[] hotbarBindings;

    /** End-of-cooldown epoch-millis per hotbar slot. UI computes remaining
     *  cooldown as (cdEnd - now) and renders a dark fill from the top. */
    @SerializableField(order = 11, type = SerializableLong.class, isCollection = true)
    private Long[] abilityCooldownEnds;

    /** Skill points invested into each of the 4 hotbar abilities. Lets the
     *  party tooltip render the teammate's damage / scaling / per-level
     *  effect line with their REAL invested level (instead of always 0). */
    @SerializableField(order = 12, type = SerializableInt.class, isCollection = true)
    private Integer[] hotbarInvested;

    /** Server-computed stats (base + equipment + enchantments + buffs).
     *  Carried so ability tooltips for a party member can render the same
     *  stat-scaled damage breakdown the owner sees on their own hotbar
     *  (otherwise scalings tied to STR/DEX/WIS/etc would silently fall
     *  back to zero and the tooltip damage would understate by the bonus
     *  amount). 18 bytes on the wire — negligible at 4-member cap, ~2Hz. */
    @SerializableField(order = 13, type = NetStats.class)
    private NetStats stats;
}
