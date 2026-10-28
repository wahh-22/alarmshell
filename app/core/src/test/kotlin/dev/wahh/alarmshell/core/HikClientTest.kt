package dev.wahh.alarmshell.core

import java.util.ArrayDeque
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HikClientTest {
    private val exp = 1_900_000_000L

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun jwt(expiry: Long = exp): String = "${b64("""{"alg":"none"}""")}.${b64("""{"exp":$expiry}""")}.sig"

    private fun session(expiry: Long = exp) = HikSession("api.hik-connect.com", jwt(expiry), "refresh", "deadbeef", expiry)

    private class FakeTransport(
        vararg replies: HttpResponse,
    ) : Transport {
        private val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return queue.removeFirst()
        }
    }

    @Test
    fun `login follows a regional redirect once and keeps the token fields`() {
        val replies =
            arrayOf(
                HttpResponse.text(200, """{"meta":{"code":1100},"loginArea":{"apiDomain":"api.eu.hik-connect.com"}}"""),
                HttpResponse.text(200, """{"meta":{"code":200},"loginSession":{"sessionId":"${jwt()}","rfSessionId":"refresh"}}"""),
            )
        val transport = FakeTransport(*replies)
        val result = HikClient(transport).login("me@example.com", "secret".toCharArray())
        assertEquals("api.eu.hik-connect.com", result.apiDomain)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[1].url.startsWith("https://api.eu.hik-connect.com"))
    }

    @Test
    fun `login body carries the md5 hex digest and never the password`() {
        val transport =
            FakeTransport(
                HttpResponse.text(200, """{"meta":{"code":200},"loginSession":{"sessionId":"${jwt()}","rfSessionId":"refresh"}}"""),
            )
        HikClient(transport).login("me@example.com", "secret".toCharArray(), captcha = "AB12")
        val body = String(transport.requests.single().bodyBytes()!!, Charsets.UTF_8)
        assertTrue(body.contains("password=5ebe2294ecd0e0f08eab7690d2a6ee69"))
        assertTrue(body.contains("imageCode=AB12"))
        assertFalse(body.contains("secret"))
    }

    @Test
    fun `refresh only happens when the stored jwt is close to expiry`() {
        val transport =
            FakeTransport(HttpResponse.text(200, """{"meta":{"code":200},"sessionInfo":{"sessionId":"${jwt(exp + 1)}","refreshSessionId":"r2"}}"""))
        assertEquals(jwt(exp), HikClient(transport, now = { exp - 7200 }).ensureFresh(session()).sessionId)
        assertTrue(transport.requests.isEmpty())

        val later = HikClient(transport, now = { exp })
        assertEquals("r2", later.ensureFresh(session()).refreshSessionId)
        assertEquals("PUT", transport.requests.single().method)
    }

    @Test
    fun `device list walks pages and stops at hasNext false`() {
        val transport =
            FakeTransport(
                HttpResponse.text(200, """{"deviceInfos":[{"deviceSerial":"A1","name":"A"}],"page":{"hasNext":true}}"""),
                HttpResponse.text(200, """{"deviceInfos":[{"deviceSerial":"B2","name":"B"}],"page":{"hasNext":false}}"""),
            )
        val devices = HikClient(transport).devices(session())
        assertEquals(listOf("A1", "B2"), devices.map { it.serial })
        assertTrue(transport.requests[1].url.contains("offset=50"))
    }

    @Test
    fun `stay sends the panel action with armType and the authorization header`() {
        val transport = FakeTransport(HttpResponse.text(200, """{"meta":{"code":200},"data":{}}"""))
        HikClient(transport).setDefenceMode(session(), "ABC123", DefenceMode.STAY, enUser = "admin")
        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertTrue(request.url.endsWith("/AlarmSubSystemMgr/CtrlSubSystemArmList"))
        assertEquals("""{"armType":"stay","operationMode":"all"}""", request.json)
        assertTrue(request.headers.getValue("additionInfo").contains("\"userName\":\"admin\""))
    }

    @Test
    fun `disarm uses the dedicated action without armType`() {
        val transport = FakeTransport(HttpResponse.text(200, """{"meta":{"code":200},"data":{}}"""))
        HikClient(transport).setDefenceMode(session(), "ABC123", DefenceMode.DISARM, enUser = "admin")
        val request = transport.requests.single()
        assertTrue(request.url.endsWith("/AlarmSubSystemMgr/CtrlSubSystemDisarmList"))
        assertEquals("""{"operationMode":"all"}""", request.json)
    }

    @Test
    fun `arm reports pending faults and confirms them exactly once`() {
        val armBody =
            """{"meta":{"code":200},"data":{"armMode":"process","subSystemFaults":[{"subSystemID":1},{"subSystemID":2}]}}"""
        val transport =
            FakeTransport(
                HttpResponse.text(200, armBody),
                HttpResponse.text(200, """{"meta":{"code":200},"data":{}}"""),
            )
        HikClient(transport).setDefenceMode(session(), "ABC123", DefenceMode.STAY, enUser = "admin")
        assertEquals(2, transport.requests.size)
        val confirm = transport.requests[1]
        assertTrue(confirm.url.endsWith("/SetSubSystemFaultArmConfirm"))
        assertEquals("""[{"preventFaultArm":false,"subSystemID":1},{"preventFaultArm":false,"subSystemID":2}]""", confirm.json)
    }

    @Test
    fun `arm status maps to a cloud state without inventing stay or away`() {
        val transport =
            FakeTransport(
                HttpResponse.text(200, """{"meta":{"code":200},"data":[{"subSystemID":1,"armStatus":"armed"},{"subSystemID":2,"armStatus":"armed"}]}"""),
                HttpResponse.text(200, """{"meta":{"code":200},"data":[{"subSystemID":1,"armStatus":"disarmed"}]}"""),
            )
        val client = HikClient(transport, now = { exp })
        val armed = client.readArmState(session(), "ABC123", enUser = "admin")
        assertEquals("armed", armed.raw)
        assertEquals(Arming.UNKNOWN, armed.arming)
        assertEquals(Arming.DISARMED, client.readArmState(session(), "ABC123", enUser = "admin").arming)
    }

    @Test
    fun `unauthorized and offline transports are reported as failures`() {
        val unauthorized = HikClient(FakeTransport(HttpResponse.text(401, "")))
        assertEquals(HikFailure.SESSION_EXPIRED, assertThrows(HikException::class.java) { unauthorized.devices(session()) }.failure)

        val offline =
            object : Transport {
                override fun execute(request: HttpRequest): HttpResponse = throw java.net.UnknownHostException("api.hik-connect.com")
            }
        assertEquals(HikFailure.NETWORK_ERROR, assertThrows(HikException::class.java) { HikClient(offline).devices(session()) }.failure)
    }

    @Test
    fun `client exposes only the reads plus the three explicit modes`() {
        val methods =
            HikClient::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.name.contains('$') }
                .map { it.name }
                .toSet()
        assertEquals(
            setOf("login", "refresh", "captchaImage", "ensureFresh", "devices", "setDefenceMode", "readArmState"),
            methods,
        )
        val command = HikClient::class.java.declaredMethods.first { it.name == "setDefenceMode" }
        assertTrue(command.parameterTypes.contains(DefenceMode::class.java))
        assertFalse(command.parameterTypes.contains(Integer.TYPE))
    }
}
