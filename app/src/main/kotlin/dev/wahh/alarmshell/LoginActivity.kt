package dev.wahh.alarmshell

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import dev.wahh.alarmshell.core.HikException
import dev.wahh.alarmshell.core.HikFailure

/**
 * Credential entry for the unofficial cloud login. Screenshots are blocked and the password is
 * handed over as a wipeable CharArray: only the session that comes back is persisted.
 */
class LoginActivity : Activity() {
    private val services get() = application as AlarmApplication
    private lateinit var accountField: SecureField
    private lateinit var passwordField: SecureField
    private lateinit var captchaField: SecureField
    private lateinit var captchaImage: ImageView
    private lateinit var captchaPanel: android.widget.LinearLayout
    private lateinit var status: TextView
    private lateinit var connect: android.widget.Button
    private var busy = false
    private var challenge = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }

    override fun onPause() {
        passwordField.hideSecret()
        super.onPause()
    }

    private fun render() {
        val body = column()
        body.addView(hudHeader("LINK", "NODE ASH-01"))
        body.addView(text("◢◤◢◤ // HIK-CONNECT CLOUD // UNOFFICIAL // READ ONLY", 12f))
        body.addView(
            text(
                "Phase 1 proves status access only. This app can list devices and read the AX PRO panel; it cannot arm, disarm, bypass or configure anything.",
                13f,
            ),
        )

        accountField = SecureField(this, "Account", "Email or phone used in Hik-Connect", maxLength = 320)
        passwordField =
            SecureField(
                this,
                "Password",
                "Hik-Connect password",
                secret = true,
                maxLength = 128,
                helper = "Sent once to obtain a session. The password and its digest are never stored.",
            )
        body.addView(accountField)
        body.addView(passwordField)

        captchaImage = ImageView(this).apply { adjustViewBounds = true; contentDescription = "Captcha image" }
        captchaField = SecureField(this, "Captcha", "Characters in the image", maxLength = 8)
        captchaPanel =
            column().apply {
                setPadding(0, 0, 0, 0)
                visibility = View.GONE
                addView(text("Cloud challenge", 13f))
                addView(captchaImage)
                addView(button("RELOAD CAPTCHA") { loadCaptcha() })
                addView(captchaField)
            }
        body.addView(captchaPanel)

        status = text("No session. ", 13f).apply { tag = TextStyle(13f, "subtext2") }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        connect = button("CONNECT") { submit() }
        body.addView(connect)
        body.addView(status)
        if (services.sessions.read() != null) {
            body.addView(button("BACK TO DIAGNOSTICS") { finish() })
        }
        body.addView(text("└── ASH-01 // END LINK ─╱", 12f))
        val scroll = ScrollView(this).apply { addView(body) }
        terminalContent(scroll)
    }

    private fun setBusy() {
        connect.isEnabled = !busy
        connect.text = getString(R.string.hud_control, if (busy) "LINKING" else "CONNECT")
        status.text = if (busy) "Contacting Hik-Connect…" else status.text
    }

    private fun submit() {
        if (busy) return
        val account = accountField.edit.text.toString().trim()
        if (account.isEmpty()) {
            accountField.showMessage("Enter the Hik-Connect account.")
            return
        }
        if (passwordField.edit.text.isEmpty()) {
            passwordField.showMessage("Enter the Hik-Connect password.")
            return
        }
        val captcha = if (challenge) captchaField.edit.text.toString().trim().ifEmpty { null } else null
        val password = passwordField.takeChars()
        busy = true
        setBusy()
        services.background({ services.client.login(account, password, captcha) }) { result ->
            password.fill('\u0000')
            if (isDestroyed) return@background
            busy = false
            setBusy()
            result
                .onSuccess { session ->
                    try {
                        services.sessions.write(session)
                        startActivity(Intent(this, DiagnosticsActivity::class.java))
                        finish()
                    } catch (_: Exception) {
                        status.text = "Session obtained but secure storage failed. Retry the login."
                    }
                }.onFailure { error ->
                    passwordField.clear()
                    if ((error as? HikException)?.failure == HikFailure.CAPTCHA_REQUIRED) {
                        challenge = true
                        captchaPanel.visibility = View.VISIBLE
                        status.text = describeHikFailure(error)
                        loadCaptcha(account)
                    } else {
                        status.text = describeHikFailure(error)
                    }
                }
        }
    }

    private fun loadCaptcha(account: String = accountField.edit.text.toString().trim()) {
        if (account.isEmpty()) return
        status.text = "Fetching captcha…"
        services.background({ services.client.captchaImage(account) }) { result ->
            if (isDestroyed) return@background
            result
                .onSuccess { png ->
                    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                    if (bitmap == null) {
                        status.text = "Captcha image could not be decoded. Log in once in the official app and retry."
                    } else {
                        captchaImage.setImageBitmap(bitmap)
                        status.text = "Enter the characters shown above."
                    }
                }.onFailure { error -> status.text = describeHikFailure(error) }
        }
    }
}
