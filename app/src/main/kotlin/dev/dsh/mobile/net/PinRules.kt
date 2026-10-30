package dev.dsh.mobile.net

object PinRules {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 64

    /** Returns the trimmed PIN, or null when it cannot be a pocket PIN. */
    fun normalize(raw: String): String? {
        val pin = raw.trim()
        if (pin.length !in MIN_LENGTH..MAX_LENGTH) return null
        if (pin.any { it.isISOControl() }) return null
        return pin
    }

    fun validate(raw: String): String? = when {
        raw.isBlank() -> null // empty is allowed when the server does not require a PIN
        raw != raw.trim() -> "Remove the leading or trailing spaces."
        raw.length < MIN_LENGTH -> "PINs are at least $MIN_LENGTH characters."
        raw.length > MAX_LENGTH -> "PINs are at most $MAX_LENGTH characters."
        raw.any { it.isISOControl() } -> "The PIN contains unsupported characters."
        else -> null
    }
}
