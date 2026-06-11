package com.dorapilot.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Provides the SQLCipher passphrase for the personal-context database.
 *
 * A random 32-byte passphrase is generated once and stored in
 * EncryptedSharedPreferences, whose master key lives in the Android Keystore
 * (hardware-backed where available). The raw passphrase never touches plaintext
 * storage; only the Keystore can decrypt it.
 */
object SecureContextKey {
    private const val PREFS = "dora_secure_ctx"
    private const val KEY_PASSPHRASE = "ctx_db_passphrase"

    /** Stable Base64 secret used directly as the SQLCipher passphrase. */
    @Synchronized
    fun getPassphraseString(context: Context): String {
        val appCtx = context.applicationContext
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            appCtx,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString(KEY_PASSPHRASE, null)?.let { return it }
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = android.util.Base64.encodeToString(generated, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_PASSPHRASE, encoded).apply()
        return encoded
    }
}
