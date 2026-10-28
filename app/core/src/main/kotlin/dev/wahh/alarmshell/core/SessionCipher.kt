package dev.wahh.alarmshell.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM record for [HikSession]. The Keystore alias and record version are bound as AAD,
 * so a record cannot be replayed into a different store generation.
 */
object SessionCipher {
    private const val AAD = "alarmshell.hikconnect.session.v1"

    fun encrypt(
        key: SecretKey,
        session: HikSession,
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeUTF(session.apiDomain)
            it.writeUTF(session.sessionId)
            it.writeUTF(session.refreshSessionId)
            it.writeUTF(session.featureCode)
            it.writeLong(session.expiresAtEpochSeconds)
        }
        val plain = buffer.toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            return cipher.iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(
        key: SecretKey,
        record: ByteArray,
    ): HikSession {
        require(record.size in 29..32768) { "session record out of bounds" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.copyOfRange(0, 12)))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(record, 12, record.size - 12)
        try {
            return DataInputStream(ByteArrayInputStream(plain)).use {
                val session =
                    HikSession(
                        apiDomain = it.readUTF(),
                        sessionId = it.readUTF(),
                        refreshSessionId = it.readUTF(),
                        featureCode = it.readUTF(),
                        expiresAtEpochSeconds = it.readLong(),
                    )
                require(it.available() == 0) { "trailing bytes in session record" }
                session
            }
        } finally {
            plain.fill(0)
        }
    }
}
