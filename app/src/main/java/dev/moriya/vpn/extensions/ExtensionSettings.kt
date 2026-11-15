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

    companion object {
        /** Same file the Layer 3 host-side flags live in. */
        const val PREFS_FILE = "mokavpn-settings"

        private fun key(id: String) = "extension-enabled-$id"
    }
}
