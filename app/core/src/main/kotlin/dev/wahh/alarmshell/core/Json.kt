package dev.wahh.alarmshell.core

/** Small strict JSON reader: bounded size/depth, no dependency or platform parser divergence. */
internal class JsonParser private constructor(
    private val text: String,
) {
    private var position = 0

    private fun space() {
        while (position < text.length && text[position] in " \t\r\n") position++
    }

    private fun take(): Char {
        require(position < text.length)
        return text[position++]
    }

    private fun expect(char: Char) {
        require(take() == char)
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val char = take()
            if (char == '"') return result.toString()
            require(char >= ' ')
            if (char != '\\') {
                result.append(char)
            } else {
                result.append(
                    when (val escaped = take()) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(position + 4 <= text.length)
                            text.substring(position, position + 4).toInt(16).toChar().also { position += 4 }
                        }

                        else -> error("Invalid JSON")
                    },
                )
            }
        }
    }

    private fun value(depth: Int): Any? {
        require(depth <= 32)
        space()
        return when (text.getOrNull(position)) {
            '{' -> {
                position++
                val result = linkedMapOf<String, Any?>()
                space()
                if (text.getOrNull(position) == '}') {
                    position++
                    result
                } else {
                    while (true) {
                        space()
                        val key = string()
                        space()
                        expect(':')
                        require(!result.containsKey(key))
                        result[key] = value(depth + 1)
                        space()
                        val delimiter = take()
                        if (delimiter == '}') break
                        require(delimiter == ',')
                    }
                    result
                }
            }

            '[' -> {
                position++
                val result = mutableListOf<Any?>()
                space()
                if (text.getOrNull(position) == ']') {
                    position++
                    result
                } else {
                    while (true) {
                        result += value(depth + 1)
                        space()
                        val delimiter = take()
                        if (delimiter == ']') break
                        require(delimiter == ',')
                    }
                    result
                }
            }

            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                val start = position
                while (text.getOrNull(position)?.let { it in "-+0123456789.eE" } == true) position++
                val raw = text.substring(start, position)
                require(raw.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                raw.toDouble().also { require(it.isFinite()) }
            }
        }
    }

    private fun literal(
        raw: String,
        result: Any?,
    ): Any? {
        require(text.startsWith(raw, position))
        position += raw.length
        return result
    }

    companion object {
        const val MAX_BYTES = 262144

        fun parseArray(text: String): List<Any?> {
            require(text.length <= MAX_BYTES)
            val parser = JsonParser(text)
            val value = parser.value(0)
            parser.space()
            require(parser.position == text.length && value is List<*>)
            return value
        }

        fun parse(text: String): Map<String, Any?> {
            require(text.length <= MAX_BYTES)
            val parser = JsonParser(text)
            val value = parser.value(0)
            parser.space()
            require(parser.position == text.length && value is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
    }
}

internal fun jsonEncode(value: Any?): String =
    when (value) {
        null -> "null"
        is String ->
            buildString {
                append('"')
                value.forEach { c ->
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }

        is Boolean -> value.toString()
        is Number -> {
            val decimal = java.math.BigDecimal(value.toString()).stripTrailingZeros()
            val ordinary = decimal.toString().replace("E+", "e").replace("E", "e")
            val scientific = decimal.unscaledValue().toString() + "e" + -decimal.scale()
            if (scientific.length < ordinary.length) scientific else ordinary
        }

        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { jsonEncode(it.key) + ":" + jsonEncode(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { jsonEncode(it) }
        else -> error("Unsupported JSON value")
    }
