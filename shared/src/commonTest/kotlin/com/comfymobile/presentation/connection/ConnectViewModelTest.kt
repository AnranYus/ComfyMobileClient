package com.comfymobile.presentation.connection

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.persistence.InMemoryServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    @Test fun submit_valid_form_emits_connect_request() = runTest {
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(),
            historyStore = InMemoryServerHistoryStore(),
            scope = backgroundScope,
        )

        viewModel.onHostChanged("192.168.1.5")
        viewModel.onPortChanged("8188")
        viewModel.onFriendlyNameChanged("Studio Mac")
        viewModel.onSubmit()

        val event = viewModel.events.first()
        assertEquals(
            ConnectUiEvent.ConnectRequested(
                ServerFormSubmit(
                    host = "192.168.1.5",
                    port = 8188,
                    friendlyName = "Studio Mac",
                ),
            ),
            event,
        )
    }

    @Test fun retry_dispatches_to_state_machine_facade() = runTest {
        val machine = FakeConnectionStateMachine(ConnectionState.Lost(com.comfymobile.data.network.ConnectError.TIMEOUT))
        val viewModel = ConnectViewModel(
            machine = machine,
            historyStore = InMemoryServerHistoryStore(),
            scope = backgroundScope,
        )

        viewModel.onRetry()

        assertEquals(listOf<ConnectionInput>(ConnectionInput.Retry), machine.dispatchedInputs)
    }

    @Test fun selecting_history_entry_emits_connect_request() = runTest {
        val store = InMemoryServerHistoryStore()
        val server = ServerInfo(
            serverId = "192.168.1.5:8188",
            host = "192.168.1.5",
            port = 8188,
            label = "Studio Mac",
            lastConnectedAtEpochMs = 100L,
        )
        store.upsert(server)
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(),
            historyStore = store,
            scope = backgroundScope,
        )

        viewModel.onServerSelected(server)

        val event = viewModel.events.first()
        assertEquals(
            ConnectUiEvent.ConnectRequested(
                ServerFormSubmit(
                    host = "192.168.1.5",
                    port = 8188,
                    friendlyName = "Studio Mac",
                ),
            ),
            event,
        )
    }

    @Test fun saved_history_does_not_become_active_server_on_connected_cold_start() = runTest {
        val store = InMemoryServerHistoryStore()
        val server = ServerInfo(
            serverId = "192.168.1.5:8188",
            host = "192.168.1.5",
            port = 8188,
            label = "Studio Mac",
            lastConnectedAtEpochMs = 100L,
        )
        store.upsert(server)
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(ConnectionState.Connected),
            historyStore = store,
            scope = this,
        )

        advanceUntilIdle()

        val state = viewModel.screenState.value
        assertEquals(listOf(server), state.history)
        assertNull(state.activeServer)
        assertEquals("Connected", state.statusUi.label.resolve(ConnectionLanguage.En))
        coroutineContext.cancelChildren()
    }

    @Test fun friendly_modal_save_updates_history_store() = runTest {
        val store = InMemoryServerHistoryStore()
        val server = ServerInfo(
            serverId = "192.168.1.5:8188",
            host = "192.168.1.5",
            port = 8188,
            label = "Old",
            lastConnectedAtEpochMs = 100L,
        )
        store.upsert(server)
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(),
            historyStore = store,
            scope = this,
        )

        viewModel.onServerLongPressed(server)
        viewModel.onFriendlyModalChanged("Renamed")
        viewModel.onFriendlyModalSave()
        advanceUntilIdle()

        assertEquals("Renamed", store.getById("192.168.1.5:8188")?.label)
        assertTrue(viewModel.screenState.value.friendlyNameModal is FriendlyNameModalState.Hidden)
        coroutineContext.cancelChildren()
    }

    @Test fun deleting_history_entry_removes_it_from_store() = runTest {
        val store = InMemoryServerHistoryStore()
        val server = ServerInfo(
            serverId = "192.168.1.5:8188",
            host = "192.168.1.5",
            port = 8188,
            label = "Studio Mac",
            lastConnectedAtEpochMs = 100L,
        )
        store.upsert(server)
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(),
            historyStore = store,
            scope = this,
        )

        viewModel.onServerDelete(server)
        advanceUntilIdle()

        assertNull(store.getById("192.168.1.5:8188"))
        coroutineContext.cancelChildren()
    }

    @Test fun dismiss_error_hides_error_details_without_dispatching_retry() = runTest {
        val machine = FakeConnectionStateMachine(ConnectionState.Lost(com.comfymobile.data.network.ConnectError.TIMEOUT))
        val viewModel = ConnectViewModel(
            machine = machine,
            historyStore = InMemoryServerHistoryStore(),
            scope = this,
        )

        viewModel.onDismissError()
        advanceUntilIdle()

        assertTrue(!viewModel.screenState.value.showErrorDetails)
        assertTrue(machine.dispatchedInputs.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun screenState_reflects_active_server_label_after_setActive() = runTest {
        // Per @Lily PR #19 review (`4413957569`) blocker 2:
        // ConnectViewModel must observe ActiveServerHolder so a
        // successful probe — which calls activeServer.setActive(...) —
        // surfaces in ConnectScreenState.activeServer + statusUi.
        val store = InMemoryServerHistoryStore()
        val activeServer = ActiveServerHolder()
        val viewModel = ConnectViewModel(
            machine = FakeConnectionStateMachine(ConnectionState.Connected),
            historyStore = store,
            scope = this,
            activeServer = activeServer,
        )
        // Initially no active server.
        advanceUntilIdle()
        assertNull(viewModel.screenState.value.activeServer)
        assertTrue(!viewModel.screenState.value.shouldShowStatusIndicator)

        // Coordinator-equivalent action: probe succeeded → setActive.
        val server = ServerInfo(
            serverId = "192.168.1.5:8188",
            host = "192.168.1.5",
            port = 8188,
            label = "Studio Mac",
            lastConnectedAtEpochMs = 1234L,
        )
        activeServer.setActive(server)
        advanceUntilIdle()

        val state = viewModel.screenState.value
        // (i) ConnectScreenState.activeServer != null
        assertNotNull(state.activeServer)
        assertEquals("Studio Mac", state.activeServer!!.label)
        // (ii) shouldShowStatusIndicator == true (Connected state +
        // active server makes the indicator visible per
        // ConnectScreenState.shouldShowStatusIndicator).
        assertTrue(state.shouldShowStatusIndicator)
        // (iii) statusUi label includes the friendly name.
        val labelEn = state.statusUi.label.resolve(ConnectionLanguage.En)
        assertTrue(
            labelEn.contains("Studio Mac"),
            "Expected status label to include friendly name 'Studio Mac', got: $labelEn",
        )
        coroutineContext.cancelChildren()
    }
}
