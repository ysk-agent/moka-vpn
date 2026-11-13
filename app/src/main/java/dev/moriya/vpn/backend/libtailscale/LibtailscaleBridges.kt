package dev.moriya.vpn.backend.libtailscale

import android.content.Context
import java.net.NetworkInterface as JavaNetworkInterface
import android.os.Build
import android.provider.Settings
import dev.moriya.vpn.tunnel.TunBuilder
import dev.moriya.vpn.tunnel.TunHost
import java.util.Collections
import java.util.Locale

/**
 * Layer 3 bridges — the host side of the libtailscale contract
 * (DESIGN.md §3.2–§3.4). STUBS with the exact integration shape.
 *
 * When the AAR lands, exactly three declarations change:
 *   class LibtailscaleAppContext(...) : libtailscale.AppContext
 *   class LibtailscaleIPNService(host) : libtailscale.IPNService
 *   class LibtailscaleVPNServiceBuilder(b) : libtailscale.VPNServiceBuilder
 * and each override below gains its libtailscale signature (gomobile
 * generates Kotlin overrides with Long where Go has int in several
 * places — cf. upstream App.kt getSDKInt(): Long). No file outside
 * this package changes.
 */

/** Android facilities the adapter needs, built at the composition root. */
class LibtailscaleAppServices private constructor(
    val dataDir: String,
    val appContextData: LibtailscaleAppContextData,
) {
    companion object {
        fun create(context: Context): LibtailscaleAppServices =
            LibtailscaleAppServices(
                dataDir = context.filesDir.absolutePath,
                appContextData = LibtailscaleAppContextData(context.applicationContext),
            )
    }
}

/**
 * Data source for Go's AppContext callbacks (interfaces.go).
 * The Android-value half is real already (it only uses framework APIs);
 * what is missing until AAR integration is the `: libtailscale.AppContext`
 * supertype.
 *
 * The encrypted-prefs trio (EncryptToPref / DecryptFromPref /
 * GetStateStoreKeysJSON) is implemented for real by
 * [EncryptedPrefStore]: Android Keystore AES-GCM, no Jetpack Security
 * (Moriya's decision, 2026-11-14; DESIGN.md §8). It is
 * production-shaped — the state store holds node key material, so
 * these methods can be wired to the real backend as they are when the
 * AAR integration happens. That wiring has NOT happened yet: the
 * adapter as a whole still reports BackendUnavailable.
 */
class LibtailscaleAppContextData(private val context: Context) {

    fun log(tag: String, line: String) {
        android.util.Log.d(tag, line)
    }

    fun osVersion(): String = Build.VERSION.RELEASE

    fun sdkInt(): Int = Build.VERSION.SDK_INT

    fun deviceName(): String {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.let { return it }
        val manufacturer = Build.MANUFACTURER
        var model = Build.MODEL
        val idx = model.lowercase(Locale.getDefault())
            .indexOf(manufacturer.lowercase(Locale.getDefault()))
        if (idx != -1) model = model.substring(idx + manufacturer.length).trim()
        return "$manufacturer $model"
    }

    fun isChromeOS(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.type.pc")

    /** JSON shape must match upstream App.kt InterfaceJson/AddrJson (§3.2). */
    fun interfacesAsJson(): String {
        val sb = StringBuilder("[")
        var firstInterface = true
        for (nif in Collections.list(JavaNetworkInterface.getNetworkInterfaces())) {
            try {
                if (!firstInterface) sb.append(',')
                firstInterface = false
                sb.append("{\"name\":\"").append(nif.name).append("\",")
                sb.append("\"index\":").append(nif.index).append(',')
                sb.append("\"mtu\":").append(nif.mtu).append(',')
                sb.append("\"up\":").append(nif.isUp).append(',')
                sb.append("\"broadcast\":").append(nif.supportsMulticast()).append(',')
                sb.append("\"loopback\":").append(nif.isLoopback).append(',')
                sb.append("\"pointToPoint\":").append(nif.isPointToPoint).append(',')
                sb.append("\"multicast\":").append(nif.supportsMulticast()).append(',')
                sb.append("\"addrs\":[")
                var firstAddr = true
                for (ia in nif.interfaceAddresses) {
                    val host = ia.address?.hostAddress ?: continue
                    if (!firstAddr) sb.append(',')
                    firstAddr = false
                    sb.append("{\"ip\":\"").append(host).append("\",")
                    sb.append("\"prefixLen\":").append(ia.networkPrefixLength.toInt()).append('}')
                }
                sb.append("]}")
            } catch (_: Exception) {
                continue
            }
        }
        return sb.append(']').toString()
    }

    // Encrypted prefs trio — real implementation via EncryptedPrefStore
    // (Keystore AES-GCM), see the class doc above.
    private val encryptedPrefs = EncryptedPrefStore(context)

    fun encryptToPref(key: String, value: String) {
        encryptedPrefs.encrypt(key, value)
    }

    /** Returns "" when the key is unset, per the AppContext contract. */
    fun decryptFromPref(key: String): String =
        encryptedPrefs.decrypt(key)

    fun stateStoreKeysJson(): String {
        val prefix = "statestore-"
        val keys = encryptedPrefs.keys()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
        return keys.joinToString(prefix = "[", postfix = "]") { "\"${jsonEscape(it)}\"" }
    }

    private fun jsonEscape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}

/**
 * Bridge from our [TunHost] (Layer 1/2) to Go's IPNService
 * (interfaces.go §3.3). Holds no state of its own; protect/builder
 * calls forward to the host, lifecycle calls forward the other way.
 */
class LibtailscaleIPNService(private val host: TunHost) {
    fun id(): String = host.hostId
    fun protect(fd: Int): Boolean = host.protect(fd)
    fun newBuilder(): LibtailscaleVPNServiceBuilder =
        LibtailscaleVPNServiceBuilder(host.newTunBuilder())
    fun close() = host.onVpnDisconnected()
    fun disconnectVPN() = host.onVpnDisconnected()
    fun updateVpnStatus(active: Boolean) = host.onVpnStatusChanged(active)
}

/**
 * Bridge from our [TunBuilder] to Go's VPNServiceBuilder
 * (interfaces.go §3.4). Note Establish()'s return: with the AAR this
 * wraps our TunSession into a libtailscale.ParcelFileDescriptor whose
 * Detach() is TunSession.detachFd().
 */
class LibtailscaleVPNServiceBuilder(private val builder: TunBuilder) {
    fun setMTU(mtu: Int) = builder.setMtu(mtu)
    fun addDNSServer(address: String) = builder.addDnsServer(address)
    fun addSearchDomain(domain: String) = builder.addSearchDomain(domain)
    fun addRoute(address: String, prefix: Int) = builder.addRoute(address, prefix)
    fun excludeRoute(address: String, prefix: Int) = builder.excludeRoute(address, prefix)
    fun addAddress(address: String, prefix: Int) = builder.addAddress(address, prefix)
    fun establish() = builder.establish()
}
