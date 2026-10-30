package dev.dsh.mobile.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A pocket QR encodes the LAN base URL; some share links also carry `?token=`.
 * The token is returned as the PIN candidate and is never put back into a URL.
 */
object QrPayload {
    data class Parsed(val baseUrl: String?, val pin: String?, val error: String? = null) {
        val ok: Boolean get() = baseUrl != null
    }

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(null, null, "The QR code is empty.")
        if (text.length > 2048) return Parsed(null, null, "The QR code is too large.")
        if (text.any { it.code < 0x20 }) return Parsed(null, null, "The QR code contains invalid characters.")

        val candidate = if (text.contains("://")) text else "http://$text"
        val url = candidate.toHttpUrlOrNull()
            ?: return Parsed(null, null, "The QR code does not contain a valid address.")
        if (url.scheme != "http" && url.scheme != "https") {
            return Parsed(null, null, "The QR code is not an http(s) address.")
        }

        val token = url.queryParameter("token")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none { ch -> ch.isISOControl() } }
        val hostPart = if (url.host.contains(':')) "[${url.host}]" else url.host
        val origin = "${url.scheme}://$hostPart:${url.port}"
        val normalized = LocalNetworkPolicy.normalize(origin)
        if (!normalized.ok) return Parsed(null, null, normalized.error)
        return Parsed(normalized.baseUrl, token)
    }
}
