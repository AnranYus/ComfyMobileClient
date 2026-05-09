package com.comfymobile.data.persistence

import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Multiplatform-Settings backed [ServerHistoryStore]. Persists the
 * server list to platform native storage (SharedPreferences on
 * Android, NSUserDefaults on iOS) via Russhwolf's library; the JSON
 * encoding stays under our control so a future migration to a richer
 * format (e.g. SQLDelight) can read the existing data without a
 * schema dance.
 *
 * The DI module (T1.4b part 3c) supplies a [Settings] instance with
 * a fixed key prefix; this class doesn't pick the key namespace,
 * just the JSON document shape under [HISTORY_KEY].
 *
 * In-memory mirror via [MutableStateFlow] makes [observeAll] cheap
 * (no platform-side change listener required) and gives the same
 * "snapshot per write" semantics as [InMemoryServerHistoryStore].
 */
class SettingsServerHistoryStore(
    private val settings: Settings,
) : ServerHistoryStore {

    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, ServerInfo>>(loadFromSettings())

    override fun observeAll(): Flow<List<ServerInfo>> =
        state.map { snapshot ->
            snapshot.values.sortedByDescending { it.lastConnectedAtEpochMs }
        }

    override suspend fun listAll(): List<ServerInfo> =
        state.value.values.sortedByDescending { it.lastConnectedAtEpochMs }

    override suspend fun getById(serverId: String): ServerInfo? = state.value[serverId]

    override suspend fun upsert(server: ServerInfo) = mutex.withLock {
        val current = state.value
        val nextWithEntry = current + (server.serverId to server)
        val next = if (
            nextWithEntry.size > ServerHistoryStore.MAX_ENTRIES &&
            server.serverId !in current
        ) {
            val oldest = nextWithEntry.values.minByOrNull { it.lastConnectedAtEpochMs }
            if (oldest != null) nextWithEntry - oldest.serverId else nextWithEntry
        } else {
            nextWithEntry
        }
        state.value = next
        persist(next)
    }

    override suspend fun delete(serverId: String) = mutex.withLock {
        val next = state.value - serverId
        state.value = next
        persist(next)
    }

    override suspend fun clear() = mutex.withLock {
        state.value = emptyMap()
        settings.remove(HISTORY_KEY)
    }

    // ----------------------------------------------------------------- io

    private fun loadFromSettings(): Map<String, ServerInfo> {
        val text = settings.getStringOrNull(HISTORY_KEY) ?: return emptyMap()
        return try {
            val list = json.decodeFromString(ListSerializer(ServerInfo.serializer()), text)
            list.associateBy { it.serverId }
        } catch (t: Throwable) {
            // Corrupt persisted state — drop and start fresh rather
            // than block the user from the connect flow. A future
            // platform-specific log can surface this.
            emptyMap()
        }
    }

    private fun persist(snapshot: Map<String, ServerInfo>) {
        if (snapshot.isEmpty()) {
            settings.remove(HISTORY_KEY)
            return
        }
        val list = snapshot.values.sortedByDescending { it.lastConnectedAtEpochMs }
        settings.putString(HISTORY_KEY, json.encodeToString(ListSerializer(ServerInfo.serializer()), list))
    }

    companion object {
        /** Storage key for the entire server history list. */
        const val HISTORY_KEY: String = "comfy.server_history.v1"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            // Pretty print is wasted on the platform store; smaller
            // values save SharedPreferences write time.
            prettyPrint = false
        }
    }
}
