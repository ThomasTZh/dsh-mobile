package dev.dsh.mobile.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgePhase {
    DISABLED,
    CONNECTING,
    LIVE,
    RECONNECTING,
    UNAUTHORIZED,
    BRIDGE_MISSING,
    PIN_UNAVAILABLE,
    NO_PROFILE,
    NO_PIN,
    DISCONNECTED,
    ERROR,
}

data class BridgeStatus(
    val phase: BridgePhase = BridgePhase.DISABLED,
    val message: String = "",
    val updatedAt: Long = 0L,
)

/**
 * Process-wide alert status. The service writes it, the UI observes it; no
 * binding is required because both live in the same process.
 */
object BridgeRuntime {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun set(phase: BridgePhase, message: String = "") {
        _status.value = BridgeStatus(phase, message, System.currentTimeMillis())
    }

    fun clear() {
        _status.value = BridgeStatus()
    }
}
