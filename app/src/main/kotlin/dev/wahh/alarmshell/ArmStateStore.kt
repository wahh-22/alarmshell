package dev.wahh.alarmshell

import android.content.Context
import dev.wahh.alarmshell.core.ArmState
import dev.wahh.alarmshell.core.Arming

/** Last observed arm state plus the panel serial the widget will command. Not secret data. */
class ArmStateStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("alarmshell_arm_state", Context.MODE_PRIVATE)

    fun read(): ArmState = ArmState.decode(prefs.getString(KEY_STATE, null)) ?: ArmState.UNKNOWN

    fun write(state: ArmState) {
        prefs.edit().putString(KEY_STATE, state.encode()).apply()
    }

    fun markSent(arming: Arming) {
        // "Sent" is not "armed": keep it explicit so the widget never claims a state it did not see.
        write(ArmState(arming, System.currentTimeMillis(), "sent"))
    }

    /** Panel account name used by the arm/disarm authorization header. */
    var enUser: String
        get() = prefs.getString(KEY_EN_USER, dev.wahh.alarmshell.core.HikApi.DEFAULT_EN_USER) ?: dev.wahh.alarmshell.core.HikApi.DEFAULT_EN_USER
        set(value) {
            prefs.edit().putString(KEY_EN_USER, value.trim()).apply()
        }

    var serial: String
        get() = prefs.getString(KEY_SERIAL, DEFAULT_SERIAL) ?: DEFAULT_SERIAL
        set(value) {
            prefs.edit().putString(KEY_SERIAL, value).apply()
        }

    companion object {
        const val DEFAULT_SERIAL = ""
        private const val KEY_STATE = "state"
        private const val KEY_SERIAL = "serial"
        private const val KEY_EN_USER = "enUser"
    }
}
