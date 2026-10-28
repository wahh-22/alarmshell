package dev.wahh.alarmshell.core

import java.nio.CharBuffer
import java.security.MessageDigest

/**
 * Unofficial Hik-Connect mobile API contract (reverse-engineered, not supported by Hikvision).
 * Only login, session refresh, device listing and the panel's own arm/status actions are used.
 */
object HikApi {
    const val CLIENT_TYPE = "55"
    const val LANG = "en-US"

    /** Panel account name required by the action authorization header. */
    const val DEFAULT_EN_USER = ""

    private const val PAGE_FILTER = "TIME_PLAN,CONNECTION,SWITCH,STATUS,STATUS_EXT,WIFI,NODISTURB,P2P,KMS,HIDDNS"

    fun sessionHeaders(session: HikSession): Map<String, String> =
        mapOf(
            "clientType" to CLIENT_TYPE,
            "lang" to LANG,
            "featureCode" to session.featureCode,
            "sessionId" to session.sessionId,
        )

    /** Every OTAP action carries the panel user in additionInfo; without it the call is a no-op. */
    fun additionHeaders(
        session: HikSession,
        enUser: String,
    ): Map<String, String> =
        sessionHeaders(session) +
            mapOf(
                "additionInfo" to
                    "{\"ENAuthentication\":{\"userName\":${jsonEncode(enUser)},\"userType\":\"administrator\"}}",
            )

    fun loginRequest(
        apiDomain: String,
        account: String,
        password: CharArray,
        captcha: String?,
    ): HttpRequest {
        require(HikSession.isApiDomain(apiDomain))
        require(account.isNotEmpty() && account.length <= 320)
        val form =
            linkedMapOf(
                "account" to account,
                // The API predates modern auth and requires MD5 — a wire requirement, not storage.
                "password" to md5Hex(password),
            )
        captcha?.takeIf { it.isNotBlank() }?.let { form["imageCode"] = it }
        return HttpRequest(
            method = "POST",
            url = "https://$apiDomain/v3/users/login/v2",
            headers = mapOf("clientType" to CLIENT_TYPE, "lang" to LANG, "featureCode" to HikSession.DEFAULT_FEATURE_CODE),
            form = form,
        )
    }

    /** CAPTCHA image for the challenge presented as login code 1015. */
    fun captchaRequest(
        apiDomain: String,
        account: String,
    ): HttpRequest {
        require(HikSession.isApiDomain(apiDomain))
        require(account.isNotEmpty() && account.length <= 320)
        val url =
            "https://$apiDomain/v3/captcha?account=" +
                java.net.URLEncoder.encode(account, "UTF-8") +
                "&featureCode=" + HikSession.DEFAULT_FEATURE_CODE
        return HttpRequest(
            method = "GET",
            url = url,
            headers = mapOf("clientType" to CLIENT_TYPE, "lang" to LANG, "featureCode" to HikSession.DEFAULT_FEATURE_CODE),
            accept = "image/*",
        )
    }

    /** Refresh carries no sessionId header; the refresh token is the credential. */
    fun refreshRequest(session: HikSession): HttpRequest =
        HttpRequest(
            method = "PUT",
            url = "${session.baseUrl}/v3/apigateway/login",
            headers = mapOf(
                "clientType" to CLIENT_TYPE,
                "lang" to LANG,
                "featureCode" to session.featureCode,
            ),
            form = linkedMapOf(
                "refreshSessionId" to session.refreshSessionId,
                "featureCode" to session.featureCode,
            ),
        )

    fun devicesRequest(
        session: HikSession,
        offset: Int = 0,
        limit: Int = 50,
    ): HttpRequest {
        require(offset >= 0 && limit in 1..50)
        val url =
            "${session.baseUrl}/v3/userdevices/v1/devices/pagelist?groupId=-1&limit=$limit&offset=$offset&filter=$PAGE_FILTER"
        return HttpRequest(method = "GET", url = url, headers = sessionHeaders(session))
    }

    /**
     * Arm or disarm through the panel's own action channel. `stay`/`away` use the arm action with
     * an armType; disarm uses the dedicated disarm action. Values captured from app 6.12.502.
     */
    fun otapArmRequest(
        session: HikSession,
        serial: String,
        mode: DefenceMode,
        enUser: String,
    ): HttpRequest {
        require(serial.isNotEmpty() && serial.length <= 64 && serial.all { it.isLetterOrDigit() })
        require(enUser.isNotBlank() && enUser.length <= 64)
        val action = if (mode == DefenceMode.DISARM) "CtrlSubSystemDisarmList" else "CtrlSubSystemArmList"
        val fields = mutableListOf<String>()
        if (mode != DefenceMode.DISARM) fields += "\"armType\":${jsonEncode(mode.armType)}"
        fields += "\"operationMode\":\"all\""
        return HttpRequest(
            method = "PUT",
            url = "${session.baseUrl}/v3/iot-feature/action/$serial/global/0/AlarmSubSystemMgr/$action",
            headers = additionHeaders(session, enUser),
            json = "{" + fields.joinToString(",") + "}",
        )
    }

    /** Confirms arming despite reported faults (the panel's own "Continue arming?"). */
    fun faultArmConfirmRequest(
        session: HikSession,
        serial: String,
        enUser: String,
        subSystemIds: List<Int>,
    ): HttpRequest {
        require(serial.isNotEmpty() && serial.length <= 64 && serial.all { it.isLetterOrDigit() })
        require(enUser.isNotBlank() && enUser.length <= 64)
        require(subSystemIds.isNotEmpty())
        val body = subSystemIds.joinToString(",", "[", "]") { "{\"preventFaultArm\":false,\"subSystemID\":$it}" }
        return HttpRequest(
            method = "PUT",
            url = "${session.baseUrl}/v3/iot-feature/action/$serial/global/0/AlarmSubSystemMgr/SetSubSystemFaultArmConfirm",
            headers = additionHeaders(session, enUser),
            json = body,
        )
    }

    /** Read-only arm status query (armed/disarmed per partition). */
    fun armStatusRequest(
        session: HikSession,
        serial: String,
        enUser: String,
    ): HttpRequest {
        require(serial.isNotEmpty() && serial.length <= 64 && serial.all { it.isLetterOrDigit() })
        require(enUser.isNotBlank() && enUser.length <= 64)
        return HttpRequest(
            method = "PUT",
            url = "${session.baseUrl}/v3/iot-feature/action/$serial/global/0/AlarmSubSystemMgr/SearchSubSystemArmStatus",
            headers = additionHeaders(session, enUser),
            json = "{\"operationMode\":\"all\"}",
        )
    }

    fun md5Hex(password: CharArray): String {
        require(password.isNotEmpty())
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val digest = MessageDigest.getInstance("MD5")
        try {
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            digest.update(bytes)
            bytes.fill(0)
        } finally {
            encoded.array()?.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed interface LoginOutcome {
    data class Success(val session: HikSession) : LoginOutcome

    /** Code 1100 or a regional `loginArea.apiDomain`: repeat the login against that host. */
    data class RegionalRedirect(val apiDomain: String) : LoginOutcome
}

enum class HikFailure {
    INVALID_CREDENTIALS,
    CAPTCHA_REQUIRED,
    SESSION_EXPIRED,
    MALFORMED_RESPONSE,
    HTTP_ERROR,
    NETWORK_ERROR,
}

/**
 * [detail] is a short structural hint (result code, payload shape) and must never contain response
 * content, account identifiers or tokens.
 */
class HikException(
    val failure: HikFailure,
    message: String,
    cause: Throwable? = null,
    val detail: String? = null,
) : Exception(message, cause)

/** Response parsing kept pure so every branch is testable without a network or Android. */
object HikAuth {
    fun parseLogin(
        body: String,
        requestDomain: String,
    ): LoginOutcome {
        val root = parseObject(body)
        when (val code = metaCode(root)) {
            1013, 1014 -> throw HikException(HikFailure.INVALID_CREDENTIALS, "Account or password rejected")
            1015 -> throw HikException(HikFailure.CAPTCHA_REQUIRED, "Captcha required")
            1100 -> return LoginOutcome.RegionalRedirect(domainOf(root) ?: throw malformed())
            else -> if (code != null && code !in 200..299) throw HikException(HikFailure.HTTP_ERROR, "Login rejected ($code)")
        }
        val session = (root["loginSession"] as? Map<*, *>) ?: throw malformed()
        val sessionId = session["sessionId"] as? String ?: throw malformed()
        val refreshId = session["rfSessionId"] as? String ?: throw malformed()
        // A code-200 answer can still carry a regional host; all later calls must follow it.
        val domain = domainOf(root) ?: requestDomain
        return LoginOutcome.Success(sessionOf(domain, sessionId, refreshId, HikSession.DEFAULT_FEATURE_CODE))
    }

    fun parseRefresh(
        body: String,
        previous: HikSession,
    ): HikSession {
        val root = parseObject(body)
        val code = metaCode(root)
        if (code != null && code != 200 && code !in 200..299) {
            throw HikException(HikFailure.SESSION_EXPIRED, "Session refresh rejected ($code)")
        }
        val info = (root["sessionInfo"] as? Map<*, *>) ?: throw malformed()
        val sessionId = info["sessionId"] as? String ?: throw malformed()
        val refreshId = info["refreshSessionId"] as? String ?: throw malformed()
        return sessionOf(previous.apiDomain, sessionId, refreshId, previous.featureCode)
    }

    fun parseDevices(body: String): DevicePage {
        val root = parseObject(body)
        val infos = root["deviceInfos"] as? List<*> ?: throw malformed()
        val statuses = root["statusInfos"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val devices =
            infos.mapNotNull { entry ->
                val device = entry as? Map<*, *> ?: return@mapNotNull null
                val serial = device["deviceSerial"] as? String ?: return@mapNotNull null
                val status = statuses[serial] as? Map<*, *>
                val globalStatus = (status?.get("globalStatus") as? Number)?.toInt()
                HikDevice(
                    serial = serial,
                    fullSerial = device["fullSerial"] as? String,
                    name = device["name"] as? String ?: "Unnamed device",
                    type = device["deviceType"] as? String,
                    version = device["version"] as? String,
                    online = if (globalStatus == null) null else globalStatus == 1,
                )
            }
        val page = root["page"] as? Map<*, *>
        return DevicePage(devices, page?.get("hasNext") as? Boolean ?: false)
    }

    private fun sessionOf(
        domain: String,
        sessionId: String,
        refreshId: String,
        featureCode: String,
    ): HikSession {
        if (!HikSession.isApiDomain(domain)) throw malformed()
        val expiry =
            try {
                HikSession.jwtExpirySeconds(sessionId)
            } catch (_: RuntimeException) {
                throw malformed()
            }
        return HikSession(domain, sessionId, refreshId, featureCode, expiry)
    }

    private fun metaCode(root: Map<String, Any?>): Int? =
        ((root["meta"] as? Map<*, *>)?.get("code") as? Number)?.toInt()

    /** A present-but-invalid host fails closed instead of silently falling back. */
    private fun domainOf(root: Map<String, Any?>): String? {
        val area = root["loginArea"] as? Map<*, *> ?: return null
        val domain = area["apiDomain"] as? String ?: return null
        return domain.takeIf { HikSession.isApiDomain(it) } ?: throw malformed()
    }

    private fun parseObject(body: String): Map<String, Any?> =
        try {
            JsonParser.parse(body)
        } catch (_: RuntimeException) {
            throw malformed()
        }

    private fun malformed() = HikException(HikFailure.MALFORMED_RESPONSE, "Unexpected cloud response")
}

internal fun payloadMap(value: String): Map<String, Any?>? =
    try {
        JsonParser.parse(value)
    } catch (_: RuntimeException) {
        null
    }

data class DevicePage(
    val devices: List<HikDevice>,
    val hasNext: Boolean,
)

data class HikDevice(
    val serial: String,
    val fullSerial: String?,
    val name: String,
    val type: String?,
    val version: String?,
    val online: Boolean?,
)
