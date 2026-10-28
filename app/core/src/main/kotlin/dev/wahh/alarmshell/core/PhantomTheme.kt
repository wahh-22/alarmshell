package dev.wahh.alarmshell.core

import java.util.Locale

/**
 * Read-only subset of the PhantomShell theme document that the app actually renders.
 * Unsupported launcher fields are ignored instead of rejected so a real export still loads.
 */
class PhantomTheme private constructor(
    private val fields: Map<String, Any?>,
) {
    fun color(slot: String): PhantomColor {
        require(slot in COLOR_SLOTS)
        val value = fields[slot] as? Map<*, *> ?: error("Missing color $slot")

        fun number(key: String) = (value[key] as? Number)?.toDouble() ?: error("$slot.$key must be a number")
        return PhantomColor(number("hue"), number("saturation"), number("lightness"), number("alpha"))
    }

    val windowShape: String get() = fields.getValue("windowShape") as String
    val buttonShape: String get() = fields.getValue("buttonShape") as String
    val isAllCaps: Boolean get() = fields.getValue("isAllCaps") as Boolean
    val fontSizeOffset: Int get() = (fields.getValue("fontSizeOffset") as Number).toDouble().coerceIn(-4.0, 6.0).toInt()

    // Field-specific support is deliberate: launcher enum membership alone proves no mapping.
    val renderWindowShape: String get() = windowShape.takeIf { it in listOf("TABBED", "BEVEL") } ?: "SQUARED"
    val renderButtonShape: String get() = buttonShape.takeIf { it == "BEVEL" } ?: "SQUARED"

    fun displayText(value: String): String = if (isAllCaps) value.uppercase(Locale.US) else value

    fun withColor(
        slot: String,
        color: PhantomColor,
    ): PhantomTheme {
        require(slot in COLOR_SLOTS)
        val old = fields[slot] as? Map<*, *> ?: emptyMap<String, Any?>()
        return PhantomTheme(fields + (slot to (old + color.fields())))
    }

    companion object {
        val COLOR_SLOTS = listOf("outline", "window", "text", "subtext", "subtext2", "accent", "notifications", "background", "vignette")

        fun parse(json: String): PhantomTheme =
            try {
                val fields = JsonParser.parse(json)
                val theme = PhantomTheme(fields)
                COLOR_SLOTS.forEach { theme.color(it) }
                listOf("windowShape", "buttonShape").forEach {
                    require(fields[it] is String) { "$it must be a string" }
                }
                require(fields["isAllCaps"] is Boolean) { "isAllCaps must be a boolean" }
                val offset = (fields["fontSizeOffset"] as? Number)?.toDouble()
                require(offset != null && offset.isFinite() && offset % 1 == 0.0) { "fontSizeOffset must be an integer" }
                theme
            } catch (error: RuntimeException) {
                throw IllegalArgumentException("Invalid PhantomShell theme: ${error.message ?: "malformed JSON"}")
            }

        fun default(): PhantomTheme = parse(PHANTOM_BLUE_JSON)
    }
}
