package dev.dsh.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val pinSaved: Boolean = false,
    val lastUsedAt: Long = 0L,
)

@Serializable
data class ProfilesFile(
    val version: Int = 1,
    val selectedProfileId: String? = null,
    val profiles: List<ServerProfile> = emptyList(),
) {
    fun selected(): ServerProfile? = profiles.firstOrNull { it.id == selectedProfileId }
}

object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun decode(raw: String): ProfilesFile? =
        runCatching { json.decodeFromString<ProfilesFile>(raw) }.getOrNull()

    fun encode(file: ProfilesFile): String = json.encodeToString(ProfilesFile.serializer(), file)
}
