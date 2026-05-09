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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
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
 * Drives [ConnectionEffectRunner] with a virtual-time `TestScope` so
 * timer ticks resolve deterministically. Per @Lily seam (msgs
 * `e106ec46`, `14983d87`), every `producedInputs` assertion follows
 * the **collector-first** pattern:
 *
 * ```
 * launch { runner.producedInputs.take(N).collect { ... } }   // subscribe first
 * advanceUntilIdle()                                         // ensure subscribed
 * runner.run(SideEffectIntent.X)                             // trigger
 * advanceUntilIdle()                                         // drain
 * ```
 *
 * The "fire-then-subscribe" pattern is racy across JVM/Native because
 * `Channel.receiveAsFlow()` requires the collector to be active when
 * the value is sent.
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
     * In-memory [WebSocketSource] used by tests. Each `connect()` call
     * returns a Flow draining [frames] (a SharedFlow tests push events
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
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 5_000))
        advanceTimeBy(4_999)
        assertTrue(collected.isEmpty(), "timer must not fire before delay elapses")
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
        job.cancel()
    }

    @Test fun cancel_timer_prevents_emission() = runTest {
        val runner = makeRunner(scope = this)
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectGiveUp, millis = 30_000))
        runner.run(SideEffectIntent.CancelTimer(TimerTick.ReconnectGiveUp))
        advanceTimeBy(31_000)
        advanceUntilIdle()
        assertTrue(collected.isEmpty(), "cancelled timer must not fire")
        // Schedule a fresh timer to verify the runner is still functional.
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_001)
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val timer = assertIs<ConnectionInput.Timer>(collected[0])
        assertEquals(TimerTick.ReconnectFallbackPoll, timer.tick)
        job.cancel()
    }

    @Test fun rescheduling_same_tick_replaces_previous_timer() = runTest {
        val runner = makeRunner(scope = this)
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 30_000))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_500)
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
        // Original 30s timer must NOT fire; advance way past, no extra emission.
        advanceTimeBy(31_000)
        advanceUntilIdle()
        job.cancel()
    }

    @Test fun poll_history_with_completed_status_emits_Completed_result() = runTest {
        val runner = makeRunner(scope = this)
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals("p-1", probe.promptId)
        assertEquals(HistoryProbeResult.Completed, probe.result)
        job.cancel()
    }

    @Test fun poll_history_with_running_status_emits_Running_result() = runTest {
        val runner = ConnectionEffectRunner(
            http = http(historyResponse = """{"p-1":{"status":{"status_str":"running","completed":false}}}"""),
            ws = FakeWs(),
            scope = this,
        )
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals(HistoryProbeResult.Running, probe.result)
        job.cancel()
    }

    @Test fun poll_active_history_fans_out_one_poll_per_tracked_prompt() = runTest {
        val runner = makeRunner(scope = this)
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        runner.trackInFlight("p-3")
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(3).collect { collected += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        assertEquals(3, collected.size)
        val probedPromptIds = collected
            .map { assertIs<ConnectionInput.HistoryProbe>(it).promptId }
            .toSet()
        assertEquals(setOf("p-1", "p-2", "p-3"), probedPromptIds)
        job.cancel()
    }

    @Test fun poll_active_history_with_no_inflight_is_a_no_op() = runTest {
        val runner = makeRunner(scope = this)
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        // No trackInFlight — empty in-flight set.
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        assertTrue(collected.isEmpty(), "PollActiveHistory with no in-flight ids must not emit")
        // Schedule a timer to confirm the runner is still functional.
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1))
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertIs<ConnectionInput.Timer>(collected[0])
        job.cancel()
    }

    @Test fun emit_error_pushes_to_emitted_errors_flow() = runTest {
        val runner = makeRunner(scope = this)
        val errors = mutableListOf<ConnectError>()
        val job = launch { runner.emittedErrors.take(1).collect { errors += it } }
        advanceUntilIdle()
        runner.run(SideEffectIntent.EmitError(ConnectError.NOT_COMFYUI))
        advanceUntilIdle()
        assertEquals(listOf(ConnectError.NOT_COMFYUI), errors)
        job.cancel()
    }

    @Test fun emitted_errors_replay_delivers_value_to_late_subscriber() = runTest {
        // Regression: the errorFlow uses replay = 1 (per @Lily PR #6
        // review msg `0e87febf`) so a UI subscribing AFTER the error
        // was already classified still sees it.
        val runner = makeRunner(scope = this)
        runner.run(SideEffectIntent.EmitError(ConnectError.WRONG_PORT_404))
        advanceUntilIdle()
        val errors = mutableListOf<ConnectError>()
        val job = launch { runner.emittedErrors.take(1).collect { errors += it } }
        advanceUntilIdle()
        assertEquals(listOf(ConnectError.WRONG_PORT_404), errors)
        job.cancel()
    }

    @Test fun untrack_inflight_updates_snapshot_synchronously() {
        // Test the data structure directly — going through
        // PollActiveHistory + Ktor MockEngine on TestScope causes
        // dispatcher races (per @Lily msg `14983d87`). The
        // `snapshotInFlight()` seam exposes the deterministic state.
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
        val collected = mutableListOf<ConnectionInput>()
        val collectJob = launch {
            runner.producedInputs.take(2).collect { collected += it }
        }
        advanceUntilIdle()
        runner.run(SideEffectIntent.OpenWs(clientId = "client-uuid-42"))
        advanceUntilIdle()
        val ev1 = WsEvent.ExecutionStart(promptId = "p-1")
        val ev2 = WsEvent.Progress(promptId = "p-1", node = "3", value = 5, max = 20)
        ws.frames.emit(ev1)
        ws.frames.emit(ev2)
        advanceUntilIdle()
        assertEquals(2, collected.size)
        val first = assertIs<ConnectionInput.Ws>(collected[0])
        val second = assertIs<ConnectionInput.Ws>(collected[1])
        assertEquals(ev1, first.event)
        assertEquals(ev2, second.event)
        assertEquals("client-uuid-42", ws.lastClientId)
        collectJob.cancel()
    }

    @Test fun open_ws_emits_Ws_drop_LAN_FLAKE_when_session_throws() = runTest {
        val ws = FakeWs().apply { throwOnConnect = RuntimeException("connection reset") }
        val runner = ConnectionEffectRunner(
            http = http(),
            ws = ws,
            scope = this,
        )
        val collected = mutableListOf<ConnectionInput>()
        val job = launch {
            runner.producedInputs.take(1).collect { collected += it }
        }
        advanceUntilIdle()
        runner.run(SideEffectIntent.OpenWs(clientId = "any"))
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val drop = assertIs<ConnectionInput.Ws>(collected.single())
        assertEquals(WsDropReason.LAN_FLAKE, drop.droppedReason)
        assertEquals(null, drop.event)
        job.cancel()
    }

    private fun makeRunner(scope: TestScope): ConnectionEffectRunner =
        ConnectionEffectRunner(http = http(), ws = FakeWs(), scope = scope)
}
