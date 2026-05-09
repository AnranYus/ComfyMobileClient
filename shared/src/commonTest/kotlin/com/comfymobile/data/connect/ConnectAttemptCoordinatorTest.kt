package com.comfymobile.data.connect

import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.persistence.InMemoryServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ServerFormSubmit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectAttemptCoordinatorTest {

    /** Records dispatched inputs. */
    private class CapturingFacade : ConnectionStateMachineFacade {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val currentState: StateFlow<ConnectionState> = _state.asStateFlow()
        override val errors: Flow<ConnectError> = MutableSharedFlow(replay = 0)
        val dispatched = mutableListOf<ConnectionInput>()
        override fun dispatch(input: ConnectionInput) { dispatched += input }
    }

    private fun client(responder: () -> Pair<HttpStatusCode, String>): ComfyHttpClient {
        val mock = HttpClient(MockEngine { _ ->
            val (status, body) = responder()
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return ComfyHttpClient("http://stub", mock)
    }

    @Test fun successful_probe_upserts_server_to_history() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ ->
                client {
                    HttpStatusCode.OK to """{"system":{"comfyui_version":"0.3.4"},"devices":[]}"""
                }
            },
        )
        coord.start()
        runCurrent()

        // Drive ViewModel to emit a ConnectRequested event.
        vm.onHostChanged("192.168.1.10")
        vm.onPortChanged("8188")
        vm.onFriendlyNameChanged("MacBook")
        vm.onSubmit()
        runCurrent()

        val saved = store.getById("192.168.1.10:8188")
        assertNotNull(saved)
        assertEquals("MacBook", saved.label)
        assertEquals(1234L, saved.lastConnectedAtEpochMs)
        // No ConnectAttempt failure dispatched.
        assertTrue(
            facade.dispatched.none { it is ConnectionInput.ConnectAttempt },
            "Expected no ConnectAttempt dispatch on success, got: ${facade.dispatched}",
        )
        coord.stop()
    }

    @Test fun probe_with_404_dispatches_WRONG_PORT_404() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ -> client { HttpStatusCode.NotFound to "" } },
        )
        coord.start()
        runCurrent()

        vm.onHostChanged("192.168.1.10")
        vm.onPortChanged("8188")
        vm.onSubmit()
        runCurrent()

        val attempts = facade.dispatched.filterIsInstance<ConnectionInput.ConnectAttempt>()
        assertEquals(1, attempts.size)
        assertEquals(ConnectError.WRONG_PORT_404, attempts.single().classified)
        // Server NOT saved on failure.
        assertEquals(null, store.getById("192.168.1.10:8188"))
        coord.stop()
    }

    @Test fun probe_with_non_comfyui_body_dispatches_NOT_COMFYUI() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ ->
                client { HttpStatusCode.OK to """{"system":{},"devices":[]}""" }
            },
        )
        coord.start()
        runCurrent()

        vm.onHostChanged("192.168.1.10")
        vm.onPortChanged("8188")
        vm.onSubmit()
        runCurrent()

        val attempts = facade.dispatched.filterIsInstance<ConnectionInput.ConnectAttempt>()
        assertEquals(1, attempts.size)
        assertEquals(ConnectError.NOT_COMFYUI, attempts.single().classified)
        coord.stop()
    }

    @Test fun submit_with_empty_friendly_name_falls_back_to_host_label() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val vm = ConnectViewModel(
            machine = facade,
            historyStore = store,
            scope = this,
            language = ConnectionLanguage.En,
        )
        val coord = ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = store,
            machine = facade,
            scope = this,
            nowEpochMs = { 1L },
            httpClientFor = { _ ->
                client {
                    HttpStatusCode.OK to """{"system":{"comfyui_version":"0.3.4"},"devices":[]}"""
                }
            },
        )
        coord.start()
        runCurrent()

        vm.onHostChanged("192.168.1.10")
        vm.onPortChanged("8188")
        // No friendly name set.
        vm.onSubmit()
        runCurrent()

        assertEquals("192.168.1.10", store.getById("192.168.1.10:8188")?.label)
        coord.stop()
    }
}
