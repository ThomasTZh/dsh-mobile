package dev.dsh.mobile.core

import android.content.Context
import dev.dsh.mobile.data.ProfileStore
import dev.dsh.mobile.data.SecretStore
import dev.dsh.mobile.data.SettingsStore
import dev.dsh.mobile.net.LanDiscovery
import dev.dsh.mobile.net.PocketClient

/** Process-wide dependencies shared by the activity and the alert service. */
class AppGraph(context: Context) {
    val profileStore = ProfileStore(context)
    val secretStore = SecretStore(context)
    val settingsStore = SettingsStore(context)
    val pocketClient = PocketClient()
    val lanDiscovery = LanDiscovery(context)
}
