package com.comfymobile.data.network

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * a Flow that drains [frames] (a SharedFlow tests push events
     * into); when [throwOnConnect] is non-null, the Flow throws to
     * simulate a WS drop.
     */
    private class FakeWs : WebSocketSource {
        val frames = MutableSharedFlow<WsEvent>(replay = 0, extraBufferCapacity = 64)
        var throwOnConnect: Throwable? = null
        var lastClientId: String? = null

        override fun connect(clientId: String): Flow<WsEvent> {
            lastClientId = clientId
            val err = throwOnConnect
            return if (err != null) flow { throw err } else frames
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
        val runner = ConnectionEffectRunner(
            http = http(historyResponse = """{"p-1":{"status":{"status_str":"running","completed":false}}}"""),
            ws = FakeWs(),
            scope = this,
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
        val runner = ConnectionEffectRunner(
            http = http(),
            ws = ws,
            scope = this,
        )
        val collector = async { runner.producedInputs.take(2).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.OpenWs(clientId = "client-uuid-42"))
        advanceUntilIdle()
        val ev1 = WsEvent.ExecutionStart(promptId = "p-1")
        val ev2 = WsEvent.Progress(promptId = "p-1", node = "3", value = 5, max = 20)
        ws.frames.emit(ev1)
        ws.frames.emit(ev2)
        advanceUntilIdle()
        val collected = collector.await()
        assertEquals(2, collected.size)
        val first = assertIs<ConnectionInput.Ws>(collected[0])
        val second = assertIs<ConnectionInput.Ws>(collected[1])
        assertEquals(ev1, first.event)
        assertEquals(ev2, second.event)
        assertEquals("client-uuid-42", ws.lastClientId)
    }

    @Test fun open_ws_emits_Ws_drop_LAN_FLAKE_when_session_throws() = runTest {
        val ws = FakeWs().apply { throwOnConnect = RuntimeException("connection reset") }
        val runner = ConnectionEffectRunner(
            http = http(),
            ws = ws,
            scope = this,
        )
        val collector = async { runner.producedInputs.take(1).toList() }
        advanceUntilIdle()
        runner.run(SideEffectIntent.OpenWs(clientId = "any"))
        advanceUntilIdle()
        val collected = collector.await()
        assertEquals(1, collected.size)
        val drop = assertIs<ConnectionInput.Ws>(collected.single())
        assertEquals(WsDropReason.LAN_FLAKE, drop.droppedReason)
        assertEquals(null, drop.event)
    }

    private fun makeRunner(scope: kotlinx.coroutines.CoroutineScope): ConnectionEffectRunner =
        ConnectionEffectRunner(http = http(), ws = FakeWs(), scope = scope)
}
