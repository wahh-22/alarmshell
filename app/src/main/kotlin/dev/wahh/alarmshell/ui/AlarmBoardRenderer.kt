package dev.wahh.alarmshell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.wahh.alarmshell.core.ArmState
import dev.wahh.alarmshell.core.Arming
import dev.wahh.alarmshell.core.PhantomTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Compact PhantomShell board. The three commands share one bottom band split into equal cells
 * (QuotaShell's `[ Sync ]` look: bracketed text, no frames), so nothing is left blank.
 */
object AlarmBoardRenderer {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    const val PAD = 5f
    const val INNER = 6f
    const val BAND_MARGIN_DP = 5f

    private val commands =
        listOf(
            "STAY" to Arming.STAY,
            "AWAY" to Arming.AWAY,
            "DISARM" to Arming.DISARMED,
        )

    fun render(
        context: Context,
        theme: PhantomTheme,
        state: ArmState,
        serial: String,
        widthDp: Float,
        heightDp: Float,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt().coerceAtLeast(1)
        val height = (heightDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        val g = PhantomGraphics(context, canvas, theme)
        val w = widthDp
        val h = heightDp

        canvas.drawColor(theme.color("background").argb)
        g.panel(PAD, PAD, w - PAD, h - PAD, theme.color("window").argb, theme.color("outline").argb)

        val left = PAD + INNER + 2f
        val right = w - PAD - INNER
        // The command band scales with the cell height so tall widgets do not leave a gap.
        val bandHeight = (h * 0.28f).coerceIn(26f, 40f)
        val bandTop = h - PAD - BAND_MARGIN_DP - bandHeight
        val freeTop = PAD + 15f
        val freeBottom = bandTop - 3f

        g.text("▮ ASH // AXP //", left, PAD + 12f, 7.5f, theme.color("notifications").argb, true)

        val headline = headlineFor(state)
        val free = (freeBottom - freeTop).coerceAtLeast(20f)
        val stateSize = (free * 0.36f).coerceIn(13f, 23f)
        val subSize = (free * 0.16f).coerceIn(7.5f, 11f)
        val stateColor =
            theme.color(if (state.arming == Arming.UNKNOWN && state.raw.isNullOrEmpty()) "subtext" else "text").argb
        val blockHeight = stateSize + 3f + subSize + 2f + subSize
        val blockTop = freeTop + ((free - blockHeight) / 2f).coerceAtLeast(0f)
        val stateBaseline = blockTop + stateSize
        g.text(headline, left, stateBaseline, stateSize, stateColor, true, maxWidth = right - left)

        val whenText =
            if (state.atMillis == 0L) {
                "TOCA PARA ABRIR"
            } else {
                Instant.ofEpochMilli(state.atMillis).atZone(ZoneId.systemDefault()).format(clock) +
                    " · " + state.source.uppercase()
            }
        g.text(whenText, left, stateBaseline + subSize + 3f, subSize, theme.color("subtext").argb, maxWidth = right - left)
        // Serial right-aligned on the same line: uses the full width instead of a third short line.
        val panelLabel = "PANEL //"
        val serialSize = (subSize - 0.5f).coerceAtLeast(7f)
        g.text(panelLabel, left, stateBaseline + subSize * 2f + 5f, serialSize, theme.color("subtext2").argb)
        g.text(serial, right, stateBaseline + subSize * 2f + 5f, serialSize, theme.color("subtext2").argb, align = android.graphics.Paint.Align.RIGHT)

        // Band frame and equal dividers: the whole strip is a control surface, no dead space.
        val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            color = theme.color("outline").argb
        }
        canvas.drawLine(left, bandTop, right, bandTop, linePaint)
        val cell = (right - left) / commands.size
        val labelSize = (bandHeight * 0.44f).coerceIn(11f, 15f)
        commands.forEachIndexed { index, (label, arming) ->
            val cellLeft = left + index * cell
            if (index > 0) canvas.drawLine(cellLeft, bandTop + 4f, cellLeft, bandTop + bandHeight - 4f, linePaint)
            val active =
                (state.source == "notification" && state.arming == arming) ||
                    (state.source == "cloud" && state.raw == arming.name.lowercase())
            val text = if (active) "▸ [ $label ]" else "[ $label ]"
            val textWidth = g.measure(text, labelSize, active)
            g.text(
                text,
                cellLeft + (cell - textWidth) / 2f,
                bandTop + bandHeight / 2f + labelSize * 0.36f,
                labelSize,
                theme.color(if (active) "notifications" else "accent").argb,
                active,
            )
        }
        return bitmap
    }

    private fun headlineFor(state: ArmState): String =
        when {
            state.source == "cloud" && !state.raw.isNullOrEmpty() -> state.raw!!.uppercase()
            state.source == "notification" -> state.arming.label
            state.source == "no-session" -> "SIN SESIÓN"
            state.source.startsWith("error") -> "NO ENVIADO"
            else -> "SIN CONFIRMAR"
        }
}
