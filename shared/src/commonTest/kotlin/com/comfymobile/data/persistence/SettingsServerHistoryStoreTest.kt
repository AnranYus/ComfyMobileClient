package com.comfymobile.data.persistence

import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [SettingsServerHistoryStore] against an in-memory
 * [MapSettings] so test runs are platform-free. Exercises the same
 * surface as [InMemoryServerHistoryStoreTest] plus the
 * persistence-specific properties: load, persist, corrupt-recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsServerHistoryStoreTest {

    private fun srv(host: String, port: Int = 8188, label: String = host, last: Long = 0L): ServerInfo =
        ServerInfo(
            serverId = ServerInfo.idFor(host, port),
            host = host,
            port = port,
            label = label,
            lastConnectedAtEpochMs = last,
        )

    @Test fun load_from_empty_settings_yields_empty_store() = runTest {
        val store = SettingsServerHistoryStore(MapSettings())
        assertTrue(store.listAll().isEmpty())
    }

    @Test fun upsert_persists_to_settings_and_reads_back() = runTest {
        val settings = MapSettings()
        val storeA = SettingsServerHistoryStore(settings)
        storeA.upsert(srv("192.168.1.10", label = "MacBook", last = 1L))
        // Re-instantiate with the same settings — values must come back.
        val storeB = SettingsServerHistoryStore(settings)
        val readBack = storeB.getById("192.168.1.10:8188")
        assertNotNull(readBack)
        assertEquals("MacBook", readBack.label)
    }

    @Test fun listAll_orders_by_lastConnectedAt_descending() = runTest {
        val store = SettingsServerHistoryStore(MapSettings())
        store.upsert(srv("a", last = 100L))
        store.upsert(srv("b", last = 300L))
        store.upsert(srv("c", last = 200L))
        assertEquals(listOf("b", "c", "a"), store.listAll().map { it.host })
    }

    @Test fun observeAll_emits_after_upsert() = runTest {
        val store = SettingsServerHistoryStore(MapSettings())
        store.upsert(srv("a", last = 1L))
        val first = store.observeAll().first()
        assertEquals(listOf("a"), first.map { it.host })
    }

    @Test fun upsert_replaces_entry_with_same_id() = runTest {
        val store = SettingsServerHistoryStore(MapSettings())
        store.upsert(srv("a", label = "Old"))
        store.upsert(srv("a", label = "Renamed"))
        assertEquals("Renamed", store.getById("a:8188")?.label)
        assertEquals(1, store.listAll().size)
    }

    @Test fun delete_removes_only_specified_entry_and_persists() = runTest {
        val settings = MapSettings()
        val store = SettingsServerHistoryStore(settings)
        store.upsert(srv("a"))
        store.upsert(srv("b"))
        store.delete("a:8188")
        assertNull(store.getById("a:8188"))
        // Verify persistence: re-read.
        val storeReborn = SettingsServerHistoryStore(settings)
        assertNull(storeReborn.getById("a:8188"))
        assertNotNull(storeReborn.getById("b:8188"))
    }

    @Test fun clear_wipes_settings_key() = runTest {
        val settings = MapSettings()
        val store = SettingsServerHistoryStore(settings)
        store.upsert(srv("a"))
        store.clear()
        assertTrue(store.listAll().isEmpty())
        // Settings key should be gone too.
        assertNull(settings.getStringOrNull(SettingsServerHistoryStore.HISTORY_KEY))
    }

    @Test fun upsert_evicts_oldest_when_cap_exceeded() = runTest {
        val store = SettingsServerHistoryStore(MapSettings())
        repeat(ServerHistoryStore.MAX_ENTRIES) { i ->
            store.upsert(srv("host-$i", last = i.toLong()))
        }
        store.upsert(srv("host-new", last = 9_999L))
        assertEquals(ServerHistoryStore.MAX_ENTRIES, store.listAll().size)
        assertNull(store.getById("host-0:8188"))
        assertNotNull(store.getById("host-new:8188"))
    }

    @Test fun corrupt_persisted_value_is_silently_dropped_on_load() = runTest {
        val settings = MapSettings()
        settings.putString(SettingsServerHistoryStore.HISTORY_KEY, "not valid json {{{")
        // Constructor must not throw — corrupt state is dropped so the
        // user can still reach the connect flow.
        val store = SettingsServerHistoryStore(settings)
        // Easiest portable check: store starts empty after corruption,
        // and a fresh upsert succeeds.
        assertTrue(store.listAll().isEmpty())
        store.upsert(srv("post-corrupt"))
        assertNotNull(store.getById("post-corrupt:8188"))
    }

    @Test fun persisted_format_is_a_json_string_under_HISTORY_KEY() = runTest {
        val settings = MapSettings()
        val store = SettingsServerHistoryStore(settings)
        store.upsert(srv("a", last = 1L))
        val raw = settings.getStringOrNull(SettingsServerHistoryStore.HISTORY_KEY)
        assertNotNull(raw)
        // Light shape assertion — don't pin the exact serialiser
        // output, just confirm it's a JSON array containing the host.
        assertTrue(raw.startsWith("["), "expected JSON array, got: $raw")
        assertTrue("\"a\"" in raw, "expected host literal in: $raw")
    }
}
