package dev.dsh.mobile.net

import dev.dsh.mobile.web.PageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/**
 * The only code that talks to dsh-pocket's login endpoint. It never logs the
 * PIN and never follows a redirect: a 302 is the success signal that carries
 * the session cookie.
 */
class PocketClient(
    private val client: OkHttpClient = defaultClient(),
) {
    data class Probe(
        val reachable: Boolean,
        val needsPin: Boolean,
        val status: Int? = null,
        val error: String? = null,
    )

    data class Login(
        val ok: Boolean,
        val cookie: String? = null,
        val lockedSeconds: Long? = null,
        val rejected: Boolean = false,
        val error: String? = null,
    )

    enum class BridgeHealth { OK, UNAUTHORIZED, PIN_UNAVAILABLE, NOT_FOUND, UNREACHABLE, ERROR }

    suspend fun probe(baseUrl: String, cookie: String?): Probe = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .header("Accept", "text/html")
                .header("Cache-Control", "no-cache")
                .apply {
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                }
                .build()
            client.newCall(request).execute().use { response ->
                val body = readCapped(response.body, 64L * 1024)
                val needsPin = PageDetector.needsPin(body) ||
                    response.code == 401 ||
                    response.code == 403
                Probe(reachable = true, needsPin = needsPin, status = response.code)
            }
        } catch (error: Exception) {
            Probe(reachable = false, needsPin = false, error = describe(error))
        }
    }

    suspend fun login(baseUrl: String, pin: String): Login = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder().add("token", pin).build()
            val request = Request.Builder()
                .url("$baseUrl/pocket-login")
                .post(form)
                .header("Accept", "text/html")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                val cookie = CookieHeaders.find(response.headers.values("Set-Cookie"), "dsh_pocket_token")
                when (response.code) {
                    302, 303, 307, 308 -> {
                        if (cookie != null) Login(ok = true, cookie = cookie)
                        else Login(ok = false, error = "The server accepted the PIN but sent no session cookie.")
                    }
                    429 -> Login(
                        ok = false,
                        lockedSeconds = CookieHeaders.retryAfterSeconds(response.header("Retry-After")),
                        error = "Too many attempts.",
                    )
                    200, 401, 403 -> {
                        val body = readCapped(response.body, 16L * 1024)
                        if (PageDetector.needsPin(body) || response.code == 401 || response.code == 403) {
                            Login(ok = false, rejected = true, error = "That PIN was not accepted.")
                        } else {
                            Login(ok = false, error = "The server did not accept the PIN.")
                        }
                    }
                    else -> Login(ok = false, error = "Unexpected server response (${response.code}).")
                }
            }
        } catch (error: Exception) {
            Login(ok = false, error = describe(error))
        }
    }

    suspend fun bridgeHealth(bridgeBaseUrl: String, pin: String): Pair<BridgeHealth, String?> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$bridgeBaseUrl/health")
                    .get()
                    .header("Authorization", "Bearer $pin")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = readCapped(response.body, 8L * 1024)
                            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                            BridgeHealth.OK to version
                        }
                        401 -> BridgeHealth.UNAUTHORIZED to null
                        503 -> BridgeHealth.PIN_UNAVAILABLE to null
                        404 -> BridgeHealth.NOT_FOUND to null
                        else -> BridgeHealth.ERROR to "HTTP ${response.code}"
                    }
                }
            } catch (_: Exception) {
                BridgeHealth.UNREACHABLE to null
            }
        }

    private fun readCapped(body: ResponseBody?, limit: Long): String {
        if (body == null) return ""
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size < limit) {
            val read = source.read(buffer, limit - buffer.size)
            if (read == -1L) break
        }
        return buffer.readUtf8()
    }

    private fun describe(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "The connection timed out."
        is java.net.ConnectException -> "The server refused the connection."
        is java.net.UnknownHostException -> "The host could not be resolved."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "The connection failed."
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // dsh-pocket closes idle keep-alives after ~5s. Without a short pool a
            // reused socket can fail with "unexpected end of stream" before the
            // request is sent; retries are safe for these idempotent control calls.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }
}
