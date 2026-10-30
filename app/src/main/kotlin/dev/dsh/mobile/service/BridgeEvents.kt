package dev.dsh.mobile.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BridgeEvent(
    val v: Int = 1,
    val type: String,
    val sessionId: String? = null,
    val label: String? = null,
    val cwd: String? = null,
    val at: Long = 0L,
    val id: String? = null,
    val kind: String? = null,
    val tool: String? = null,
    val reason: String? = null,
    val header: String? = null,
    val question: String? = null,
    val options: List<String> = emptyList(),
    val count: Int = 0,
    val durationSec: Int? = null,
    val reply: String? = null,
    val error: String? = null,
    val waitedSec: Int? = null,
    val pending: List<BridgeEvent> = emptyList(),
    val recent: List<BridgeEvent> = emptyList(),
)

object BridgeJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun decode(raw: String): BridgeEvent? =
        runCatching { json.decodeFromString(BridgeEvent.serializer(), raw) }.getOrNull()
}
