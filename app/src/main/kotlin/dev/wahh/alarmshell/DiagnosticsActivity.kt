package dev.wahh.alarmshell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.wahh.alarmshell.core.ArmState
import dev.wahh.alarmshell.core.Arming
import dev.wahh.alarmshell.core.DefenceMode
import dev.wahh.alarmshell.core.HikDevice
import dev.wahh.alarmshell.core.HikSession
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Session, panel inventory and the three explicit commands. State is read from the cloud and the
 * official app's own notifications; nothing here assumes an arm succeeded.
 */
class DiagnosticsActivity : Activity() {
    private val services get() = application as AlarmApplication
    private var session: HikSession? = null
    private var devices: List<HikDevice> = emptyList()
    private var busy: String? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        loadSession()
        render()
    }

    override fun onResume() {
        super.onResume()
        loadSession()
        render()
    }

    private fun loadSession() {
        session =
            try {
                services.sessions.read()
            } catch (_: Exception) {
                null
            }
    }

    private fun render() {
        val body = column()
        body.addView(hudHeader("DIAGNOSTICS", "ASH-01"))
        busy?.let { body.addView(text("◇ $it", 13f)) }
        error?.let { body.addView(text("▸ $it", 13f)) }

        val link = panel(code = "LINK")
        link.addView(text("SESSION", 19f))
        val current = session
        if (current == null) {
            link.addView(text("NO SESSION // LOG IN TO THE CLOUD", 14f))
            link.addView(button("LOG IN", enabled = busy == null) { openLogin() })
        } else {
            link.addView(text("DOMAIN // ${current.apiDomain}", 14f))
            link.addView(text(expiryLine(current), 13f))
            link.addView(text(armStateLine(), 13f))
            link.addView(button("REFRESH SESSION", enabled = busy == null) { refreshSession(current) })
            link.addView(button("LIST DEVICES", enabled = busy == null) { loadDevices(current) })
            link.addView(button("RE-LOGIN") { openLogin() })
            link.addView(button("SIGN OUT", enabled = busy == null) { signOut() })
        }
        body.addView(link)

        if (current != null) {
            body.addView(commandPanel(current))
            body.addView(devicePanel())
        }

        body.addView(
            text(
                "Unofficial Hik-Connect API. State comes from the cloud and the official app's notifications; commands use the panel's own action channel.",
                12f,
            ),
        )
        body.addView(text("└── ASH-01 // END DIAGNOSTICS ─╱", 12f))
        terminalContent(ScrollView(this).apply { addView(body) })
    }

    private fun commandPanel(current: HikSession): LinearLayout {
        val command = panel(code = "CMD")
        command.addView(text("PANEL COMMAND", 19f))
        command.addView(text("Explicit modes only. Faults are confirmed automatically, like the app's own prompt.", 12f))
        val serial = services.armState.serial
        val ready = serial.isNotBlank() && services.armState.enUser.isNotBlank()
        command.addView(text("PANEL // ${serial.ifBlank { "NOT SET" }}", 12f))
        command.addView(button("STAY", enabled = busy == null && ready) { sendMode(current, serial, DefenceMode.STAY) })
        command.addView(button("AWAY", enabled = busy == null && ready) { sendMode(current, serial, DefenceMode.AWAY) })
        command.addView(button("DISARM", enabled = busy == null && ready) { sendMode(current, serial, DefenceMode.DISARM) })
        command.addView(button("READ ARM STATUS", enabled = busy == null) { readArmStatus(current, serial) })
        val serialField = SecureField(this, "Panel serial", "Q37069416", maxLength = 32).apply { edit.setText(serial) }
        command.addView(serialField)
        command.addView(
            button("SAVE SERIAL") {
                val value = serialField.edit.text.toString().trim().uppercase(Locale.ROOT)
                if (value.isEmpty()) {
                    error = "Panel serial cannot be empty."
                } else {
                    services.armState.serial = value
                    error = "Panel serial set to $value."
                }
                ArmWidget.updateAll(this)
                render()
            },
        )
        val enUser = services.armState.enUser
        command.addView(text("AUTHORIZATION USER // $enUser", 12f))
        val enField = SecureField(this, "Panel account", "panel account name", maxLength = 64).apply { edit.setText(enUser) }
        command.addView(enField)
        command.addView(
            button("SAVE ACCOUNT") {
                val value = enField.edit.text.toString().trim()
                if (value.isEmpty()) {
                    error = "Panel account cannot be empty."
                } else {
                    services.armState.enUser = value
                    error = "Panel account set to $value."
                }
                ArmWidget.updateAll(this)
                render()
            },
        )
        return command
    }

    private fun devicePanel(): LinearLayout {
        val inventory = panel(code = "INV")
        inventory.addView(text("DEVICES // ${devices.size}", 19f))
        if (devices.isEmpty()) {
            inventory.addView(text("Tap LIST DEVICES to read the account inventory.", 12f))
        }
        devices.forEach { device ->
            inventory.addView(text(deviceLine(device), 12f))
            inventory.addView(
                button("USE ${device.serial}", enabled = busy == null) {
                    services.armState.serial = device.serial
                    error = "Panel serial set to ${device.serial}."
                    ArmWidget.updateAll(this)
                    render()
                },
            )
        }
        return inventory
    }

    private fun expiryLine(session: HikSession): String {
        val remaining = Duration.between(Instant.now(), Instant.ofEpochSecond(session.expiresAtEpochSeconds))
        if (remaining.isNegative) return "SESSION EXPIRED // REFRESH OR LOG IN"
        val minutes = remaining.toMinutes()
        val refresh = if (session.needsRefresh(Instant.now().epochSecond)) "NOW" else "LATER"
        return "EXPIRES IN ${minutes / 60}H ${minutes % 60}M // REFRESH $refresh"
    }

    private fun armStateLine(): String {
        val state = services.armState.read()
        if (state.atMillis == 0L) return "ARM STATE // SIN DATOS"
        val time =
            Instant
                .ofEpochMilli(state.atMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        return "ARM STATE // ${state.raw?.uppercase() ?: state.arming.label} · $time · ${state.source}"
    }

    private fun deviceLine(device: HikDevice): String {
        val online =
            when (device.online) {
                true -> "ONLINE"
                false -> "OFFLINE"
                null -> "STATE UNKNOWN"
            }
        return listOfNotNull(device.name.uppercase(Locale.ROOT), device.serial, device.type, online).joinToString(" // ")
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun signOut() {
        session = null
        devices = emptyList()
        error = null
        busy = null
        try {
            services.sessions.clear()
        } catch (_: Exception) {
            error = "Session removal unconfirmed. Retry before leaving the device."
        }
        render()
    }

    private fun persist(updated: HikSession) {
        try {
            services.sessions.write(updated)
            session = updated
        } catch (_: Exception) {
            session = updated
            error = "Refreshed session is only in memory: secure storage failed."
        }
    }

    private fun refreshSession(current: HikSession) {
        if (busy != null) return
        busy = "Refreshing session…"
        error = null
        render()
        services.background({ services.client.refresh(current) }) { result ->
            if (isDestroyed) return@background
            busy = null
            result.onSuccess { persist(it) }.onFailure { error = describeHikFailure(it) }
            render()
        }
    }

    private fun loadDevices(current: HikSession) {
        if (busy != null) return
        busy = "Reading device list…"
        error = null
        render()
        services.background(
            {
                val fresh = services.client.ensureFresh(current)
                fresh to services.client.devices(fresh)
            },
        ) { result ->
            if (isDestroyed) return@background
            busy = null
            result
                .onSuccess { (fresh, listed) ->
                    if (fresh != current) persist(fresh) else session = fresh
                    devices = listed
                }.onFailure { error = describeHikFailure(it) }
            render()
        }
    }

    private fun sendMode(
        current: HikSession,
        serial: String,
        mode: DefenceMode,
    ) {
        if (busy != null) return
        busy = "Sending ${mode.name}…"
        error = null
        render()
        services.background({
            val fresh = services.client.ensureFresh(current)
            fresh to services.client.setDefenceMode(fresh, serial, mode, services.armState.enUser)
        }) { result ->
            if (isDestroyed) return@background
            busy = null
            result
                .onSuccess { (fresh, code) ->
                    if (fresh != current) persist(fresh)
                    services.armState.write(ArmState(Arming.UNKNOWN, System.currentTimeMillis(), "sent:$code"))
                    error = "Command accepted (code $code)."
                }.onFailure { error = describeHikFailure(it) }
            render()
        }
    }

    private fun readArmStatus(
        current: HikSession,
        serial: String,
    ) {
        if (busy != null) return
        busy = "Reading arm status…"
        error = null
        render()
        services.background({
            val fresh = services.client.ensureFresh(current)
            val state = services.client.readArmState(fresh, serial, services.armState.enUser)
            fresh to state
        }) { result ->
            if (isDestroyed) return@background
            busy = null
            result
                .onSuccess { (fresh, state) ->
                    if (fresh != current) persist(fresh)
                    services.armState.write(state)
                    error = "Cloud reports: ${state.raw ?: state.arming.label}"
                }.onFailure { error = describeHikFailure(it) }
            render()
        }
    }
}
