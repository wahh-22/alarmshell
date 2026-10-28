package dev.wahh.alarmshell

import android.app.Activity
import android.view.View
import android.widget.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Activity.column() =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

internal data class TextStyle(
    val size: Float,
    val slot: String,
)

internal data class PanelStyle(
    val code: String,
)

internal fun Activity.text(
    value: String,
    size: Float = 16f,
    color: Int = 0,
) = TextView(this).apply {
    text = value
    textSize = size
    typeface = HudFont.typeface(this@text, size >= 18f)
    tag =
        TextStyle(
            size,
            if (size >= 18f || color != 0) {
                "accent"
            } else if (size <= 13f) {
                "subtext"
            } else {
                "text"
            },
        )
    setPadding(0, dp(3), 0, dp(3))
}

internal fun Activity.button(
    label: String,
    enabled: Boolean = true,
    action: () -> Unit,
) = Button(this).apply {
    text = getString(R.string.hud_control, label.uppercase(java.util.Locale.ROOT))
    typeface = HudFont.typeface(this@button, true)
    isAllCaps = false
    isEnabled = enabled
    minHeight = dp(48)
    minimumHeight = dp(48)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    layoutParams =
        android.widget.LinearLayout
            .LayoutParams(-1, -2)
            .apply { bottomMargin = dp(4) }
    setOnClickListener { if (isEnabled) action() }
}

@Suppress("UNUSED_PARAMETER") // All appearance comes from the fixed AlarmShell PhantomTheme.
internal fun Activity.panel(
    accent: Int = 0,
    background: Int = 0,
    code: String = "SYS-00",
) = column().apply {
    tag = PanelStyle(code)
    setPadding(dp(12), dp(28), dp(12), dp(28))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
}

/** Terminal frame plus the status gutter, matching the QuotaShell shell the user already runs. */
internal fun Activity.terminalContent(view: View) {
    if (view is ScrollView) {
        view.setPadding(dp(16), dp(16), dp(16), dp(16))
        view.clipToPadding = false
        view.getChildAt(0)?.let { child ->
            if (child.tag !is PanelStyle) child.setPadding(0, 0, 0, 0)
        }
    }
    val frame =
        FrameLayout(this).apply {
            addView(view)
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
        }
    setContentView(frame)
    installPhantomShell(frame)
    frame.requestApplyInsets()
}

/** HUD header: title, local clock and a node tag. No device or account data is shown here. */
internal fun Activity.hudHeader(
    screen: String,
    subtitle: String,
): LinearLayout {
    val header = panel(code = "ASH // $screen")
    header.addView(text("ALARM/SHELL", 24f).apply { tag = TextStyle(24f, "notifications") })
    val clock = text("", 13f).apply { tag = TextStyle(13f, "subtext2") }
    clock.contentDescription = "Local clock"
    val tick =
        object : Runnable {
            override fun run() {
                clock.text =
                    getString(
                        R.string.hud_clock,
                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                        subtitle,
                    )
                header.postDelayed(this, 1000)
            }
        }
    header.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                tick.run()
            }

            override fun onViewDetachedFromWindow(view: View) {
                header.removeCallbacks(tick)
            }
        },
    )
    header.addView(clock)
    return header
}
