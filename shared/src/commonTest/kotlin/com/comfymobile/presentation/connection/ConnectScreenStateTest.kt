package com.comfymobile.presentation.connection

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.server.ServerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectScreenStateTest {

    @Test fun connected_state_maps_to_success_indicator_without_banner() {
        val ui = ConnectScreenState(
            connectionState = ConnectionState.Connected,
            history = listOf(server()),
            activeServer = server(),
        ).statusUi

        assertEquals(ConnectionStatusTone.Success, ui.tone)
        assertFalse(ui.pulsing)
        assertNull(ui.banner)
        assertEquals("Connected to Studio Mac", ui.label.resolve(ConnectionLanguage.En))
        assertTrue(
            ConnectScreenState(
                connectionState = ConnectionState.Connected,
                history = listOf(server()),
                activeServer = server(),
            ).shouldShowStatusIndicator,
        )
    }

    @Test fun connected_history_without_active_server_omits_saved_label() {
        val state = ConnectScreenState(
            connectionState = ConnectionState.Connected,
            history = listOf(server()),
        )

        assertNull(state.activeServer)
        assertEquals("Connected", state.statusUi.label.resolve(ConnectionLanguage.En))
        assertFalse(state.shouldShowStatusIndicator)
    }

    @Test fun lan_flake_reconnecting_is_subtle_and_silent() {
        val ui = ConnectScreenState(
            connectionState = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE),
            history = listOf(server()),
        ).statusUi

        assertEquals(ConnectionStatusTone.Subtle, ui.tone)
        assertTrue(ui.pulsing)
        assertNull(ui.banner)
        assertEquals("Brief network blip, reconnecting…", ui.label.resolve(ConnectionLanguage.En))
    }

    @Test fun background_resumed_reconnecting_has_explicit_banner() {
        val ui = ConnectScreenState(
            connectionState = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED),
            history = listOf(server()),
        ).statusUi

        assertEquals(ConnectionStatusTone.Info, ui.tone)
        assertTrue(ui.pulsing)
        assertNotNull(ui.banner)
        assertEquals("Welcome back, checking your generation…", ui.banner?.resolve(ConnectionLanguage.En))
    }

    @Test fun all_connect_errors_map_to_error_indicator_and_copy() {
        ConnectError.entries.forEach { error ->
            val state = ConnectScreenState(connectionState = ConnectionState.Lost(error))
            val ui = state.statusUi
            val copy = ConnectErrorCopy.lookup(error)

            assertEquals(ConnectionStatusTone.Error, ui.tone, "$error")
            assertFalse(ui.pulsing, "$error")
            assertEquals(copy.titleEn, ui.banner?.resolve(ConnectionLanguage.En), "$error")
            assertNotNull(copy.primaryCtaEn, "$error")
        }
    }

    private fun server() = ServerInfo(
        serverId = "192.168.1.5:8188",
        host = "192.168.1.5",
        port = 8188,
        label = "Studio Mac",
        lastConnectedAtEpochMs = 100L,
    )
}
