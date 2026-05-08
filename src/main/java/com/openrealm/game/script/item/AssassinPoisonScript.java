package com.openrealm.game.script.item;

import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.client.packet.CreateEffectPacket;
import com.openrealm.net.realm.Realm;
import com.openrealm.net.realm.RealmManagerServer;

/**
 * Assassin Poison ability (items 249-255 T0-T6, plus 829 = Soulrot Vial,
 * the untiered variant).
 * Throws a poison vial at cursor position with a 0.8s travel time.
 * On landing, enemies in the AoE get POISONED and take DoT (ignores defense).
 *
 * The throw delay is tracked per tick on the Realm — no threads are blocked.
 */
public class AssassinPoisonScript extends UseableItemScriptBase {

    private static final int MIN_ID = 249;
    private static final int MAX_ID = 255;
    /** Untiered "Soulrot Vial": flat 1000 damage over 10s. */
    private static final int SOULROT_VIAL_ID = 829;
    private static final float POISON_RADIUS = 128.0f;
    private static final long THROW_DURATION_MS = 800;

    public AssassinPoisonScript(RealmManagerServer mgr) {
        super(mgr);
    }

    @Override
    public boolean handles(int itemId) {
        return (itemId >= MIN_ID && itemId <= MAX_ID) || itemId == SOULROT_VIAL_ID;
    }

    @Override
    public int getTargetItemId() {
        return MIN_ID;
    }

    @Override
    public void invokeUseItem(Realm targetRealm, Player player, GameItem item) {
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem) {
        invokeItemAbility(targetRealm, player, abilityItem,
                player.getPos().clone(player.getSize() / 2, player.getSize() / 2));
    }

    @Override
    public void invokeItemAbility(Realm targetRealm, Player player, GameItem abilityItem, Vector2f targetPos) {
        final Vector2f center = (targetPos != null) ? targetPos
                : player.getPos().clone(player.getSize() / 2, player.getSize() / 2);

        final int totalDamage;
        final long poisonDuration;
        if (abilityItem.getItemId() == SOULROT_VIAL_ID) {
            // Untiered Soulrot: flat 1000 + att, ticks over 10s. Wider damage
            // window than the tiered poisons but no scaling.
            totalDamage = 1000 + player.getComputedStats().getAtt();
            poisonDuration = 10000;
        } else {
            int tier = abilityItem.getItemId() - MIN_ID;
            totalDamage = 150 + tier * 150 + player.getComputedStats().getAtt();
            poisonDuration = 3000 + tier * 250;
        }

        // Broadcast the throw arc visual (800ms travel time). Tier flows
        // through both the throw and the splash so the colour family stays
        // consistent. Soulrot Vial is untiered -> tier 0.
        final byte tier = (abilityItem.getItemId() == SOULROT_VIAL_ID)
                ? (byte) 0 : (byte) (abilityItem.getItemId() - MIN_ID);
        final Vector2f playerCenter = player.getPos().clone(player.getSize() / 2, player.getSize() / 2);
        this.mgr.enqueueServerPacketToRealm(targetRealm, CreateEffectPacket.lineEffect(
                CreateEffectPacket.EFFECT_POISON_SPLASH,
                playerCenter.x, playerCenter.y, center.x, center.y, (short) THROW_DURATION_MS, tier));

        // Register the pending throw — Realm will process the landing on tick after 800ms
        targetRealm.registerPoisonThrow(THROW_DURATION_MS, player.getId(),
                center.x, center.y, POISON_RADIUS, totalDamage, poisonDuration, tier);
    }
}
