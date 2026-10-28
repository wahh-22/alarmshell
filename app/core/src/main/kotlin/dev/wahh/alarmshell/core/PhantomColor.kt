package dev.wahh.alarmshell.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** PhantomShell uses HSL degrees/fractions and a separate alpha fraction. */
data class PhantomColor(
    val hue: Double,
    val saturation: Double,
    val lightness: Double,
    val alpha: Double,
) {
    init {
        require(hue.isFinite() && hue in 0.0..360.0) { "hue must be 0–360" }
        require(saturation.isFinite() && saturation in 0.0..1.0) { "saturation must be 0–1" }
        require(lightness.isFinite() && lightness in 0.0..1.0) { "lightness must be 0–1" }
        require(alpha.isFinite() && alpha in 0.0..1.0) { "alpha must be 0–1" }
    }

    val argb: Int
        get() {
            val chroma = (1 - abs(2 * lightness - 1)) * saturation
            val h = (hue % 360) / 60
            val x = chroma * (1 - abs(h % 2 - 1))
            val (r, g, b) =
                when (h.toInt()) {
                    0 -> Triple(chroma, x, 0.0)
                    1 -> Triple(x, chroma, 0.0)
                    2 -> Triple(0.0, chroma, x)
                    3 -> Triple(0.0, x, chroma)
                    4 -> Triple(x, 0.0, chroma)
                    else -> Triple(chroma, 0.0, x)
                }
            val m = lightness - chroma / 2

            fun channel(value: Double) = ((value + m) * 255).roundToInt().coerceIn(0, 255)
            return ((alpha * 255).roundToInt() shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }

    internal fun fields(): Map<String, Double> =
        linkedMapOf("hue" to hue, "saturation" to saturation, "lightness" to lightness, "alpha" to alpha)

    companion object {
        fun fromHex(
            hex: String,
            alphaPercent: Int,
        ): PhantomColor {
            require(hex.matches(Regex("[0-9a-fA-F]{6}"))) { "Use six hex digits" }
            require(alphaPercent in 0..100) { "Alpha must be 0–100" }
            val rgb = hex.toInt(16)
            val r = ((rgb shr 16) and 255) / 255.0
            val g = ((rgb shr 8) and 255) / 255.0
            val b = (rgb and 255) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val light = (max + min) / 2
            val hue =
                when {
                    delta == 0.0 -> 0.0
                    max == r -> ((g - b) / delta % 6) * 60
                    max == g -> ((b - r) / delta + 2) * 60
                    else -> ((r - g) / delta + 4) * 60
                }
            return PhantomColor(
                (hue + 360) % 360,
                // RGB-derived saturation can exceed 1 by floating-point epsilon; imported HSL is still strictly rejected.
                if (delta == 0.0) 0.0 else (delta / (1 - abs(2 * light - 1))).coerceIn(0.0, 1.0),
                light,
                alphaPercent / 100.0,
            )
        }
    }
}
