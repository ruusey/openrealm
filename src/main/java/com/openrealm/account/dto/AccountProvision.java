package com.openrealm.account.dto;

import java.util.List;

/**
 * Account provision levels for OpenRealm.
 *
 * OPENREALM_PLAYER     - Base game access, no special privileges.
 * OPENREALM_MODERATOR  - In-game moderation commands (kick, mute, etc). No editor or full admin access.
 * OPENREALM_EDITOR     - Game data editor access (sprite editor, tile editor, etc). No in-game admin commands.
 * OPENREALM_ADMIN      - Full in-game admin commands. Subsumes MODERATOR, EDITOR, and PLAYER.
 * OPENREALM_SYS_ADMIN  - Highest privilege. Subsumes all other provisions.
 *
 * OPENREALM_DEMO      - Guest/demo account with limited features (1 character, 1 chest, no trading).
 *
 * EDITOR and MODERATOR are separate capability branches — neither implies the other.
 * ADMIN and SYS_ADMIN are superuser tiers that implicitly grant all lower capabilities.
 */
public enum AccountProvision {
    OPENREALM_PLAYER,
    OPENREALM_MODERATOR,
    OPENREALM_EDITOR,
    OPENREALM_ADMIN,
    OPENREALM_SYS_ADMIN,
    OPENREALM_DEMO;

    /**
     * Returns true if this provision satisfies the given required provision.
     * ADMIN satisfies everything except SYS_ADMIN-only checks.
     * SYS_ADMIN satisfies everything.
     * EDITOR and MODERATOR only satisfy their own checks (plus PLAYER).
     */
    public boolean satisfies(AccountProvision required) {
        if (this == required) return true;
        // OPENREALM_DEMO is a *flag*, not a privilege tier — it marks an
        // account as a guest with restricted features. ADMIN and SYS_ADMIN
        // must NOT satisfy DEMO; otherwise admins get the 1-character /
        // 1-chest cap intended for guest accounts. DEMO only matches DEMO.
        if (required == OPENREALM_DEMO) return false;
        if (this == OPENREALM_SYS_ADMIN) return true;
        if (this == OPENREALM_ADMIN) return required != OPENREALM_SYS_ADMIN;
        if (required == OPENREALM_PLAYER) return true; // everyone satisfies PLAYER
        return false;
    }

    /**
     * Check if an account's provision list satisfies a set of required provisions.
     * The account passes if ANY of its provisions satisfies ANY of the required provisions.
     */
    public static boolean checkAccess(List<AccountProvision> accountProvisions, AccountProvision[] required) {
        if (accountProvisions == null || accountProvisions.isEmpty()) return false;
        for (AccountProvision held : accountProvisions) {
            for (AccountProvision req : required) {
                if (held.satisfies(req)) return true;
            }
        }
        return false;
    }
}
