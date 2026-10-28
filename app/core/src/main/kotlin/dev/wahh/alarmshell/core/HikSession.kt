package dev.wahh.alarmshell.core

/**
 * Cloud session for the unofficial Hik-Connect (Hik-Connect / Hikconnect) API.
 *
 * The session ID is a JWT with full-account power, and the refresh ID can mint new ones,
 * so both are persisted only through [SessionCipher] behind Android Keystore.
 * The account password (and its MD5) is never part of this record.
 */
data class HikSession(
    val apiDomain: String,
    val sessionId: String,
    val refreshSessionId: String,
    val featureCode: String,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(isApiDomain(apiDomain)) { "apiDomain must be a plain DNS host" }
        require(sessionId.isNotEmpty() && sessionId.length <= MAX_TOKEN) { "sessionId out of bounds" }
        require(refreshSessionId.isNotEmpty() && refreshSessionId.length <= MAX_TOKEN) { "refreshSessionId out of bounds" }
        require(featureCode.matches(Regex("[0-9a-fA-F]{1,64}"))) { "featureCode must be hex" }
        require(expiresAtEpochSeconds > 0) { "expiresAtEpochSeconds must be positive" }
    }

    val baseUrl: String get() = "https://$apiDomain"

    /** Refresh a little early: the server decides abruptly and a failed call is not retried in phase 1. */
    fun needsRefresh(
        nowEpochSeconds: Long,
        marginSeconds: Long = REFRESH_MARGIN_SECONDS,
    ): Boolean = nowEpochSeconds >= expiresAtEpochSeconds - marginSeconds

    fun withRefreshed(
        apiDomain: String,
        sessionId: String,
        refreshSessionId: String,
        expiresAtEpochSeconds: Long,
    ): HikSession = copy(
        apiDomain = apiDomain,
        sessionId = sessionId,
        refreshSessionId = refreshSessionId,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

    fun encode(): String =
        jsonEncode(
            linkedMapOf(
                "v" to 1,
                "domain" to apiDomain,
                "session" to sessionId,
                "refresh" to refreshSessionId,
                "feature" to featureCode,
                "exp" to expiresAtEpochSeconds,
            ),
        )

    companion object {
        const val DEFAULT_API_DOMAIN = "api.hik-connect.com"
        const val DEFAULT_FEATURE_CODE = "875f40599b2f32d1ed37ce7441cdb2d2"
        const val REFRESH_MARGIN_SECONDS = 3600L
        private const val MAX_TOKEN = 8192

        fun isApiDomain(value: String): Boolean =
            value.length in 3..200 && value.matches(Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+"))

        /** JWT payload `exp`; the server refuses the session after it, regardless of local clock skew. */
        fun jwtExpirySeconds(sessionId: String): Long {
            val parts = sessionId.split('.')
            require(parts.size == 3) { "sessionId is not a JWT" }
            val payload = parts[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val claims = JsonParser.parse(String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
            val exp = (claims["exp"] as? Number)?.toLong() ?: error("JWT has no exp")
            require(exp > 0) { "JWT exp must be positive" }
            return exp
        }

        fun decode(encoded: String): HikSession {
            require(encoded.length <= 32768) { "session record too large" }
            val fields = JsonParser.parse(encoded)
            require((fields["v"] as? Number)?.toInt() == 1) { "unsupported session version" }
            return HikSession(
                apiDomain = fields["domain"] as? String ?: error("missing domain"),
                sessionId = fields["session"] as? String ?: error("missing session"),
                refreshSessionId = fields["refresh"] as? String ?: error("missing refresh"),
                featureCode = fields["feature"] as? String ?: error("missing feature"),
                expiresAtEpochSeconds = (fields["exp"] as? Number)?.toLong() ?: error("missing exp"),
            )
        }
    }
}
