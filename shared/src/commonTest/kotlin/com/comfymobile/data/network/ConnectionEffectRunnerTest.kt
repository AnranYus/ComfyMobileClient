package com.comfymobile.data.network

import com.comfymobile.data.connect.ActiveServerHolder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Drives [ConnectionEffectRunner] with `runTest`. Per @Lily seam
 * (msgs `e106ec46`, `14983d87`, `c64ab657`, `213b9cd9`):
 *
 *  1. The runner's scope is the same `TestScope` as the test (`scope =
 *     this`) so the runner's launches share the test's Job hierarchy
 *     and `advanceUntilIdle()` truly drains them.
 *
 *  2. Tests that expect N emissions use `async { producedInputs.take(N)
 *     .toList() }.await()` — `await()` blocks until N items have
 *     actually been collected, regardless of which dispatcher the
 *     producer ran on. This dodges the "advanceUntilIdle returned but
 *     the MockEngine reply hadn't reached the channel yet" race.
 *
 *  3. Tests that expect *no* emission run the silent intent first,
 *     then issue a sentinel timer; `await()` returns when the
 *     sentinel arrives, and we assert it's the only thing collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectRunnerTest {

    private val baseUrl = "http://192.168.1.10:8188"

    private fun http(historyResponse: String = """{"p-1":{"status":{"status_str":"success","completed":true}}}"""): ComfyHttpClient {
        val mock = HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel(historyResponse),
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

    /**
     * In-memory [WebSocketSource] used by tests. `connect()` returns
     * a Flow draining [frames]; when [throwOnConnect] is non-null,
     * the Flow throws to simulate a WS drop.
     *
     * Backed by a buffered [Channel] (instead of a `MutableSharedFlow`)
     * so frames pushed *before* the runner subscribes are NOT
     * dropped — they are buffered until the runner's
     * `connect().collect` starts draining. This matches a real WS
     * session where each frame is delivered exactly once.
     */
    private class FakeWs : WebSocketSource {
        val frames = Channel<WsEvent>(capacity = 64)
        var throwOnConnect: Throwable? = null
        var lastClientId: String? = null

        suspend fun pushFrame(event: WsEvent) {
            frames.send(event)
        }

        override fun connect(clientId: String): Flow<WsEvent> {
            lastClientId = clientId
            val err = throwOnConnect
            return if (err != null) flow { throw err } else frames.receiveAsFlow()
        }
    }

    @Test fun schedule_timer_emits_Timer_input_after_delay() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 5_000))
        advanceTimeBy(5_001)
        val collected = collector.await()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
    }

    @Test fun cancel_timer_prevents_emission() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectGiveUp, millis = 30_000))
        runner.run(SideEffectIntent.CancelTimer(TimerTick.ReconnectGiveUp))
        advanceTimeBy(31_000)
        // Sentinel: the cancelled timer must not fire; only this one should.
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_001)
        val collected = collector.await()
        assertEquals(1, collected.size)
        val timer = assertIs<ConnectionInput.Timer>(collected[0])
        assertEquals(TimerTick.ReconnectFallbackPoll, timer.tick)
    }

    @Test fun rescheduling_same_tick_replaces_previous_timer() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 30_000))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_500)
        val collected = collector.await()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
        // Original 30s timer must NOT fire; advance way past, no new collector
        // means we can't directly observe that here, but
        // `cancel_timer_prevents_emission` covers the negative case.
        advanceTimeBy(31_000)
    }

    @Test fun poll_history_with_completed_status_emits_Completed_result() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        val collected = collector.await()
        assertEquals(1, collected.size)
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals("p-1", probe.promptId)
        assertEquals(HistoryProbeResult.Completed, probe.result)
    }

    @Test fun poll_history_with_running_status_emits_Running_result() = runTest {
        val runner = makeRunner(
            scope = this,
            http = http(historyResponse = """{"p-1":{"status":{"status_str":"running","completed":false}}}"""),
        )
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        val collected = collector.await()
        assertEquals(1, collected.size)
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals(HistoryProbeResult.Running, probe.result)
    }

    @Test fun poll_active_history_fans_out_one_poll_per_tracked_prompt() = runTest {
        val runner = makeRunner(scope = this)
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        runner.trackInFlight("p-3")
        val collector = async { runner.producedInputs.take(3).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        val collected = collector.await()
        assertEquals(3, collected.size)
        val probedPromptIds = collected
            .map { assertIs<ConnectionInput.HistoryProbe>(it).promptId }
            .toSet()
        assertEquals(setOf("p-1", "p-2", "p-3"), probedPromptIds)
    }

    @Test fun poll_active_history_with_no_inflight_is_a_no_op() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        // No trackInFlight — empty in-flight set.
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        // Sentinel: the only emission should be a freshly-scheduled timer.
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1))
        advanceTimeBy(2)
        val collected = collector.await()
        assertEquals(1, collected.size)
        assertIs<ConnectionInput.Timer>(collected[0])
    }

    @Test fun emit_error_pushes_to_emitted_errors_flow() = runTest {
        val runner = makeRunner(scope = this)
        val collector = async { runner.emittedErrors.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.EmitError(ConnectError.NOT_COMFYUI))
        advanceUntilIdle()
        val errors = collector.await()
        assertEquals(listOf(ConnectError.NOT_COMFYUI), errors)
    }

    @Test fun emitted_errors_replay_delivers_value_to_late_subscriber() = runTest {
        // Regression: errorFlow uses replay = 1 (per @Lily PR #6
        // review msg `0e87febf`) so a UI subscribing AFTER the error
        // was already classified still sees it.
        val runner = makeRunner(scope = this)
        runner.run(SideEffectIntent.EmitError(ConnectError.WRONG_PORT_404))
        advanceUntilIdle()
        val collector = async { runner.emittedErrors.take(1).toList() }
        advanceUntilIdle()
        val errors = collector.await()
        assertEquals(listOf(ConnectError.WRONG_PORT_404), errors)
    }

    @Test fun untrack_inflight_updates_snapshot_synchronously() {
        // Synchronous test of the data structure — no coroutines, no
        // dispatcher hazards. Going through PollActiveHistory + Ktor
        // MockEngine on TestScope was racy (per @Lily msgs
        // `14983d87`, `c64ab657`, `213b9cd9`); this seam is the
        // deterministic alternative.
        val runner = makeRunner(scope = TestScope())
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        assertEquals(setOf("p-1", "p-2"), runner.snapshotInFlight())
        runner.untrackInFlight("p-1")
        assertEquals(setOf("p-2"), runner.snapshotInFlight())
        runner.untrackInFlight("p-2")
        assertEquals(emptySet(), runner.snapshotInFlight())
    }

    // ---------------------------------------------------------------- WS branch

    @Test fun open_ws_forwards_each_frame_as_Ws_input() = runTest {
        val ws = FakeWs()
        val runner = makeRunner(scope = this, ws = ws)
        try {
            val collector = async { runner.producedInputs.take(2).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "client-uuid-42"))
            advanceUntilIdle()
            val ev1 = WsEvent.ExecutionStart(promptId = "p-1")
            val ev2 = WsEvent.Progress(promptId = "p-1", node = "3", value = 5, max = 20)
            // Channel-backed pushFrame buffers if the runner has not
            // yet subscribed; no risk of frame loss.
            ws.pushFrame(ev1)
            ws.pushFrame(ev2)
            advanceUntilIdle()
            val collected = collector.await()
            assertEquals(2, collected.size)
            val first = assertIs<ConnectionInput.Ws>(collected[0])
            val second = assertIs<ConnectionInput.Ws>(collected[1])
            assertEquals(ev1, first.event)
            assertEquals(ev2, second.event)
            assertEquals("client-uuid-42", ws.lastClientId)
        } finally {
            // OpenWs starts a long-lived collect job on the runTest
            // scope; without explicit shutdown it would still be
            // alive when the test body returns and runTest would
            // fail with UncompletedCoroutinesError. (Per @Lily PR #6
            // review, msg `11700f67`.)
            runner.shutdown()
            ws.frames.close()
        }
    }

    @Test fun open_ws_emits_Ws_drop_LAN_FLAKE_when_session_throws() = runTest {
        val ws = FakeWs().apply { throwOnConnect = RuntimeException("connection reset") }
        val runner = makeRunner(scope = this, ws = ws)
        try {
            val collector = async { runner.producedInputs.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "any"))
            advanceUntilIdle()
            val collected = collector.await()
            assertEquals(1, collected.size)
            val drop = assertIs<ConnectionInput.Ws>(collected.single())
            assertEquals(WsDropReason.LAN_FLAKE, drop.droppedReason)
            assertEquals(null, drop.event)
        } finally {
            runner.shutdown()
            ws.frames.close()
        }
    }

    private fun makeRunner(
        scope: CoroutineScope,
        http: ComfyHttpClient = http(),
        ws: WebSocketSource = FakeWs(),
        activeServer: ActiveServerHolder = activeServerWith(SERVER_A),
    ): ConnectionEffectRunner = ConnectionEffectRunner(
        activeServer = activeServer,
        httpClientFactory = { _ -> http },
        webSocketSourceFactory = { _ -> ws },
        scope = scope,
    )

    private fun activeServerWith(server: ServerInfo): ActiveServerHolder =
        ActiveServerHolder().also { it.setActive(server) }

    // ---------------------------------------------------------------- active-server gating
    //
    // Per @Lily PR #18 thread (`60a7e64a`): the runner must NOT route
    // server-bound side effects to a default URL or the previously-
    // active server when no active server is selected. Instead it
    // emits ConnectError.NO_ACTIVE_SERVER on `emittedErrors` and
    // performs zero IO. UI maps this to @Ores's "Pick a server"
    // copy (PR #18 thread `b522a9f3`).

    @Test fun open_ws_with_no_active_server_emits_NO_ACTIVE_SERVER_and_does_no_IO() = runTest {
        val ws = FakeWs()
        val http = http()
        val noServer = ActiveServerHolder() // current.value == null
        val runner = makeRunner(scope = this, http = http, ws = ws, activeServer = noServer)
        try {
            val errorCollector = async { runner.emittedErrors.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "client-uuid-A"))
            val errors = errorCollector.await()
            assertEquals(listOf(ConnectError.NO_ACTIVE_SERVER), errors)
            // No WS connect was attempted: FakeWs.lastClientId stays null.
            assertEquals(null, ws.lastClientId)
        } finally {
            runner.shutdown()
            ws.frames.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun poll_history_with_no_active_server_emits_NO_ACTIVE_SERVER_and_does_no_IO() = runTest {
        val noServer = ActiveServerHolder()
        val runner = makeRunner(scope = this, activeServer = noServer)
        try {
            val errorCollector = async { runner.emittedErrors.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
            val errors = errorCollector.await()
            assertEquals(listOf(ConnectError.NO_ACTIVE_SERVER), errors)
        } finally {
            runner.shutdown()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun poll_active_history_with_no_active_server_emits_NO_ACTIVE_SERVER() = runTest {
        val noServer = ActiveServerHolder()
        val runner = makeRunner(scope = this, activeServer = noServer)
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        try {
            val errorCollector = async { runner.emittedErrors.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.PollActiveHistory)
            val errors = errorCollector.await()
            assertEquals(listOf(ConnectError.NO_ACTIVE_SERVER), errors)
        } finally {
            runner.shutdown()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun timer_intents_run_unconditionally_even_without_active_server() = runTest {
        // Timers are reconnect-protocol cadence, NOT server-bound IO,
        // so they must continue to fire even before an active server
        // is selected. Otherwise the state machine could be wedged in
        // Reconnecting waiting for a give-up timer that never arrives.
        val noServer = ActiveServerHolder()
        val runner = makeRunner(scope = this, activeServer = noServer)
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 100))
        advanceTimeBy(150)
        val collected = collector.await()
        assertEquals(1, collected.size)
        assertIs<ConnectionInput.Timer>(collected[0])
    }

    @Test fun setting_active_server_after_no_active_server_allows_subsequent_open_ws() = runTest {
        val ws = FakeWs()
        val activeServer = ActiveServerHolder() // null at first
        val runner = makeRunner(scope = this, ws = ws, activeServer = activeServer)
        try {
            // First attempt: no active server → NO_ACTIVE_SERVER, no IO.
            val errorCollector = async { runner.emittedErrors.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "early"))
            val firstErrors = errorCollector.await()
            assertEquals(listOf(ConnectError.NO_ACTIVE_SERVER), firstErrors)
            assertEquals(null, ws.lastClientId)

            // Now set active server. Subsequent OpenWs should connect.
            activeServer.setActive(SERVER_A)
            advanceUntilIdle()

            val frameCollector = async { runner.producedInputs.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "client-after-set"))
            advanceUntilIdle()
            ws.pushFrame(WsEvent.Status(queueRemaining = 0))
            advanceUntilIdle()
            val frames = frameCollector.await()
            assertEquals(1, frames.size)
            assertEquals("client-after-set", ws.lastClientId)
        } finally {
            runner.shutdown()
            ws.frames.close()
            coroutineContext.cancelChildren()
        }
    }

    // ---------------------------------------------------------------- active-server change
    //
    // Per @Lily PR #18 thread (`60a7e64a`): when the user switches
    // server, the runner must cancel in-flight server-bound IO so a
    // half-open WS or pending poll never resolves against the old
    // baseUrl, and subsequent intents target the NEW server.

    @Test fun switching_active_server_cancels_in_flight_ws() = runTest {
        // Different ws sources per server so we can prove the second
        // OpenWs picked the new server's WebSocketSource.
        val wsA = FakeWs()
        val wsB = FakeWs()
        val activeServer = activeServerWith(SERVER_A)
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { _ -> http() },
            webSocketSourceFactory = { server ->
                when (server.serverId) {
                    SERVER_A.serverId -> wsA
                    SERVER_B.serverId -> wsB
                    else -> error("unexpected server $server")
                }
            },
            scope = this,
        )
        try {
            // Open WS against A.
            runner.run(SideEffectIntent.OpenWs(clientId = "client-A"))
            advanceUntilIdle()
            assertEquals("client-A", wsA.lastClientId)

            // Switch to B; runner observer cancels A's WS job.
            activeServer.setActive(SERVER_B)
            advanceUntilIdle()

            // Now OpenWs against B. wsB's lastClientId will reflect it.
            runner.run(SideEffectIntent.OpenWs(clientId = "client-B"))
            advanceUntilIdle()
            assertEquals("client-B", wsB.lastClientId)
        } finally {
            runner.shutdown()
            wsA.frames.close()
            wsB.frames.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun switching_active_server_to_null_cancels_in_flight_ws() = runTest {
        val ws = FakeWs()
        val activeServer = activeServerWith(SERVER_A)
        val runner = makeRunner(scope = this, ws = ws, activeServer = activeServer)
        try {
            runner.run(SideEffectIntent.OpenWs(clientId = "client-A"))
            advanceUntilIdle()
            assertEquals("client-A", ws.lastClientId)

            // Active server cleared (e.g. user disconnected). Observer
            // should cancel the WS.
            activeServer.clear()
            advanceUntilIdle()

            // Subsequent OpenWs hits NO_ACTIVE_SERVER.
            val errorCollector = async { runner.emittedErrors.take(1).toList() }
            advanceUntilIdle()
            runner.run(SideEffectIntent.OpenWs(clientId = "client-after-clear"))
            val errors = errorCollector.await()
            assertEquals(listOf(ConnectError.NO_ACTIVE_SERVER), errors)
        } finally {
            runner.shutdown()
            ws.frames.close()
            coroutineContext.cancelChildren()
        }
    }

    @Test fun switching_active_server_uses_new_baseUrl_for_subsequent_polls() = runTest {
        // Verify the http factory is called with the new server, not
        // the original one. We track factory invocations.
        val invocations = mutableListOf<ServerInfo>()
        val activeServer = activeServerWith(SERVER_A)
        val runner = ConnectionEffectRunner(
            activeServer = activeServer,
            httpClientFactory = { server ->
                invocations += server
                http()
            },
            webSocketSourceFactory = { _ -> FakeWs() },
            scope = this,
        )
        try {
            runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
            advanceUntilIdle()
            // Switch server.
            activeServer.setActive(SERVER_B)
            advanceUntilIdle()
            runner.run(SideEffectIntent.PollHistory(promptId = "p-2"))
            advanceUntilIdle()
            // First poll resolved against A, second against B.
            assertTrue(invocations.any { it.serverId == SERVER_A.serverId })
            assertTrue(invocations.any { it.serverId == SERVER_B.serverId })
        } finally {
            runner.shutdown()
            coroutineContext.cancelChildren()
        }
    }

    private companion object {
        val SERVER_A = ServerInfo(
            serverId = "192.168.1.10:8188",
            host = "192.168.1.10",
            port = 8188,
            label = "A",
            lastConnectedAtEpochMs = 0L,
        )
        val SERVER_B = ServerInfo(
            serverId = "192.168.1.20:8188",
            host = "192.168.1.20",
            port = 8188,
            label = "B",
            lastConnectedAtEpochMs = 0L,
        )
    }
}
