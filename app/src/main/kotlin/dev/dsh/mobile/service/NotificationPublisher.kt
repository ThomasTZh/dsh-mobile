package dev.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import dev.dsh.mobile.MainActivity
import dev.dsh.mobile.R
import dev.dsh.mobile.data.AppSettings

object NotificationPublisher {
    const val CHANNEL_ATTENTION = "dsh_attention"
    const val CHANNEL_UPDATES = "dsh_updates"
    const val CHANNEL_SERVICE = "dsh_service"
    const val ONGOING_ID = 1001
    private const val TEST_ID = 2999

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val attention = NotificationChannel(
            CHANNEL_ATTENTION,
            "Needs you",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Approvals and questions that block a DSH session"
            enableVibration(true)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Session updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Finished turns and errors from DSH sessions"
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Background alerts",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps DSH Mobile connected while the app is in the background"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(attention, updates, service))
    }

    fun post(context: Context, event: BridgeEvent, settings: AppSettings) {
        val type = event.type
        if (type == "turn-done" && !settings.alertDone) return
        if (type == "error" && !settings.alertError) return
        if (type == "approval" && !settings.alertApproval) return
        if (type == "question" && !settings.alertQuestion) return

        val label = event.label?.takeIf { it.isNotBlank() } ?: "DSH session"
        val attention = type == "approval" || type == "question"
        val channel = if (attention) CHANNEL_ATTENTION else CHANNEL_UPDATES
        val title: String
        val body: String
        when (type) {
            "approval" -> {
                title = if (settings.hideDetails) "DSH needs approval" else "Approval needed · $label"
                body = if (settings.hideDetails) {
                    "Open DSH Mobile to review the request."
                } else {
                    listOfNotNull(event.tool?.takeIf { it.isNotBlank() }, event.reason?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .ifBlank { "Open DSH Mobile to review the request." }
                }
            }
            "question" -> {
                title = if (settings.hideDetails) "DSH has a question" else "Question · $label"
                body = if (settings.hideDetails) {
                    "Open DSH Mobile to answer."
                } else {
                    buildString {
                        event.header?.takeIf { it.isNotBlank() }?.let { append(it).append(" — ") }
                        append(event.question?.takeIf { it.isNotBlank() } ?: "Open DSH Mobile to answer.")
                        if (event.options.isNotEmpty()) {
                            append("\n")
                            append(event.options.joinToString(" · "))
                        }
                    }
                }
            }
            "error" -> {
                title = if (settings.hideDetails) "DSH session failed" else "$label failed"
                body = if (settings.hideDetails) "Open DSH Mobile for details." else (event.error ?: "The turn failed.")
            }
            else -> {
                title = if (settings.hideDetails) "DSH session finished" else "$label finished"
                body = if (settings.hideDetails) {
                    "Tap to open DSH Mobile."
                } else {
                    buildString {
                        event.reply?.takeIf { it.isNotBlank() }?.let { append(it) }
                        event.durationSec?.takeIf { it > 0 }?.let {
                            if (isNotEmpty()) append("\n")
                            append("Finished in ${it}s")
                        }
                        if (isEmpty()) append("Tap to open DSH Mobile.")
                    }
                }
            }
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setColor(Color.parseColor("#4D6BFE"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(if (attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup("dsh:${event.sessionId ?: "global"}")
            .setWhen(if (event.at > 0) event.at else System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(false)

        val publicBuilder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("DSH Mobile")
            .setContentText("Open the app to see the update.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        builder.setPublicVersion(publicBuilder.build())

        context.getSystemService(NotificationManager::class.java)?.notify(idFor(event), builder.build())
    }

    fun cancel(context: Context, event: BridgeEvent) {
        val kind = event.kind ?: return
        val id = event.id ?: return
        val sessionId = event.sessionId
        context.getSystemService(NotificationManager::class.java)?.cancel(idFor(sessionId, kind, id))
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    fun postTest(context: Context) {
        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setColor(Color.parseColor("#4D6BFE"))
            .setContentTitle("Alerts are working")
            .setContentText("DSH Mobile will show a notification here when a session needs you.")
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        context.getSystemService(NotificationManager::class.java)?.notify(TEST_ID, builder.build())
    }

    fun ongoing(context: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, NotificationBridgeService::class.java).setAction(NotificationBridgeService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setColor(Color.parseColor("#4D6BFE"))
            .setContentTitle("DSH Mobile")
            .setContentText(text)
            .setContentIntent(openIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop alerts", stop)
            .build()
    }

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun idFor(event: BridgeEvent): Int = when (event.type) {
        "approval" -> idFor(event.sessionId, "approval", event.id)
        "question" -> idFor(event.sessionId, "question", event.id)
        else -> idFor(event.sessionId, event.type, null)
    }

    private fun idFor(sessionId: String?, kind: String?, id: String?): Int {
        val key = "${sessionId ?: "global"}|${kind ?: "event"}|${id ?: "state"}"
        return 2000 + (key.hashCode() and 0x7FFFFFFF) % 2_000_000_000
    }
}
