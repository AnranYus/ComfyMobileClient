package com.comfymobile.data.connection

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionEffectRunner
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ConnectionStateReducer
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.network.WsDropReason
import com.comfymobile.data.network.WsEvent
import com.comfymobile.domain.server.ServerInfo
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end tests of the reducer + runner + state-machine driver
 * triple. Per the test patterns established in
 * ConnectionEffectRunnerTest, every assertion uses
 * `async { ... take(N).toList() }.await()` for synchronisation, and
 * the runner is given the same TestScope as the test itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateMachineTest {

    private val baseUrl = "http://192.168.1.10:8188"

    private fun http(): ComfyHttpClient {
        val mock = HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return ComfyHttpClient(baseUrl, mock)
    }

    private class FakeWs : WebSocketSource {
        val frames = Channel<WsEvent>(capacity = 64)
        // Tracks every clientId that called connect() so integration
        // tests can assert WS open / switch behaviour without reaching
        // into the runner directly (per @Lily PR #19 review
        // `4413996459`: regression must traverse the real
        // `machine.dispatch(Retry)` path).
        var lastClientId: String? = null
        var connectCalls: Int = 0
        override fun connect(clientId: String): Flow<WsEvent> {
            lastClientId = clientId
            connectCalls += 1
            return frames.receiveAsFlow()
        }
    }

    private fun buildMachine(scope: kotlinx.coroutines.CoroutineScope): Triple<ConnectionStateMachine, ConnectionEffectRunner, FakeWs> {
        val ws = FakeWs()
        // Pre-populated active-server holder so the runner's null-guard
        // never triggers in machine-level tests. Active-server-aware
        // behaviour (null guard, server switch) is covered by
        // ConnectionEffectRunnerTest, not here.
        val activeServer = ActiveServerHolder().also {
            it.setActive(
                ServerInfo(
                    serverId = "192.168.1.10:8188",
                    host = "192.168.1.10",
                    port = 8188,
                    label = "test",
                    lastConnectedAtEpochMs = 0L,
                ),
            )
        }
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { _ -> http() },
            webSocketSourceFactory = { _ -> ws },
            scope = scope,
        )
        val reducer = ConnectionStateReducer(clientIdProvider = { "client-uuid" })
        val machine = ConnectionStateMachine(reducer, runner, scope)
        return Triple(machine, runner, ws)
    }

    @Test fun ws_drop_via_dispatch_transitions_to_Reconnecting_LAN_FLAKE() = runTest {
        // Per @Lily PR #12 follow-up review (msg `77de9c6e`): immediate
        // transitions use runCurrent() so we don't accidentally advance
        // virtual time past the 30s give-up timer scheduled by the WS
        // drop. Only the explicit timer tests use advanceTimeBy +
        // advanceUntilIdle.
        val (machine, _, _) = buildMachine(this)
        machine.start()
        runCurrent()
        assertEquals(ConnectionState.Connected, machine.currentState.value)

        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()

        val state = machine.currentState.value
        val reconnecting = assertIs<ConnectionState.Reconnecting>(state)
        assertEquals(com.comfymobile.data.network.ReconnectReason.LAN_FLAKE, reconnecting.reason)
        machine.stop()
    }

    @Test fun reconnect_then_event_returns_to_Connected_via_runner_emission() = runTest {
        val (machine, _, ws) = buildMachine(this)
        machine.start()
        runCurrent()

        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()
        // Sanity: now Reconnecting.
        assertIs<ConnectionState.Reconnecting>(machine.currentState.value)

        // OpenWs fired by reducer → runner; ws.frames is empty until
        // we push. Push an event; runner forwards as ConnectionInput.Ws,
        // reducer transitions back to Connected.
        ws.frames.send(WsEvent.Status(queueRemaining = 0))
        runCurrent()

        assertEquals(ConnectionState.Connected, machine.currentState.value)
        machine.stop()
    }

    @Test fun give_up_timer_transitions_to_Lost() = runTest {
        val (machine, _, _) = buildMachine(this)
        machine.start()
        runCurrent()

        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()

        // Default config: 30s give-up timer. Explicitly advance time
        // past it (this is what the test is about).
        advanceTimeBy(31_000)
        runCurrent()

        val state = machine.currentState.value
        assertIs<ConnectionState.Lost>(state)
        machine.stop()
    }

    @Test fun retry_from_Lost_re_enters_Reconnecting() = runTest {
        val (machine, _, _) = buildMachine(this)
        machine.start()
        runCurrent()
        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()
        // Trigger the give-up timer to land in Lost.
        advanceTimeBy(31_000)
        runCurrent()
        assertIs<ConnectionState.Lost>(machine.currentState.value)

        // Retry is an immediate transition; runCurrent only.
        machine.dispatch(ConnectionInput.Retry)
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(machine.currentState.value)
        machine.stop()
    }

    @Test fun connect_attempt_failure_pins_classified_error_in_Lost() = runTest {
        val (machine, _, _) = buildMachine(this)
        machine.start()
        runCurrent()
        machine.dispatch(ConnectionInput.ConnectAttempt(classified = ConnectError.NOT_COMFYUI))
        runCurrent()
        val lost = assertIs<ConnectionState.Lost>(machine.currentState.value)
        assertEquals(ConnectError.NOT_COMFYUI, lost.error)
        machine.stop()
    }

    @Test fun start_is_idempotent() = runTest {
        val (machine, _, _) = buildMachine(this)
        machine.start()
        machine.start()
        machine.start()
        runCurrent()
        // No throw, single observer; verify by dispatching once and
        // confirming a single state transition (not three).
        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(machine.currentState.value)
        machine.stop()
    }

    @Test fun emitted_errors_observable_via_machine_errors_flow() = runTest {
        val (machine, _, _) = buildMachine(this)
        machine.start()
        // start collector first per the established pattern
        val collector = async { machine.errors.take(1).toList() }
        runCurrent()
        machine.dispatch(ConnectionInput.ConnectAttempt(classified = ConnectError.WRONG_PORT_404))
        runCurrent()
        val errors = collector.await()
        assertEquals(listOf(ConnectError.WRONG_PORT_404), errors)
        machine.stop()
    }

    @Test fun trackInFlight_propagates_to_runner() = runTest {
        val (machine, runner, _) = buildMachine(this)
        machine.trackInFlight("p-1")
        machine.trackInFlight("p-2")
        assertEquals(setOf("p-1", "p-2"), runner.snapshotInFlight())
        machine.untrackInFlight("p-1")
        assertEquals(setOf("p-2"), runner.snapshotInFlight())
    }

    @Test fun dispatch_before_start_is_buffered_and_processed_after_start() = runTest {
        // Regression for @Lily PR #12 review (msg `335b8813`):
        // externalInputs must NOT drop early dispatches. Even if a
        // UI handler / platform broadcast fires before the observer
        // coroutine begins collecting, the input has to be processed.
        // Backed by an UNLIMITED Channel; tryEmit-on-SharedFlow would
        // have silently dropped this.
        val (machine, _, _) = buildMachine(this)
        // Dispatch BEFORE start. With Channel buffering this must be
        // queued; with the previous MutableSharedFlow it was lost.
        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        machine.start()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(machine.currentState.value)
        machine.stop()
    }

    @Test fun multiple_dispatches_before_start_are_all_processed_in_order() = runTest {
        val (machine, _, _) = buildMachine(this)
        // Burst of three dispatches before the observer starts.
        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        machine.dispatch(ConnectionInput.Retry)             // re-issues OpenWs
        machine.dispatch(ConnectionInput.ConnectAttempt(classified = ConnectError.NOT_COMFYUI))
        machine.start()
        runCurrent()
        // Final state should be Lost(NOT_COMFYUI) — the last dispatch
        // wins after the reducer chains through all three.
        val lost = assertIs<ConnectionState.Lost>(machine.currentState.value)
        assertEquals(ConnectError.NOT_COMFYUI, lost.error)
        machine.stop()
    }

    @Test fun manual_connect_from_initial_Connected_opens_WS_for_active_server() = runTest {
        // Per @Lily PR #19 review (`4413996459`): the production
        // path that exposed this bug is:
        //   coordinator probe success
        //   → activeServer.setActive(server)
        //   → machine.dispatch(ConnectionInput.Retry)
        // From the initial `Connected` state Retry was previously a
        // no-op, so the runner never opened a WS — the app showed
        // "Connected" with no live session. After the reducer fix
        // (Connected + Retry → OpenWs side effect), the runner sees
        // the active server and opens a real WS.
        //
        // This test traverses the full real path:
        //   machine.dispatch(Retry) → reducer → runner.run(OpenWs)
        //   → FakeWs.connect(clientId)
        // No direct runner.run() calls — that would mask the reducer
        // side-effect bug.
        val ws = FakeWs()
        val activeServer = ActiveServerHolder() // null at first
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { _ -> http() },
            webSocketSourceFactory = { _ -> ws },
            scope = this,
        )
        val reducer = ConnectionStateReducer(clientIdProvider = { "client-uuid" })
        val machine = ConnectionStateMachine(reducer, runner, this)
        machine.start()
        runCurrent()
        assertEquals(ConnectionState.Connected, machine.currentState.value)
        assertEquals(0, ws.connectCalls, "Pre-condition: no WS open before user connects")

        // Coordinator-equivalent action sequence: setActive + Retry.
        val server = ServerInfo(
            serverId = "192.168.1.10:8188",
            host = "192.168.1.10",
            port = 8188,
            label = "Studio",
            lastConnectedAtEpochMs = 1L,
        )
        activeServer.setActive(server)
        machine.dispatch(ConnectionInput.Retry)
        runCurrent()

        // Reducer's Connected + Retry → OpenWs → runner opens WS for
        // the active server.
        assertEquals(1, ws.connectCalls, "Expected exactly 1 WS open after manual connect")
        assertEquals("client-uuid", ws.lastClientId)
        // State stays Connected — we're confirming a live server, not
        // recovering from a drop.
        assertEquals(ConnectionState.Connected, machine.currentState.value)
        machine.stop()
    }

    @Test fun manual_switch_A_to_B_while_Connected_cancels_A_and_opens_B() = runTest {
        // Continuation of the previous regression: after we're
        // Connected to A (one WS open), the user picks server B from
        // history. The coordinator dispatches Retry again. The
        // reducer's Connected + Retry → OpenWs side effect; the
        // runner's syncActiveServer(B) cancels A's wsJob then opens
        // a fresh WS against B's WebSocketSource.
        //
        // Different FakeWs instances per server prove the second
        // connect() lands on B, not A. Real `dispatch(Retry)` path
        // throughout — no direct runner.run() calls.
        val wsA = FakeWs()
        val wsB = FakeWs()
        val serverA = ServerInfo(
            serverId = "192.168.1.10:8188",
            host = "192.168.1.10",
            port = 8188,
            label = "A",
            lastConnectedAtEpochMs = 1L,
        )
        val serverB = ServerInfo(
            serverId = "192.168.1.20:8188",
            host = "192.168.1.20",
            port = 8188,
            label = "B",
            lastConnectedAtEpochMs = 2L,
        )
        val activeServer = ActiveServerHolder().also { it.setActive(serverA) }
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { _ -> http() },
            webSocketSourceFactory = { server ->
                when (server.serverId) {
                    serverA.serverId -> wsA
                    serverB.serverId -> wsB
                    else -> error("unexpected server $server")
                }
            },
            scope = this,
        )
        val reducer = ConnectionStateReducer(clientIdProvider = { "client-uuid" })
        val machine = ConnectionStateMachine(reducer, runner, this)
        machine.start()
        runCurrent()

        // Step 1: connect to A (initial Connected + Retry → opens A).
        machine.dispatch(ConnectionInput.Retry)
        runCurrent()
        assertEquals(1, wsA.connectCalls, "WS A should be opened on first Retry")
        assertEquals(0, wsB.connectCalls)

        // Step 2: user switches to server B. Coordinator dispatches
        // setActive(B) + Retry — same path.
        activeServer.setActive(serverB)
        machine.dispatch(ConnectionInput.Retry)
        runCurrent()

        // The runner's syncActiveServer cancelled A's wsJob (proven
        // indirectly by the fact that we still get only one connect
        // call on A — the same one as before — and connect on B).
        assertEquals(1, wsA.connectCalls, "WS A should not be reopened")
        assertEquals(1, wsB.connectCalls, "WS B should be opened after switch")
        assertEquals("client-uuid", wsB.lastClientId)
        // State stays Connected throughout.
        assertEquals(ConnectionState.Connected, machine.currentState.value)
        machine.stop()
    }

    @Test fun ws_drop_with_no_active_server_lands_in_Lost_NO_ACTIVE_SERVER() = runTest {
        // Per @Lily PR #19 review (`4413957569`) blocker 3:
        // NO_ACTIVE_SERVER must drive the state to Lost(NO_ACTIVE_SERVER)
        // — emitting on the runner's `errors` flow alone leaves the
        // state machine stuck in Reconnecting with no UI panel.
        //
        // Path: WS drop in Connected → Reconnecting + OpenWs side effect.
        // Runner sees null active server, dispatches
        // ConnectAttempt(NO_ACTIVE_SERVER). Reducer transitions to
        // Lost(NO_ACTIVE_SERVER).
        val ws = FakeWs()
        val activeServer = ActiveServerHolder() // empty — current.value == null
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { _ -> http() },
            webSocketSourceFactory = { _ -> ws },
            scope = this,
        )
        val reducer = ConnectionStateReducer(clientIdProvider = { "client-uuid" })
        val machine = ConnectionStateMachine(reducer, runner, this)
        machine.start()
        runCurrent()

        // Drop WS → Reconnecting + OpenWs. Runner sees null active
        // server → ConnectAttempt(NO_ACTIVE_SERVER) → Lost(...).
        machine.dispatch(ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE))
        runCurrent()

        val lost = assertIs<ConnectionState.Lost>(machine.currentState.value)
        assertEquals(ConnectError.NO_ACTIVE_SERVER, lost.error)
        machine.stop()
    }
}
