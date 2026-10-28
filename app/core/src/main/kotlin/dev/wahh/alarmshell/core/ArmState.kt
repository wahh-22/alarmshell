package dev.wahh.alarmshell.core

/** Panel arming state as reported by the panel or its app. */
enum class Arming(
    val label: String,
) {
    DISARMED("DISARMED"),
    AWAY("ARMED · AWAY"),
    STAY("ARMED · STAY"),
    ARMING("ARMING"),
    DISARMING("DISARMING"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun of(value: String?): Arming {
            val wire = value?.lowercase() ?: return UNKNOWN
            return when {
                wire.contains("disarm") -> DISARMED
                wire.contains("stay") -> STAY
                wire.contains("away") -> AWAY
                wire == "arming" -> ARMING
                wire == "disarming" -> DISARMING
                else -> UNKNOWN
            }
        }
    }
}

/** The only three explicit commands this app sends. */
enum class DefenceMode(
    val armType: String?,
) {
    STAY("stay"),
    AWAY("away"),
    DISARM(null),
}

/** Last arm state we actually observed, never an assumption. */
data class ArmState(
    val arming: Arming,
    val atMillis: Long,
    val source: String,
    /** Cloud armStatus literal ("armed"/"disarmed"); it does not distinguish stay from away. */
    val raw: String? = null,
) {
    fun encode(): String = "${arming.name}|$atMillis|$source|${raw ?: ""}"

    companion object {
        val UNKNOWN = ArmState(Arming.UNKNOWN, 0, "none")

        fun decode(encoded: String?): ArmState? {
            val parts = encoded?.split('|') ?: return null
            if (parts.size !in 3..4) return null
            val arming = Arming.entries.firstOrNull { it.name == parts[0] } ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            return ArmState(arming, at, parts[2], parts.getOrNull(3)?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * Reads arm state from the official app's own notifications (push-driven), mirroring how the
 * Hik-Connect widget is updated: no polling, no persistent connection.
 */
object NotificationInterpreter {
    const val HIK_PACKAGE = "com.connect.enduser"

    fun interpret(
        packageName: String,
        title: String?,
        text: String?,
        channelId: String?,
    ): ArmState? {
        if (packageName != HIK_PACKAGE) return null
        val haystack = listOfNotNull(title, text, channelId).joinToString(" ").lowercase()
        if (haystack.isBlank()) return null
        // Alarm events do not change the arming state.
        if (listOf("alarm", "alarma", "triggered", "disparo").any { haystack.contains(it) } &&
            !haystack.contains("disarm") && !haystack.contains("desarm")
        ) {
            return null
        }
        val arming =
            when {
                listOf("disarmed", "desarmado", "desarmada", "desactivado").any { haystack.contains(it) } -> Arming.DISARMED
                listOf("stay", "en casa", "parcial", "perimetro armado").any { haystack.contains(it) } -> Arming.STAY
                listOf("away", "ausente", "total armado", "armed away").any { haystack.contains(it) } -> Arming.AWAY
                listOf("armed", "armado", "armada").any { haystack.contains(it) } -> Arming.AWAY
                "arming" == haystack.trim() -> Arming.ARMING
                else -> null
            } ?: return null
        return ArmState(arming, System.currentTimeMillis(), "notification")
    }
}
