package dev.dsh.mobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dsh.mobile.DshMobileApp
import dev.dsh.mobile.data.ServerProfile
import dev.dsh.mobile.net.PocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps the alert stream alive while the app is in the
 * background. It stops when the task is removed from recents; that is the
 * user's explicit kill switch.
 */
class NotificationBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var currentCall: okhttp3.Call? = null
    private lateinit var graph: dev.dsh.mobile.core.AppGraph

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private enum class Outcome { RETRY_FAST, RETRY_SLOW, STOP }

    override fun onCreate() {
        super.onCreate()
        graph = (application as DshMobileApp).graph
        NotificationPublisher.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlerts(removeNotifications = true)
            return START_NOT_STICKY
        }
        startForegroundCompat("Connecting to DSH…")
        if (loop?.isActive != true) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAlerts(removeNotifications = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        currentCall?.cancel()
        BridgeRuntime.set(BridgePhase.DISABLED)
        // stopWithTask can stop the service without a separate onTaskRemoved
        // callback; a recents swipe must not leave stale alerts behind.
        NotificationPublisher.cancelAll(this)
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        ServiceCompat.startForeground(
            this,
            NotificationPublisher.ONGOING_ID,
            NotificationPublisher.ongoing(this, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateOngoing(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(NotificationPublisher.ONGOING_ID, NotificationPublisher.ongoing(this, text))
    }

    private suspend fun runLoop() {
        val file = graph.profileStore.ensureLoaded()
        val selected = file.selected()
        if (selected == null) {
            BridgeRuntime.set(BridgePhase.NO_PROFILE)
            updateOngoing("No server selected")
            stopSelf()
            return
        }
        val pin = graph.secretStore.getPin(selected.id)
        if (pin.isNullOrBlank()) {
            BridgeRuntime.set(BridgePhase.NO_PIN)
            updateOngoing("A saved PIN is needed for alerts")
            stopSelf()
            return
        }
        val base = selected.baseUrl.toHttpUrlOrNull()
        if (base == null) {
            BridgeRuntime.set(BridgePhase.ERROR, "Invalid saved address")
            stopSelf()
            return
        }
        val settings = graph.settingsStore.state.value
        val bridgeBase = "${base.scheme}://${base.host}:${settings.bridgePort}"

        var delayMs = 0L
        var attempt = 0
        while (scope.isActive) {
            if (delayMs > 0) delay(delayMs)
            if (!scope.isActive) break
            val outcome = connectOnce(selected, bridgeBase, pin)
            if (outcome == Outcome.STOP) break
            attempt = if (outcome == Outcome.RETRY_FAST) (attempt + 1).coerceAtMost(6) else 0
            delayMs = when (outcome) {
                Outcome.RETRY_SLOW -> 60_000L
                else -> minOf(60_000L, 2_000L * (1L shl attempt))
            }
        }
    }

    private suspend fun connectOnce(profile: ServerProfile, bridgeBase: String, pin: String): Outcome {
        BridgeRuntime.set(BridgePhase.CONNECTING, "Checking the alert bridge…")
        updateOngoing("Checking alert bridge…")
        val (health, version) = graph.pocketClient.bridgeHealth(bridgeBase, pin)
        when (health) {
            PocketClient.BridgeHealth.UNAUTHORIZED -> {
                BridgeRuntime.set(BridgePhase.UNAUTHORIZED, "The saved PIN was not accepted")
                updateOngoing("Alerts paused: PIN rejected")
                return Outcome.STOP
            }
            PocketClient.BridgeHealth.PIN_UNAVAILABLE -> {
                BridgeRuntime.set(BridgePhase.PIN_UNAVAILABLE, "The PC bridge has no PIN to check")
                updateOngoing("Alerts paused: bridge has no PIN")
                return Outcome.STOP
            }
            PocketClient.BridgeHealth.NOT_FOUND, PocketClient.BridgeHealth.UNREACHABLE -> {
                BridgeRuntime.set(
                    BridgePhase.BRIDGE_MISSING,
                    "Install the DSH Mobile Bridge on the PC to receive alerts.",
                )
                updateOngoing("Alerts paused: bridge not found")
                return Outcome.RETRY_SLOW
            }
            PocketClient.BridgeHealth.ERROR -> {
                BridgeRuntime.set(BridgePhase.RECONNECTING, "The bridge returned an error")
                return Outcome.RETRY_FAST
            }
            PocketClient.BridgeHealth.OK -> Unit
        }

        val request = Request.Builder()
            .url("$bridgeBase/events")
            .get()
            .header("Authorization", "Bearer $pin")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val call = sseClient.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                if (response.code == 401) {
                    BridgeRuntime.set(BridgePhase.UNAUTHORIZED, "The saved PIN was not accepted")
                    updateOngoing("Alerts paused: PIN rejected")
                    return Outcome.STOP
                }
                if (!response.isSuccessful) {
                    BridgeRuntime.set(BridgePhase.BRIDGE_MISSING, "Bridge HTTP ${response.code}")
                    updateOngoing("Alerts paused: bridge error")
                    return Outcome.RETRY_SLOW
                }
                val reader = response.body?.charStream()?.buffered() ?: return Outcome.RETRY_FAST
                BridgeRuntime.set(BridgePhase.LIVE, version?.let { "Bridge $it" } ?: "Alerts on")
                updateOngoing("Alerts on · ${profile.name}")
                val parser = SseParser()
                while (scope.isActive) {
                    val line = reader.readLine() ?: break
                    parser.line(line)?.let { handleEvent(it) }
                }
                BridgeRuntime.set(BridgePhase.RECONNECTING, "Reconnecting…")
                updateOngoing("Reconnecting…")
                return Outcome.RETRY_FAST
            }
        } catch (_: IOException) {
            if (!scope.isActive) return Outcome.STOP
            BridgeRuntime.set(BridgePhase.RECONNECTING, "Reconnecting…")
            updateOngoing("Reconnecting…")
            return Outcome.RETRY_FAST
        } finally {
            currentCall = null
        }
    }

    private fun handleEvent(sse: SseEvent) {
        val event = BridgeJson.decode(sse.data) ?: return
        val settings = graph.settingsStore.state.value
        when (event.type) {
            "snapshot" -> event.pending.forEach { NotificationPublisher.post(this, it, settings) }
            "clear" -> NotificationPublisher.cancel(this, event)
            "approval", "question", "turn-done", "error" -> NotificationPublisher.post(this, event, settings)
            else -> Unit
        }
    }

    private fun stopAlerts(removeNotifications: Boolean) {
        scope.cancel()
        currentCall?.cancel()
        graph.settingsStore.update { it.copy(alertsEnabled = false) }
        BridgeRuntime.set(BridgePhase.DISABLED)
        if (removeNotifications) NotificationPublisher.cancelAll(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "dev.dsh.mobile.action.START_ALERTS"
        const val ACTION_STOP = "dev.dsh.mobile.action.STOP_ALERTS"

        fun start(context: Context): Boolean = try {
            val intent = Intent(context, NotificationBridgeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (error: Exception) {
            BridgeRuntime.set(BridgePhase.ERROR, error.message ?: "Could not start background alerts")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationBridgeService::class.java))
            NotificationPublisher.cancelAll(context)
            BridgeRuntime.set(BridgePhase.DISABLED)
        }
    }
}
