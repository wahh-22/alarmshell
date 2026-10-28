package dev.wahh.alarmshell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.wahh.alarmshell.core.PhantomTheme
import kotlin.math.min

/** Canvas primitives shared with the app shell. Logical units are dp; create after scaling. */
class PhantomGraphics(
    context: Context,
    private val canvas: Canvas,
    private val theme: PhantomTheme,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = HudFont.typeface(context, false)
    private val bold = HudFont.typeface(context, true)

    fun measure(
        value: String,
        size: Float,
        heavy: Boolean = false,
    ): Float {
        configure(size, heavy, Paint.Align.LEFT)
        return paint.measureText(value)
    }

    fun text(
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        heavy: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        maxWidth: Float = Float.MAX_VALUE,
    ) {
        configure(size, heavy, align)
        var cut = value
        while (cut.isNotEmpty() && paint.measureText(cut) > maxWidth) cut = cut.dropLast(1)
        if (cut != value && cut.isNotEmpty()) cut = cut.dropLast(1) + "…"
        paint.color = color
        canvas.drawText(cut, x, baseline, paint)
    }

    private fun configure(
        size: Float,
        heavy: Boolean,
        align: Paint.Align,
    ) {
        paint.style = Paint.Style.FILL
        paint.textSize = (size + theme.fontSizeOffset).coerceAtLeast(3f)
        paint.typeface = if (heavy) bold else regular
        paint.textAlign = align
    }

    fun fill(
        path: Path,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    fun stroke(
        path: Path,
        color: Int,
        width: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        canvas.drawPath(path, paint)
    }

    fun panel(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fill: Int,
        border: Int,
        shape: String = theme.renderWindowShape,
        strokeWidth: Float = 1f,
    ) {
        val path = panelPath(left, top, right, bottom, shape)
        this.fill(path, fill)
        stroke(path, border, strokeWidth)
    }

    fun button(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        selected: Boolean = false,
    ) {
        val path = panelPath(left, top, right, bottom, theme.renderButtonShape)
        fill(path, theme.color("window").argb)
        stroke(path, theme.color(if (selected) "accent" else "outline").argb, if (selected) 2f else 1f)
    }

    companion object {
        private var regularType: Typeface? = null
        private var boldType: Typeface? = null

        fun panelPath(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            shape: String,
        ): Path =
            Path().apply {
                when (shape) {
                    "TABBED" -> {
                        val tab = min(59f, (right - left) * 0.7f)
                        moveTo(left, top)
                        lineTo(right - tab, top)
                        lineTo(right - tab + 5, top + 5)
                        lineTo(right, top + 5)
                        lineTo(right, bottom)
                        lineTo(left + 7, bottom)
                        lineTo(left, bottom - 7)
                        close()
                    }

                    "BEVEL" -> {
                        val cut = min(7f, min((bottom - top) / 3, (right - left) / 3))
                        moveTo(left + cut, top)
                        lineTo(right - cut, top)
                        lineTo(right, top + cut)
                        lineTo(right, bottom - cut)
                        lineTo(right - cut, bottom)
                        lineTo(left + cut, bottom)
                        lineTo(left, bottom - cut)
                        lineTo(left, top + cut)
                        close()
                    }

                    else -> addRect(left, top, right, bottom, Path.Direction.CW)
                }
            }
    }
}
