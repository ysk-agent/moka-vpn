package dev.moriya.vpn.extensions

/**
 * Pure decision logic for the ADB keepalive extension (v1.2.0,
 * DESIGN.md §9.7) — kept free of Android APIs so it is covered by
 * plain-JVM tests in `tests/adb-keepalive/`, the same split the
 * Proxy extension uses (`ProxyProtocol`).
 *
 * The decision is deliberately conservative: a write happens only
 * in the one state where it is both needed and known to stick —
 * the toggle is off, the grant is held, and Wi-Fi is connected.
 * Every other state is observed and reported, never written.
 */
object AdbKeepaliveLogic {

    /**
     * The only setting the keepalive decision concerns — the
     * watcher reads and writes this key and no other. (The
     * extension's control endpoint writes `adb_enabled`, but only
     * on an authorized command, never from this logic; see
     * [AdbControlProtocol].)
     */
    const val SETTING_KEY = "adb_wifi_enabled"

    enum class Action {
        /** Toggle already on — nothing to do. */
        NONE,

        /** Toggle off, grant held, Wi-Fi connected — write 1. */
        RESTORE,

        /**
         * Toggle off and grant held, but Wi-Fi is not connected.
         * The system drops the toggle when Wi-Fi goes away and will
         * not keep a restored value while Wi-Fi is down, so writing
         * would be a fight the extension cannot win; it waits.
         */
        WAIT_FOR_WIFI,

        /**
         * Toggle off but the WRITE_SECURE_SETTINGS grant is not
         * held, so a write would only throw. Never attempted; the
         * missing grant is surfaced in the status instead.
         */
        NO_GRANT,

        /**
         * The toggle value could not be read. Safe by construction:
         * an unknown state is never written.
         */
        UNREADABLE,
    }

    /**
     * Decide from one observation.
     *
     * [toggleValue] is the raw `adb_wifi_enabled` value, or null
     * when it could not be read. 1 is on; any other readable value
     * is treated as off (the system only ever writes 0/1, and the
     * goal state is exactly 1).
     *
     * Precedence: an unreadable value decides nothing (no write);
     * an on toggle needs nothing regardless of the other inputs;
     * without the grant a write is impossible, so that is reported
     * before the Wi-Fi gate.
     */
    fun decide(
        toggleValue: Int?,
        grantHeld: Boolean,
        wifiConnected: Boolean,
    ): Action = when {
        toggleValue == null -> Action.UNREADABLE
        toggleValue == 1 -> Action.NONE
        !grantHeld -> Action.NO_GRANT
        !wifiConnected -> Action.WAIT_FOR_WIFI
        else -> Action.RESTORE
    }
}
