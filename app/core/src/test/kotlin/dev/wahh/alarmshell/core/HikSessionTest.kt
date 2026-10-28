package dev.wahh.alarmshell.core

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HikSessionTest {
    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private val token = "${b64("""{"alg":"HS256"}""")}.${b64("""{"exp":1900000000}""")}.signature"

    @Test
    fun `encode and decode round-trip every field`() {
        val session = HikSession("api.eu.hik-connect.com", token, "refresh", "deadbeef", 1_900_000_000L)
        assertEquals(session, HikSession.decode(session.encode()))
    }

    @Test
    fun `host validation blocks scheme paths ports and userinfo`() {
        listOf(
            "api.hik-connect.com/evil",
            "https://api.hik-connect.com",
            "api.hik-connect.com:443",
            "user@api.hik-connect.com",
            "localhost",
            "",
        ).forEach { domain ->
            assertThrows(IllegalArgumentException::class.java) {
                HikSession(domain, token, "refresh", "deadbeef", 1_900_000_000L)
            }
        }
    }

    @Test
    fun `refresh trigger follows the server JWT expiry`() {
        val session = HikSession("api.hik-connect.com", token, "refresh", "deadbeef", 1_900_000_000L)
        assertFalse(session.needsRefresh(1_900_000_000L - 3601))
        assertTrue(session.needsRefresh(1_900_000_000L - 3600))
        assertTrue(session.needsRefresh(1_900_000_000L))
        assertEquals(1_900_000_000L, HikSession.jwtExpirySeconds(token))
    }

    @Test
    fun `decode rejects unknown versions and oversized records`() {
        assertThrows(IllegalArgumentException::class.java) {
            HikSession.decode("""{"v":2,"domain":"api.hik-connect.com","session":"s","refresh":"r","feature":"deadbeef","exp":1}""")
        }
        assertThrows(IllegalArgumentException::class.java) { HikSession.decode("x".repeat(32769)) }
    }

    @Test
    fun `cipher round-trips and binds the record to the store generation`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val session = HikSession("api.hik-connect.com", token, "refresh", "deadbeef", 1_900_000_000L)
        val record = SessionCipher.encrypt(key, session)
        assertEquals(session, SessionCipher.decrypt(key, record))
        assertFalse(String(record, Charsets.ISO_8859_1).contains(token))
        assertArrayEquals(record, record.copyOf())

        val tampered = record.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertThrows(Exception::class.java) { SessionCipher.decrypt(key, tampered) }

        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { SessionCipher.decrypt(other, record) }
    }
}
