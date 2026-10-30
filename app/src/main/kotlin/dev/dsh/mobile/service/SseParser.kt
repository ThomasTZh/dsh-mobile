package dev.dsh.mobile.service

data class SseEvent(val event: String, val data: String)

/**
 * Minimal incremental text/event-stream parser. Feed one line at a time; a
 * blank line dispatches the accumulated event. Comments (`:hb`) are ignored.
 */
class SseParser {
    private val data = StringBuilder()
    private var event: String? = null
    private var hasData = false

    fun line(rawLine: String?): SseEvent? {
        if (rawLine == null) return null
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            if (!hasData) {
                event = null
                return null
            }
            val payload = data.toString()
            val name = event ?: "message"
            data.setLength(0)
            hasData = false
            event = null
            return SseEvent(name, payload)
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit
        }
        return null
    }
}
