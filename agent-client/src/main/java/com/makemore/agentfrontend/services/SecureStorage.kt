package com.makemore.agentfrontend.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secret storage backed by an AES-256-GCM key held in the Android Keystore
 * (hardware-backed where the device supports it). Values are encrypted before
 * being written to private SharedPreferences, so on a captured device the
 * stored auth token / client memories are ciphertext whose key never leaves
 * the Keystore and cannot be exported.
 *
 * Uses the platform crypto primitives directly rather than the deprecated
 * `androidx.security:security-crypto` library. The GCM IV (12 bytes) is
 * randomised per write and prepended to the ciphertext.
 */
class KeystoreEncryptedStorage(
    context: Context,
    private val keyAlias: String,
    prefsName: String = "agent_frontend_secure",
) : StorageService {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun set(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ct, 0, blob, iv.size, ct.size)
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Corrupted / undecryptable (e.g. key reset) — fail safe to "absent".
            null
        }
    }
}

/**
 * Routes sensitive keys (auth tokens, client memories) to a secure backend
 * (Keystore-encrypted) and everything else to plain SharedPreferences. Mirrors
 * the iOS `SecureStorageService`. Backends are injectable so routing can be
 * unit-tested without an Android Keystore.
 */
class SecureStorageService(
    private val secure: StorageService,
    private val standard: StorageService,
    private val explicitSecureKeys: Set<String> = emptySet(),
) : StorageService {

    /** Sensitive if explicitly listed, or named like a credential / memory. */
    fun isSecure(key: String): Boolean {
        if (key in explicitSecureKeys) return true
        val k = key.lowercase()
        return "token" in k || "memor" in k || "secret" in k || "auth" in k
    }

    override fun get(key: String): String? =
        (if (isSecure(key)) secure else standard).get(key)

    override fun set(key: String, value: String?) {
        (if (isSecure(key)) secure else standard).set(key, value)
    }

    companion object {
        /** Production default: Keystore for secrets, SharedPreferences for the rest. */
        fun makeDefault(
            context: Context,
            prefix: String,
            secureKeys: Set<String> = emptySet(),
        ): SecureStorageService = SecureStorageService(
            secure = KeystoreEncryptedStorage(context, keyAlias = "agent_secure_$prefix"),
            standard = SharedPreferencesStorage(context, prefix = prefix),
            explicitSecureKeys = secureKeys,
        )
    }
}
