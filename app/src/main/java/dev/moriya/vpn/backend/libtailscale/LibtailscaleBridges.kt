package dev.moriya.vpn.backend.libtailscale

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.moriya.vpn.tunnel.TunBuilder
import dev.moriya.vpn.tunnel.TunHost
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface as JavaNetworkInterface
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Collections
import java.util.Locale

/**
 * Layer 3 bridges — the host side of the libtailscale contract
 * (DESIGN.md §3.2–§3.4), wired for real against the AAR built from the
 * pinned upstream ref. This package is the only place that implements
 * libtailscale interfaces.
 *
 * Layout:
 * - [LibtailscaleAppContextData] — Android-value source (device name,
 *   interfaces JSON, CA certs, …) plus the encrypted-prefs trio.
 * - [LibtailscaleAppContext] — the `libtailscale.AppContext`
 *   implementation handed to `Libtailscale.start(...)`; it delegates
 *   to the data source and to suppliers the backend owns (platform DNS
 *   config string, socket binding via the current TunHost).
 * - [LibtailscaleIPNService] / [LibtailscaleVPNServiceBuilder] — the
 *   VpnService-side bridges over our Layer 1/2 [TunHost]/[TunBuilder].
 *
 * Honest degradations (DESIGN.md §3.2): the syspolicy trio always
 * throws "not configured" (the Go side treats the error as such), and
 * hardware attestation reports unsupported, matching
 * `hwAttestationPref = false` at start time.
 */

/** Android facilities the adapter needs, built at the composition root. */
class LibtailscaleAppServices private constructor(
    val dataDir: String,
    val appContextData: LibtailscaleAppContextData,
    val encryptedPrefs: EncryptedPrefStore,
) {
    companion object {
        fun create(context: Context): LibtailscaleAppServices {
            val appContext = context.applicationContext
            return LibtailscaleAppServices(
                dataDir = context.filesDir.absolutePath,
                appContextData = LibtailscaleAppContextData(appContext),
                encryptedPrefs = EncryptedPrefStore(appContext),
            )
        }
    }
}

/** Thrown by the syspolicy getters: upstream's "not configured" signal. */
class SyspolicyNotConfiguredException(key: String) :
    Exception("syspolicy key not configured: $key")

/**
 * Data source for Go's AppContext callbacks (interfaces.go).
 *
 * The encrypted-prefs trio is implemented by [EncryptedPrefStore]:
 * Android Keystore AES-GCM, no Jetpack Security (Moriya's decision,
 * 2026-11-14; DESIGN.md §8). The state store holds node key material,
 * and these methods are wired to the real backend.
 */
class LibtailscaleAppContextData(private val context: Context) {

    private val encryptedPrefs = EncryptedPrefStore(context)

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

    fun installSource(): String {
        // Reduced form of upstream AppSourceChecker: report the
        // initiating installer package where the platform exposes it.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName)
                    .initiatingPackageName ?: ""
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName) ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** User CA certificates from AndroidCAStore, PEM-encoded (upstream App.kt). */
    fun userCACertsPEM(): ByteArray {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        val out = ByteArrayOutputStream()
        for (alias in ks.aliases()) {
            if (!alias.startsWith("user:")) continue
            val cert: Certificate = ks.getCertificate(alias) ?: continue
            val b64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            out.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.UTF_8))
            b64.chunked(64).forEach { out.write((it + "\n").toByteArray(Charsets.UTF_8)) }
            out.write("-----END CERTIFICATE-----\n".toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

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
 * The `libtailscale.AppContext` implementation (interfaces.go, 25
 * methods in the generated binding). Constructed and owned by
 * [LibtailscaleBackend]; [platformDnsConfig] and [bindSocket] are
 * supplied by the backend so this object stays free of backend state.
 */
class LibtailscaleAppContext(
    private val data: LibtailscaleAppContextData,
    private val platformDnsConfig: () -> String,
    private val bindSocket: (Int) -> Boolean,
) : libtailscale.AppContext {

    override fun log(tag: String, line: String) = data.log(tag, line)

    override fun encryptToPref(key: String, value: String) = data.encryptToPref(key, value)

    override fun decryptFromPref(key: String): String = data.decryptFromPref(key)

    override fun getStateStoreKeysJSON(): String = data.stateStoreKeysJson()

    override fun getOSVersion(): String = data.osVersion()

    override fun getSDKInt(): Long = data.sdkInt().toLong()

    override fun getDeviceName(): String = data.deviceName()

    override fun getInstallSource(): String = data.installSource()

    override fun shouldUseGoogleDNSFallback(): Boolean = false

    override fun isChromeOS(): Boolean = data.isChromeOS()

    override fun isClientLoggingEnabled(): Boolean = false

    override fun getInterfacesAsJson(): String = data.interfacesAsJson()

    override fun getPlatformDNSConfig(): String = platformDnsConfig()

    override fun getSyspolicyStringValue(key: String): String =
        throw SyspolicyNotConfiguredException(key)

    override fun getSyspolicyBooleanValue(key: String): Boolean =
        throw SyspolicyNotConfiguredException(key)

    override fun getSyspolicyStringArrayJSONValue(key: String): String =
        throw SyspolicyNotConfiguredException(key)

    override fun hardwareAttestationKeySupported(): Boolean = false

    override fun hardwareAttestationKeyCreate(): String =
        throw UnsupportedOperationException("hardware attestation not supported")

    override fun hardwareAttestationKeyRelease(id: String) =
        throw UnsupportedOperationException("hardware attestation not supported")

    override fun hardwareAttestationKeyPublic(id: String): ByteArray =
        throw UnsupportedOperationException("hardware attestation not supported")

    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray =
        throw UnsupportedOperationException("hardware attestation not supported")

    override fun hardwareAttestationKeyLoad(id: String) =
        throw UnsupportedOperationException("hardware attestation not supported")

    override fun bindSocketToNetwork(fd: Int): Boolean = bindSocket(fd)

    override fun getUserCACertsPEM(): ByteArray = data.userCACertsPEM()
}

/**
 * Bridge from our [TunHost] (Layer 1/2) to Go's IPNService
 * (interfaces.go §3.3). Holds no state of its own; protect/builder
 * calls forward to the host, lifecycle calls forward the other way.
 */
class LibtailscaleIPNService(private val host: TunHost) : libtailscale.IPNService {
    override fun id(): String = host.hostId
    override fun protect(fd: Int): Boolean = host.protect(fd)
    override fun newBuilder(): libtailscale.VPNServiceBuilder =
        LibtailscaleVPNServiceBuilder(host.newTunBuilder())
    override fun close() = host.onVpnDisconnected()
    override fun disconnectVPN() = host.onVpnDisconnected()
    override fun updateVpnStatus(active: Boolean) = host.onVpnStatusChanged(active)
}

/**
 * Bridge from our [TunBuilder] to Go's VPNServiceBuilder
 * (interfaces.go §3.4). Establish() wraps our TunSession into a
 * libtailscale.ParcelFileDescriptor whose detach() is
 * TunSession.detachFd().
 */
class LibtailscaleVPNServiceBuilder(private val builder: TunBuilder) :
    libtailscale.VPNServiceBuilder {
    override fun setMTU(mtu: Int) = builder.setMtu(mtu)
    override fun addDNSServer(address: String) = builder.addDnsServer(address)
    override fun addSearchDomain(domain: String) = builder.addSearchDomain(domain)
    override fun addRoute(address: String, prefix: Int) = builder.addRoute(address, prefix)
    override fun excludeRoute(address: String, prefix: Int) = builder.excludeRoute(address, prefix)
    override fun addAddress(address: String, prefix: Int) = builder.addAddress(address, prefix)
    override fun establish(): libtailscale.ParcelFileDescriptor? {
        val session = builder.establish() ?: return null
        return object : libtailscale.ParcelFileDescriptor {
            override fun detach(): Int = session.detachFd()
        }
    }
}
