package dev.wahh.alarmshell.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HikAuthTest {
    private val exp = 1_900_000_000L

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun jwt(expiry: Long = exp): String = "${b64("""{"alg":"none"}""")}.${b64("""{"exp":$expiry}""")}.sig"

    private fun loginBody(
        code: Int,
        domain: String? = "api.hik-connect.com",
        session: String = jwt(),
    ): String {
        val area = domain?.let { ""","loginArea":{"apiDomain":"$it"}""" } ?: ""
        return """{"meta":{"code":$code},"loginSession":{"sessionId":"$session","rfSessionId":"refresh-1"}$area}"""
    }

    @Test
    fun `login 200 keeps session refresh and jwt expiry`() {
        val outcome = HikAuth.parseLogin(loginBody(200), "api.hik-connect.com")
        val session = (outcome as LoginOutcome.Success).session
        assertEquals("api.hik-connect.com", session.apiDomain)
        assertEquals(jwt(), session.sessionId)
        assertEquals("refresh-1", session.refreshSessionId)
        assertEquals(exp, session.expiresAtEpochSeconds)
    }

    @Test
    fun `code 200 with regional area switches the api domain`() {
        val outcome = HikAuth.parseLogin(loginBody(200, domain = "api.eu.hik-connect.com"), "api.hik-connect.com")
        assertEquals("api.eu.hik-connect.com", (outcome as LoginOutcome.Success).session.apiDomain)
    }

    @Test
    fun `code 1100 asks the caller to retry on the announced domain`() {
        val outcome = HikAuth.parseLogin("""{"meta":{"code":1100},"loginArea":{"apiDomain":"api.hik-connect.com"}}""", "api.hik-connect.com")
        assertEquals(LoginOutcome.RegionalRedirect("api.hik-connect.com"), outcome)
    }

    @Test
    fun `bad credentials and captcha codes are distinct failures`() {
        listOf(1013, 1014).forEach { code ->
            val failure = assertThrows(HikException::class.java) { HikAuth.parseLogin("""{"meta":{"code":$code}}""", "api.hik-connect.com") }
            assertEquals(HikFailure.INVALID_CREDENTIALS, failure.failure)
        }
        val captcha = assertThrows(HikException::class.java) { HikAuth.parseLogin("""{"meta":{"code":1015}}""", "api.hik-connect.com") }
        assertEquals(HikFailure.CAPTCHA_REQUIRED, captcha.failure)
    }

    @Test
    fun `malformed or host-injecting payloads are rejected`() {
        listOf(
            "not json",
            """{"meta":{"code":200}}""",
            loginBody(200, domain = "api.hik-connect.com/evil"),
            """{"meta":{"code":200},"loginSession":{"sessionId":"opaque","rfSessionId":"r"}}""",
        ).forEach { body ->
            val failure = assertThrows(HikException::class.java) { HikAuth.parseLogin(body, "api.hik-connect.com") }
            assertEquals(HikFailure.MALFORMED_RESPONSE, failure.failure)
        }
    }

    @Test
    fun `refresh rotates both tokens on the stored domain`() {
        val previous = HikSession("api.hik-connect.com", jwt(), "refresh-1", "deadbeef", exp)
        val body = """{"meta":{"code":200},"sessionInfo":{"sessionId":"${jwt(exp + 100)}","refreshSessionId":"refresh-2"}}"""
        val refreshed = HikAuth.parseRefresh(body, previous)
        assertEquals("api.hik-connect.com", refreshed.apiDomain)
        assertEquals(exp + 100, refreshed.expiresAtEpochSeconds)
        assertEquals("refresh-2", refreshed.refreshSessionId)
        assertEquals("deadbeef", refreshed.featureCode)
    }

    @Test
    fun `rejected refresh reports an expired session`() {
        val previous = HikSession("api.hik-connect.com", jwt(), "refresh-1", "deadbeef", exp)
        val failure = assertThrows(HikException::class.java) { HikAuth.parseRefresh("""{"meta":{"code":1013}}""", previous) }
        assertEquals(HikFailure.SESSION_EXPIRED, failure.failure)
    }

    @Test
    fun `device page keeps online state and pagination`() {
        val body =
            """
            {"deviceInfos":[
              {"deviceSerial":"ABC123","fullSerial":"ABC123-X","name":"Home panel","deviceType":"AX PRO","version":"V1.2.3"},
              {"deviceSerial":"CAM9","name":"Doorbell","deviceType":"DS-KD8003"}
            ],"statusInfos":{"ABC123":{"globalStatus":1}},"page":{"hasNext":true}}
            """.trimIndent()
        val page = HikAuth.parseDevices(body)
        assertTrue(page.hasNext)
        assertEquals(2, page.devices.size)
        assertEquals(true, page.devices[0].online)
        assertNull(page.devices[1].online)
    }
}
