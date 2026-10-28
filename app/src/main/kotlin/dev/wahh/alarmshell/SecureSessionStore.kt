package dev.wahh.alarmshell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.wahh.alarmshell.core.HikSession
import dev.wahh.alarmshell.core.SessionCipher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Single encrypted record. The AES key never leaves Android Keystore and backup is disabled,
 * so a copied preferences file is useless on another device.
 */
class SecureSessionStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("alarmshell_session_v1", Context.MODE_PRIVATE)
    private val alias = "alarmshell.hikconnect.session.v1"
    private val slot = "session"

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
    }

    /** An unreadable record is deleted rather than silently trusted or reported as a valid login. */
    @Synchronized fun read(): HikSession? {
        val encoded = prefs.getString(slot, null) ?: return null
        return try {
            SessionCipher.decrypt(key(), Base64.decode(encoded, Base64.NO_WRAP))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    /** commit() is checked: a false return leaves persistence unconfirmed, so callers must treat it as failure. */
    @Synchronized fun write(session: HikSession) {
        val record = SessionCipher.encrypt(key(), session)
        check(prefs.edit().putString(slot, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
            "Session persistence unconfirmed"
        }
    }

    @Synchronized fun clear() {
        check(prefs.edit().remove(slot).commit()) { "Session removal unconfirmed" }
    }
}
