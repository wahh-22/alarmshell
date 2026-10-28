package dev.wahh.alarmshell.core

import java.io.IOException

/**
 * Cloud client: login, session refresh, device listing and the panel's own arm/status actions.
 * There is no generic ISAPI surface and no free-form command: the three [DefenceMode] values are
 * the only writes.
 */
class HikClient(
    private val transport: Transport,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun login(
        account: String,
        password: CharArray,
        captcha: String? = null,
        apiDomain: String = HikSession.DEFAULT_API_DOMAIN,
    ): HikSession {
        var domain = apiDomain
        repeat(2) {
            val response = send(HikApi.loginRequest(domain, account, password, captcha))
            if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Login HTTP ${response.status}")
            when (val outcome = HikAuth.parseLogin(response.body, domain)) {
                is LoginOutcome.Success -> return outcome.session
                is LoginOutcome.RegionalRedirect -> domain = outcome.apiDomain
            }
        }
        throw HikException(HikFailure.MALFORMED_RESPONSE, "Cloud did not settle on a login domain")
    }

    fun refresh(session: HikSession): HikSession {
        val response = send(HikApi.refreshRequest(session))
        if (response.status !in 200..299) throw HikException(HikFailure.SESSION_EXPIRED, "Refresh HTTP ${response.status}")
        return HikAuth.parseRefresh(response.body, session)
    }

    /** Refresh a little early: the server decides abruptly and phase 1 never retries blindly. */
    fun ensureFresh(session: HikSession): HikSession =
        if (session.needsRefresh(now())) refresh(session) else session

    /** PNG bytes for the login captcha challenge (code 1015). Not logged, not persisted. */
    fun captchaImage(
        account: String,
        apiDomain: String = HikSession.DEFAULT_API_DOMAIN,
    ): ByteArray {
        val response = send(HikApi.captchaRequest(apiDomain, account))
        if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Captcha HTTP ${response.status}")
        if (response.bytes.isEmpty()) throw HikException(HikFailure.MALFORMED_RESPONSE, "Empty captcha image")
        return response.bytes
    }

    fun devices(session: HikSession): List<HikDevice> {
        val all = mutableListOf<HikDevice>()
        var offset = 0
        repeat(MAX_PAGES) {
            val response = send(HikApi.devicesRequest(session, offset = offset))
            if (response.status == 401) throw HikException(HikFailure.SESSION_EXPIRED, "Device list unauthorized")
            if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Device list HTTP ${response.status}")
            val page = HikAuth.parseDevices(response.body)
            all += page.devices
            if (!page.hasNext) return all
            offset += PAGE_SIZE
        }
        return all
    }

    /**
     * Sends one explicit mode. When the panel reports pending faults the arm is confirmed exactly
     * like the official app's "Fault(s) detected. Continue arming?" prompt.
     */
    fun setDefenceMode(
        session: HikSession,
        serial: String,
        mode: DefenceMode,
        enUser: String = HikApi.DEFAULT_EN_USER,
    ): Int {
        val response = send(HikApi.otapArmRequest(session, serial, mode, enUser))
        if (response.status == 401) throw HikException(HikFailure.SESSION_EXPIRED, "Command unauthorized")
        if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Command HTTP ${response.status}")
        val root = payloadMap(response.body)
        val code = ((root?.get("meta") as? Map<*, *>)?.get("code") as? Number)?.toInt()
        if (code != null && code != 200) {
            throw HikException(HikFailure.HTTP_ERROR, "Command refused", detail = "code=$code")
        }
        if (mode != DefenceMode.DISARM) confirmFaults(session, serial, enUser, root)
        return code ?: 200
    }

    /** Arm state from the cloud: armed/disarmed only; stay vs away comes from the app's push. */
    fun readArmState(
        session: HikSession,
        serial: String,
        enUser: String = HikApi.DEFAULT_EN_USER,
    ): ArmState {
        val response = send(HikApi.armStatusRequest(session, serial, enUser))
        if (response.status == 401) throw HikException(HikFailure.SESSION_EXPIRED, "Status unauthorized")
        if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Status HTTP ${response.status}")
        val root = payloadMap(response.body) ?: throw HikException(HikFailure.MALFORMED_RESPONSE, "Arm status not JSON")
        val code = ((root["meta"] as? Map<*, *>)?.get("code") as? Number)?.toInt()
        if (code != null && code != 200) throw HikException(HikFailure.HTTP_ERROR, "Arm status refused", detail = "code=$code")
        val statuses =
            (root["data"] as? List<*> ?: emptyList<Any?>())
                .mapNotNull { (it as? Map<*, *>)?.get("armStatus") as? String }
                .map { it.lowercase() }
        val raw =
            when {
                statuses.any { it.contains("arm") && !it.contains("disarm") } -> "armed"
                statuses.any { it.contains("disarm") } -> "disarmed"
                else -> "unknown"
            }
        return ArmState(if (raw == "disarmed") Arming.DISARMED else Arming.UNKNOWN, now(), "cloud", raw)
    }

    private fun confirmFaults(
        session: HikSession,
        serial: String,
        enUser: String,
        armRoot: Map<String, Any?>?,
    ) {
        val data = armRoot?.get("data") as? Map<*, *> ?: return
        val faults = data["subSystemFaults"] as? List<*> ?: return
        val ids = faults.mapNotNull { (it as? Map<*, *>)?.get("subSystemID").let { id -> (id as? Number)?.toInt() } }
        if (ids.isEmpty()) return
        val response = send(HikApi.faultArmConfirmRequest(session, serial, enUser, ids))
        if (response.status == 401) throw HikException(HikFailure.SESSION_EXPIRED, "Fault confirm unauthorized")
        if (response.status !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Fault confirm HTTP ${response.status}")
        val code = ((payloadMap(response.body)?.get("meta") as? Map<*, *>)?.get("code") as? Number)?.toInt()
        if (code != null && code != 200) throw HikException(HikFailure.HTTP_ERROR, "Fault confirm refused", detail = "code=$code")
    }

    private fun send(request: HttpRequest): HttpResponse =
        try {
            transport.execute(request)
        } catch (error: IOException) {
            throw HikException(HikFailure.NETWORK_ERROR, "Cloud unreachable", error)
        } catch (error: RuntimeException) {
            throw HikException(HikFailure.NETWORK_ERROR, "Cloud call failed", error)
        }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 4
    }
}
