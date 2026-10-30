package dev.dsh.mobile.web

/** Small HTML markers served by dsh-pocket v2.12.0. */
object PageDetector {
    const val LOGIN_MARKER = "action=\"/pocket-login\""
    private val TRANSITION_MARKERS = arrayOf("正在进入", "opening…", "opening...")

    fun needsPin(html: String): Boolean = html.contains(LOGIN_MARKER)

    fun isTransition(html: String): Boolean =
        TRANSITION_MARKERS.any { html.contains(it) } || html.contains("DSH Pocket · 正在进入")

    fun looksLikeDsh(html: String): Boolean =
        html.contains("DeepSeek Harness") || html.contains("dsh-pocket") || needsPin(html)
}
