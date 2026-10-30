package dev.dsh.mobile

import android.app.Application
import dev.dsh.mobile.core.AppGraph
import dev.dsh.mobile.service.NotificationPublisher

class DshMobileApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        NotificationPublisher.ensureChannels(this)
    }
}
