package com.comfymobile.data.persistence

import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryServerHistoryStoreTest {

    private fun srv(host: String, port: Int = 8188, label: String = host, last: Long = 0L): ServerInfo =
        ServerInfo(
            serverId = ServerInfo.idFor(host, port),
            host = host,
            port = port,
            label = label,
            lastConnectedAtEpochMs = last,
        )

    @Test fun upsert_inserts_new_entry() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("192.168.1.10"))
        assertNotNull(store.getById("192.168.1.10:8188"))
    }

    @Test fun upsert_replaces_existing_entry_with_same_id() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("192.168.1.10", label = "Old"))
        store.upsert(srv("192.168.1.10", label = "Renamed"))
        assertEquals("Renamed", store.getById("192.168.1.10:8188")?.label)
    }

    @Test fun listAll_orders_by_lastConnectedAt_descending() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("a", last = 100L))
        store.upsert(srv("b", last = 300L))
        store.upsert(srv("c", last = 200L))
        val ids = store.listAll().map { it.host }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun observeAll_emits_after_upsert() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("a", last = 1L))
        val first = store.observeAll().first()
        assertEquals(listOf("a"), first.map { it.host })
    }

    @Test fun delete_removes_only_specified_entry() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("a"))
        store.upsert(srv("b"))
        store.delete("a:8188")
        assertNull(store.getById("a:8188"))
        assertNotNull(store.getById("b:8188"))
    }

    @Test fun clear_wipes_every_entry() = runTest {
        val store = InMemoryServerHistoryStore()
        store.upsert(srv("a"))
        store.upsert(srv("b"))
        store.clear()
        assertTrue(store.listAll().isEmpty())
    }

    @Test fun upsert_evicts_oldest_when_cap_is_exceeded() = runTest {
        val store = InMemoryServerHistoryStore()
        // Fill to cap.
        repeat(ServerHistoryStore.MAX_ENTRIES) { i ->
            store.upsert(srv("host-$i", last = i.toLong()))
        }
        assertEquals(ServerHistoryStore.MAX_ENTRIES, store.listAll().size)
        // Oldest entry has last = 0; insert a fresh one.
        val newest = srv("host-new", last = 9_999L)
        store.upsert(newest)
        // Cap unchanged; oldest gone; newest present.
        assertEquals(ServerHistoryStore.MAX_ENTRIES, store.listAll().size)
        assertNull(store.getById("host-0:8188"))
        assertNotNull(store.getById("host-new:8188"))
    }

    @Test fun upsert_of_existing_id_does_not_evict_anything() = runTest {
        val store = InMemoryServerHistoryStore()
        repeat(ServerHistoryStore.MAX_ENTRIES) { i ->
            store.upsert(srv("host-$i", last = i.toLong()))
        }
        // Refresh existing entry — should not evict.
        store.upsert(srv("host-0", label = "Refreshed", last = 9_999L))
        assertEquals(ServerHistoryStore.MAX_ENTRIES, store.listAll().size)
        assertNotNull(store.getById("host-0:8188"))
    }

    @Test fun ServerInfo_validates_blank_host() {
        try {
            srv(host = "")
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("host" in (e.message ?: ""))
        }
    }

    @Test fun ServerInfo_validates_port_range() {
        try {
            srv(host = "x", port = 0)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("port" in (e.message ?: ""))
        }
        try {
            srv(host = "x", port = 65536)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("port" in (e.message ?: ""))
        }
    }
}
