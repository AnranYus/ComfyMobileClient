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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests follow @Lily's pattern (PR #18 review msgs `795b6cb4` /
 * `d699debd`): the assertion synchronisation point is a direct
 * await on the *target* side effect (e.g.
 * `activeServer.current.first { it != null }`,
 * `historyStore.observeAll().first { matches }`,
 * `facade.dispatched.first { contains expected }`) — NOT
 * `runCurrent()` / `advanceUntilIdle()` which can return before
 * the MockEngine probe coroutine has resumed on its own dispatcher.
 *
 * Cleanup (`coord.stop()` + `coroutineContext.cancelChildren()`) is
 * always in `finally` so an assertion failure still leaves the test
 * scope tidy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectAttemptCoordinatorTest {

    /**
     * Records dispatched inputs in a [StateFlow] so tests can suspend
     * via [first] until the expected input lands, no matter which
     * dispatcher the dispatch ran on.
     */
    private class CapturingFacade : ConnectionStateMachineFacade {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val currentState: StateFlow<ConnectionState> = _state.asStateFlow()
        override val errors: Flow<ConnectError> = MutableSharedFlow(replay = 0)
        private val _dispatched = MutableStateFlow<List<ConnectionInput>>(emptyList())
        val dispatched: StateFlow<List<ConnectionInput>> = _dispatched.asStateFlow()
        override fun dispatch(input: ConnectionInput) {
            _dispatched.value = _dispatched.value + input
        }
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

    @Test fun successful_probe_upserts_server_sets_active_dispatches_Retry() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
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
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ ->
                client {
                    HttpStatusCode.OK to """{"system":{"comfyui_version":"0.3.4"},"devices":[]}"""
                }
            },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onFriendlyNameChanged("MacBook")
            vm.onSubmit()

            // Direct await: side effect lands on activeServer.
            val saved = withTimeout(5_000) {
                activeServer.current.first { it != null }
            }!!
            assertEquals("MacBook", saved.label)
            assertEquals(1234L, saved.lastConnectedAtEpochMs)

            // Direct await: history list contains the entry.
            val historyMatch = withTimeout(5_000) {
                store.observeAll().first { list -> list.any { it.serverId == saved.serverId } }
            }
            assertEquals(saved.serverId, historyMatch.first().serverId)

            // Direct await: dispatch list contains Retry.
            val dispatchedAfter = withTimeout(5_000) {
                facade.dispatched.first { list -> ConnectionInput.Retry in list }
            }
            assertTrue(
                dispatchedAfter.none { it is ConnectionInput.ConnectAttempt },
                "Expected no ConnectAttempt dispatch on success, got: $dispatchedAfter",
            )
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun successful_probe_when_in_Lost_state_dispatches_Retry_to_recover() = runTest {
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
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1L },
            httpClientFor = { _ ->
                client {
                    HttpStatusCode.OK to """{"system":{"comfyui_version":"0.3.4"},"devices":[]}"""
                }
            },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            withTimeout(5_000) {
                facade.dispatched.first { ConnectionInput.Retry in it }
            }
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
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
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ -> client { HttpStatusCode.NotFound to "" } },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()

            val attempt = withTimeout(5_000) {
                facade.dispatched
                    .first { list -> list.any { it is ConnectionInput.ConnectAttempt } }
                    .filterIsInstance<ConnectionInput.ConnectAttempt>()
                    .single()
            }
            assertEquals(ConnectError.WRONG_PORT_404, attempt.classified)
            // Server NOT saved on failure.
            assertEquals(null, store.getById("192.168.1.10:8188"))
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
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
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1234L },
            httpClientFor = { _ ->
                client { HttpStatusCode.OK to """{"system":{},"devices":[]}""" }
            },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()

            val attempt = withTimeout(5_000) {
                facade.dispatched
                    .first { list -> list.any { it is ConnectionInput.ConnectAttempt } }
                    .filterIsInstance<ConnectionInput.ConnectAttempt>()
                    .single()
            }
            assertEquals(ConnectError.NOT_COMFYUI, attempt.classified)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun probe_failure_does_NOT_set_active_server() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
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
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1L },
            httpClientFor = { _ -> client { HttpStatusCode.NotFound to "" } },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            // Wait until the failure dispatch lands, which proves the
            // probe coroutine has fully completed. After that, assert
            // activeServer was NOT touched.
            withTimeout(5_000) {
                facade.dispatched
                    .first { list -> list.any { it is ConnectionInput.ConnectAttempt } }
            }
            assertEquals(null, activeServer.current.value)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun coordinator_propagates_CancellationException() = runTest {
        // Regression: per @Lily PR #18 review (msg `75c88c17`),
        // `catch (t: Throwable)` must not swallow CancellationException.
        // The probe coroutine throws CancellationException synchronously,
        // which the coordinator must rethrow rather than turning into
        // a ConnectAttempt failure dispatch.
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val cancellingClient = ComfyHttpClient(
            baseUrl = "http://stub",
            client = HttpClient(MockEngine { _ ->
                throw CancellationException("test cancel")
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )
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
            activeServer = ActiveServerHolder(),
            scope = this,
            nowEpochMs = { 1L },
            httpClientFor = { _ -> cancellingClient },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            vm.onSubmit()
            // Suspend a tiny virtual moment to give the launch a
            // chance to start; we never expect a dispatch, so we
            // can't `first { ... }` on dispatched here. Instead we
            // verify by stopping the coordinator and inspecting the
            // final dispatched list — it must NOT contain a
            // ConnectAttempt.
            // `runCurrent()` is an extension on TestScope; calling
            // fully-qualified breaks Android compile (the compiler
            // parses it as a top-level call and can't bind the
            // extension receiver). Use the imported form so the
            // implicit `this: TestScope` receiver from `runTest` is
            // picked up.
            runCurrent()
            assertTrue(
                facade.dispatched.value
                    .filterIsInstance<ConnectionInput.ConnectAttempt>().isEmpty(),
                "Expected NO ConnectAttempt on cancellation, got: ${facade.dispatched.value}",
            )
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun submit_with_empty_friendly_name_falls_back_to_host_label() = runTest {
        val store = InMemoryServerHistoryStore()
        val facade = CapturingFacade()
        val activeServer = ActiveServerHolder()
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
            activeServer = activeServer,
            scope = this,
            nowEpochMs = { 1L },
            httpClientFor = { _ ->
                client {
                    HttpStatusCode.OK to """{"system":{"comfyui_version":"0.3.4"},"devices":[]}"""
                }
            },
        )
        try {
            coord.start()
            vm.onHostChanged("192.168.1.10")
            vm.onPortChanged("8188")
            // No friendly name set.
            vm.onSubmit()
            val saved = withTimeout(5_000) {
                activeServer.current.first { it != null }
            }!!
            assertEquals("192.168.1.10", saved.label)
        } finally {
            coord.stop()
            coroutineContext.cancelChildren()
        }
    }
}
