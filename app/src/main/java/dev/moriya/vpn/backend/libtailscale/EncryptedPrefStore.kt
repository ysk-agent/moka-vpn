package dev.moriya.vpn.backend.libtailscale

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Layer 3 encrypted string store behind the libtailscale AppContext
 * prefs trio (DESIGN.md §3.2). Implemented 2026-11-14 with Moriya's
 * chosen option: Android Keystore AES-GCM, implemented here, no
 * Jetpack Security and no other dependency.
 *
 * Shape:
 * - One AES-256 key in the Android Keystore under [KEY_ALIAS],
 *   created on first use and never exported; the key material itself
 *   never leaves the Keystore.
 * - Values are encrypted with AES/GCM/NoPadding (128-bit tag) under
 *   a fresh random IV per write (randomised encryption is required by
 *   the key spec, so `Cipher.init` generates the IV).
 * - The stored form is `"v1:" + Base64(iv || ciphertext)` in a
 *   dedicated SharedPreferences file ([PREFS_FILE]). Key *names* are
 *   stored in the clear — GetStateStoreKeysJSON must enumerate them —
 *   key *values* (the libtailscale state store holds node key
 *   material) are never stored in the clear.
 *
 * Decrypting a missing key returns `""`, matching the AppContext
 * contract (`interfaces.go`: `""` if unset; upstream's store.go maps
 * `""` to "no state"). Corruption or Keystore failure throws
 * [GeneralSecurityException] instead of silently returning data.
 */
class EncryptedPrefStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_FILE, Context.MODE_PRIVATE,
    )

    private val lock = Any()

    fun encrypt(key: String, value: String) {
        val combined: ByteArray
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            combined = cipher.iv + ciphertext
        }
        val stored = FORMAT_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        if (!prefs.edit().putString(key, stored).commit()) {
            throw GeneralSecurityException("failed to persist encrypted pref for key $key")
        }
    }

    fun decrypt(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        if (!stored.startsWith(FORMAT_PREFIX)) {
            throw GeneralSecurityException("encrypted pref for key $key has unknown format")
        }
        val combined = try {
            Base64.decode(stored.substring(FORMAT_PREFIX.length), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("encrypted pref for key $key is not valid Base64", e)
        }
        if (combined.size <= GCM_IV_BYTES) {
            throw GeneralSecurityException("encrypted pref for key $key is truncated")
        }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    /** All stored key names (names only; values stay encrypted). */
    fun keys(): Set<String> = prefs.all.keys.toSet()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "mokavpn-pref-key"
        const val PREFS_FILE = "mokavpn-encrypted-prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_PREFIX = "v1:"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
