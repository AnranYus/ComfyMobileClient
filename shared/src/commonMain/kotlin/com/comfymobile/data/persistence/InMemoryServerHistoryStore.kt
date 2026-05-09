package com.comfymobile.data.persistence

import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure-Kotlin [ServerHistoryStore] used by tests and as a fallback
 * when the platform Settings adapter is unavailable.
 */
class InMemoryServerHistoryStore : ServerHistoryStore {

    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, ServerInfo>>(emptyMap())

    override fun observeAll(): Flow<List<ServerInfo>> =
        state.map { snapshot ->
            snapshot.values.sortedByDescending { it.lastConnectedAtEpochMs }
        }

    override suspend fun listAll(): List<ServerInfo> =
        state.value.values.sortedByDescending { it.lastConnectedAtEpochMs }

    override suspend fun getById(serverId: String): ServerInfo? = state.value[serverId]

    override suspend fun upsert(server: ServerInfo) = mutex.withLock {
        val current = state.value
        val next = current + (server.serverId to server)
        // Evict the oldest if we'd exceed the cap with a new id.
        if (next.size > ServerHistoryStore.MAX_ENTRIES && server.serverId !in current) {
            val oldest = next.values.minByOrNull { it.lastConnectedAtEpochMs }
            state.value = if (oldest != null) next - oldest.serverId else next
        } else {
            state.value = next
        }
    }

    override suspend fun delete(serverId: String) = mutex.withLock {
        state.value = state.value - serverId
    }

    override suspend fun clear() = mutex.withLock {
        state.value = emptyMap()
    }
}
