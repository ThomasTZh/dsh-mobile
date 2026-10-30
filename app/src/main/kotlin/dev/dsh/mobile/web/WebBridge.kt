package dev.dsh.mobile.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Methods callable from the trusted DSH page. Everything is validated here:
 * URLs must be http(s), and nothing can reach files or intents.
 */
class WebBridge(
    private val context: Context,
    private val onSocketState: (String) -> Unit,
    private val onLoginRequired: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun copyText(text: String) {
        val value = text.take(200_000)
        main.post {
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("DSH", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun readClipboard(): String = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        clip.getItemAt(0).coerceToText(context)?.toString()?.take(200_000) ?: ""
    }.getOrDefault("")

    @JavascriptInterface
    fun shareText(text: String, url: String) {
        val body = buildString {
            if (text.isNotBlank()) append(text)
            if (url.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(url)
            }
        }
        if (body.isBlank()) return
        main.post {
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(parsed.toString())))
            return
        }
        if (url.startsWith("mailto:") || url.startsWith("tel:")) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    @JavascriptInterface
    fun socketState(state: String) {
        main.post { onSocketState(state.take(16)) }
    }

    @JavascriptInterface
    fun loginRequired() {
        main.post { onLoginRequired() }
    }

    private fun open(intent: Intent) {
        main.post {
            runCatching {
                context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
