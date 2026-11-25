package dev.moriya.vpn.extensions

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted per-extension enabled flags.
 *
 * Stored in the same plain SharedPreferences file as the host-side
 * AdGuard DNS flag (`mokavpn-settings`, see LibtailscaleBackend) —
 * these flags are settings, not secrets, so the encrypted store is
 * not involved. Both the UI and the service-side [ExtensionManager]
 * read/write through this class, so a toggle made while the VPN
 * service is stopped still takes effect at its next start.
 */
class ExtensionSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(info: ExtensionInfo): Boolean =
        prefs.getBoolean(key(info.id), info.defaultEnabled)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(key(id), enabled).apply()
    }

    /**
     * The speed meter's poll interval, in seconds (v1.5.0). One of
     * [SpeedMeterLogic.INTERVAL_PRESETS_SECONDS]; anything else
     * stored (a hand-edited pref, a preset a later version drops)
     * reads back as the default. The UI dropdown writes through
     * here and the extension reads it fresh on every tick, so a
     * change applies live with no restart.
     */
    fun speedMeterIntervalSeconds(): Int =
        SpeedMeterLogic.sanitizeInterval(
            prefs.getInt(SPEED_METER_INTERVAL_KEY, SpeedMeterLogic.DEFAULT_INTERVAL_SECONDS),
        )

    fun setSpeedMeterIntervalSeconds(seconds: Int) {
        prefs.edit()
            .putInt(SPEED_METER_INTERVAL_KEY, SpeedMeterLogic.sanitizeInterval(seconds))
            .apply()
    }

    companion object {
        /** Same file the Layer 3 host-side flags live in. */
        const val PREFS_FILE = "mokavpn-settings"

        private fun key(id: String) = "extension-enabled-$id"

        private const val SPEED_METER_INTERVAL_KEY = "speed-meter-interval-seconds"
    }
}
