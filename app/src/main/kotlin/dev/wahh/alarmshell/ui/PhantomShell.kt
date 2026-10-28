package dev.wahh.alarmshell

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import dev.wahh.alarmshell.core.PhantomTheme
import java.util.WeakHashMap

/**
 * Restyle existing native controls in place so the app reads like the PhantomShell launcher.
 * Phase 1 keeps the frame, surface chamfers and typography; the periodic glitch layer is deferred
 * until there is a panel state worth animating.
 */
internal fun Activity.installPhantomShell(root: ViewGroup) {
    val theme = (application as AlarmApplication).theme
    val sizes = WeakHashMap<TextView, Float>()

    fun apply(view: View) {
        (view.tag as? PanelStyle)?.let { view.background = PhantomSurface(this, theme, it.code) }
        if (view is TextView) {
            val style = view.tag as? TextStyle
            val base = sizes.getOrPut(view) { style?.size ?: (view.textSize / resources.displayMetrics.scaledDensity) }
            view.textSize = (base + theme.fontSizeOffset).coerceAtLeast(12f)
            view.typeface = HudFont.typeface(this, view is Button || base >= 18f)
            view.setTextColor(theme.color(style?.slot ?: "text").argb)
            if (view !is EditText) view.setAllCaps(theme.isAllCaps)
            if (view is Button && view !is CompoundButton) {
                view.background = PhantomSurface(this, theme, button = true)
                view.backgroundTintList = null
                view.setTextColor(theme.color(style?.slot ?: "accent").argb)
                view.alpha = if (view.isEnabled) 1f else 0.45f
            }
            if (view is EditText) {
                view.minHeight = dp(48)
                view.setHintTextColor(theme.color("subtext").argb)
                view.background = PhantomSurface(this, theme, button = true, field = true)
                view.backgroundTintList = null
                view.setPadding(dp(12), dp(8), dp(12), dp(8))
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    view.textCursorDrawable =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(theme.color("notifications").argb)
                            setSize(dp(9), dp(2))
                        }
                }
            }
            if (view is CompoundButton) {
                view.minHeight = dp(48)
                view.minimumHeight = dp(48)
                view.buttonTintList = ColorStateList.valueOf(theme.color("accent").argb)
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) apply(view.getChildAt(index))
        view.invalidate()
    }

    root.setBackgroundColor(theme.color("background").argb)
    @Suppress("DEPRECATION")
    window.statusBarColor = theme.color("background").argb
    @Suppress("DEPRECATION")
    window.navigationBarColor = theme.color("background").argb
    apply(root)
}

/** App-only terminal frames; geometry in dp, not density-dependent pixel chamfers. */
internal class PhantomSurface(
    private val activity: Activity,
    private val theme: PhantomTheme,
    private val code: String = "",
    private val button: Boolean = false,
    private val field: Boolean = false,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val density = activity.resources.displayMetrics.density
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)
        val width = bounds.width() / density
        val height = bounds.height() / density
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = theme.color("window").argb
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = theme.color("outline").argb
        val path =
            android.graphics.Path().apply {
                if (button) {
                    moveTo(9f, 5f)
                    lineTo(2f, 5f)
                    lineTo(2f, height - 5f)
                    lineTo(9f, height - 5f)
                    moveTo(width - 9f, 5f)
                    lineTo(width - 2f, 5f)
                    lineTo(width - 2f, height - 5f)
                    lineTo(width - 9f, height - 5f)
                } else {
                    moveTo(2f, 19f)
                    lineTo(14f, 7f)
                    lineTo(width - 2f, 7f)
                    lineTo(width - 2f, height - 19f)
                    moveTo(2f, 24f)
                    lineTo(2f, height - 7f)
                    lineTo(width - 14f, height - 7f)
                    lineTo(width - 2f, height - 19f)
                }
            }
        canvas.drawPath(path, paint)
        if (field) {
            paint.color = theme.color("subtext2").argb
            canvas.drawLine(12f, height - 6f, width - 12f, height - 6f, paint)
        }
        if (!button) {
            paint.style = android.graphics.Paint.Style.FILL
            paint.typeface = HudFont.typeface(activity)
            paint.textSize = 11f
            val label = "▮ $code //"
            val labelWidth = paint.measureText(label)
            paint.color = theme.color("window").argb
            canvas.drawRect(17f, 0f, 25f + labelWidth, 23f, paint)
            paint.color = theme.color("notifications").argb
            canvas.drawText(label, 21f, 12f, paint)
            paint.color = theme.color("subtext2").argb
            canvas.drawText("◢◤◢◤", width - 48f, height - 12f, paint)
            paint.color = theme.color("subtext").argb
            canvas.drawText("// $code ─╱", 12f, height - 12f, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
