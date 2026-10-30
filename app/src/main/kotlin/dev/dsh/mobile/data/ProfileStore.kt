package dev.dsh.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Atomic JSON profile store. The file is private to the app; a corrupted file is
 * preserved for diagnosis and replaced with an empty state instead of crashing.
 */
class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val _state = MutableStateFlow(ProfilesFile())
    val state: StateFlow<ProfilesFile> = _state.asStateFlow()

    private var loaded = false

    suspend fun ensureLoaded(): ProfilesFile = withContext(Dispatchers.IO) {
        if (!loaded) {
            _state.value = readFile()
            loaded = true
        }
        _state.value
    }

    private fun readFile(): ProfilesFile {
        if (!file.isFile) return ProfilesFile()
        return try {
            val raw = file.readText()
            ProfileCodec.decode(raw) ?: quarantineAndReset()
        } catch (_: Exception) {
            quarantineAndReset()
        }
    }

    private fun quarantineAndReset(): ProfilesFile {
        runCatching {
            file.renameTo(File(file.parentFile, "profiles.json.corrupt-${System.currentTimeMillis()}"))
        }
        return ProfilesFile()
    }

    private fun write(next: ProfilesFile) {
        val temp = File(file.parentFile, "profiles.json.tmp")
        temp.writeText(ProfileCodec.encode(next))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun update(transform: (ProfilesFile) -> ProfilesFile): ProfilesFile = withContext(Dispatchers.IO) {
        ensureLoaded()
        val next = transform(_state.value)
        write(next)
        _state.value = next
        next
    }

    suspend fun upsert(profile: ServerProfile) = update { current ->
        val index = current.profiles.indexOfFirst { it.id == profile.id }
        val profiles = if (index >= 0) {
            current.profiles.toMutableList().also { it[index] = profile }
        } else {
            current.profiles + profile
        }
        current.copy(profiles = profiles, selectedProfileId = profile.id)
    }

    suspend fun select(id: String, now: Long = System.currentTimeMillis()) = update { current ->
        val profiles = current.profiles.map { if (it.id == id) it.copy(lastUsedAt = now) else it }
        current.copy(profiles = profiles, selectedProfileId = id)
    }

    suspend fun markPinSaved(id: String, saved: Boolean) = update { current ->
        current.copy(profiles = current.profiles.map { if (it.id == id) it.copy(pinSaved = saved) else it })
    }

    suspend fun remove(id: String) = update { current ->
        val profiles = current.profiles.filterNot { it.id == id }
        current.copy(
            profiles = profiles,
            selectedProfileId = if (current.selectedProfileId == id) profiles.firstOrNull()?.id else current.selectedProfileId,
        )
    }
}
