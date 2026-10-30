package dev.dsh.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val autoConnect: Boolean = true,
    val keepAwake: Boolean = false,
    val textZoom: Int = 100,
    val immersive: Boolean = false,
    val alertsEnabled: Boolean = false,
    val alertDone: Boolean = true,
    val alertApproval: Boolean = true,
    val alertQuestion: Boolean = true,
    val alertError: Boolean = true,
    val hideDetails: Boolean = false,
    val bridgePort: Int = 3082,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        autoConnect = prefs.getBoolean("autoConnect", true),
        keepAwake = prefs.getBoolean("keepAwake", false),
        textZoom = prefs.getInt("textZoom", 100).coerceIn(80, 160),
        immersive = prefs.getBoolean("immersive", false),
        alertsEnabled = prefs.getBoolean("alertsEnabled", false),
        alertDone = prefs.getBoolean("alertDone", true),
        alertApproval = prefs.getBoolean("alertApproval", true),
        alertQuestion = prefs.getBoolean("alertQuestion", true),
        alertError = prefs.getBoolean("alertError", true),
        hideDetails = prefs.getBoolean("hideDetails", false),
        bridgePort = prefs.getInt("bridgePort", 3082).coerceIn(1, 65535),
    )

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean("autoConnect", next.autoConnect)
            .putBoolean("keepAwake", next.keepAwake)
            .putInt("textZoom", next.textZoom.coerceIn(80, 160))
            .putBoolean("immersive", next.immersive)
            .putBoolean("alertsEnabled", next.alertsEnabled)
            .putBoolean("alertDone", next.alertDone)
            .putBoolean("alertApproval", next.alertApproval)
            .putBoolean("alertQuestion", next.alertQuestion)
            .putBoolean("alertError", next.alertError)
            .putBoolean("hideDetails", next.hideDetails)
            .putInt("bridgePort", next.bridgePort.coerceIn(1, 65535))
            .apply()
        _state.value = next
        return next
    }

    fun reload() {
        _state.value = read()
    }
}
