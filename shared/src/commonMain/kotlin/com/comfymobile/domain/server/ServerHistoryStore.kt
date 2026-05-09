package com.comfymobile.domain.server

import kotlinx.coroutines.flow.Flow

/**
 * Persistent store of LAN servers the user has connected to.
 * Production implementation in `data/persistence/` is backed by
 * Multiplatform Settings (per ADR-0004); tests use the in-memory
 * fake.
 *
 * The store is intentionally bounded ([MAX_ENTRIES]) so a long-time
 * user with many networks doesn't end up with hundreds of stale
 * entries; the oldest entry is evicted when the cap is hit.
 */
interface ServerHistoryStore {

    /**
     * All known server entries, ordered most-recent-first by
     * [ServerInfo.lastConnectedAtEpochMs]. Hot Flow — emits a fresh
     * snapshot on every write.
     */
    fun observeAll(): Flow<List<ServerInfo>>

    /** One-shot snapshot, same ordering as [observeAll]. */
    suspend fun listAll(): List<ServerInfo>

    /** Lookup by [ServerInfo.serverId]; returns null when absent. */
    suspend fun getById(serverId: String): ServerInfo?

    /**
     * Insert or update a server entry. Used by the connect flow to
     * record a successful connection (refreshing
     * [ServerInfo.lastConnectedAtEpochMs] for ordering) and to commit
     * a new friendly label.
     *
     * If the store already holds [MAX_ENTRIES] and this is a new id,
     * the oldest entry (smallest [ServerInfo.lastConnectedAtEpochMs])
     * is evicted to make room.
     */
    suspend fun upsert(server: ServerInfo)

    /** Remove a single entry by id. No-op when absent. */
    suspend fun delete(serverId: String)

    /** Wipe every entry (used when clearing app data). */
    suspend fun clear()

    companion object {
        const val MAX_ENTRIES: Int = 32
    }
}
