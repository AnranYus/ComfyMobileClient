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
 * timer ticks resolve deterministically. WS / HTTP collaborators are
 * stubbed; assertions look at what
 * [ConnectionEffectRunner.producedInputs] emits.
 *
 * Per @Lily seam (msg `e106ec46`), focus areas: WS frame → input,
 * timer cancel, PollActiveHistory fan-out, WS drop classification.
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
     * In-memory [WebSocketSource] used by tests. Each `connect()`
     * call returns a Flow that drains [frames] (a SharedFlow tests
     * push events into). When [throwOnConnect] is non-null, the Flow
     * emits then throws to simulate a WS drop.
     */
    private class FakeWs : WebSocketSource {
        val frames = MutableSharedFlow<WsEvent>(replay = 0, extraBufferCapacity = 64)
        var throwOnConnect: Throwable? = null
        var lastClientId: String? = null

        override fun connect(clientId: String): Flow<WsEvent> {
            lastClientId = clientId
            val err = throwOnConnect
            return if (err != null) {
                flow { throw err }
            } else {
                frames
            }
        }
    }

    @Test fun schedule_timer_emits_Timer_input_after_delay() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 5_000))
        advanceTimeBy(4_999)
        val collected = mutableListOf<ConnectionInput>()
        val job = launch {
            runner.producedInputs.take(1).collect { collected += it }
        }
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
        job.cancel()
    }

    @Test fun cancel_timer_prevents_emission() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectGiveUp, millis = 30_000))
        runner.run(SideEffectIntent.CancelTimer(TimerTick.ReconnectGiveUp))
        advanceTimeBy(31_000)
        advanceUntilIdle()
        // No input was sent; verify by scheduling a different timer
        // and checking it's the first emission.
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_001)
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val timer = assertIs<ConnectionInput.Timer>(collected[0])
        assertEquals(TimerTick.ReconnectFallbackPoll, timer.tick)
        job.cancel()
    }

    @Test fun rescheduling_same_tick_replaces_previous_timer() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 30_000))
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1_000))
        advanceTimeBy(1_500)
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertEquals(ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll), collected[0])
        // Original 30s timer must NOT fire — advance way past.
        advanceTimeBy(31_000)
        advanceUntilIdle()
        // No additional emission expected.
        job.cancel()
    }

    @Test fun poll_history_with_completed_status_emits_Completed_result() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals("p-1", probe.promptId)
        assertEquals(HistoryProbeResult.Completed, probe.result)
        job.cancel()
    }

    @Test fun poll_history_with_running_status_emits_Running_result() = runTest {
        val runner = ConnectionEffectRunner(
            http = http(historyResponse = """{"p-1":{"status":{"status_str":"running","completed":false}}}"""),
            ws = FakeWs(),
            scope = TestScope(testScheduler),
        )
        runner.run(SideEffectIntent.PollHistory(promptId = "p-1"))
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals(HistoryProbeResult.Running, probe.result)
        job.cancel()
    }

    @Test fun poll_active_history_fans_out_one_poll_per_tracked_prompt() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        runner.trackInFlight("p-3")
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(3).collect { collected += it } }
        advanceUntilIdle()
        assertEquals(3, collected.size)
        val probedPromptIds = collected
            .map { assertIs<ConnectionInput.HistoryProbe>(it).promptId }
            .toSet()
        assertEquals(setOf("p-1", "p-2", "p-3"), probedPromptIds)
        job.cancel()
    }

    @Test fun poll_active_history_with_no_inflight_is_a_no_op() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        runner.run(SideEffectIntent.ScheduleTimer(TimerTick.ReconnectFallbackPoll, millis = 1))
        advanceTimeBy(2)
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        assertEquals(1, collected.size)
        assertIs<ConnectionInput.Timer>(collected[0])
        job.cancel()
    }

    @Test fun emit_error_pushes_to_emitted_errors_flow() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        val errors = mutableListOf<ConnectError>()
        val job = launch { runner.emittedErrors.take(1).collect { errors += it } }
        runner.run(SideEffectIntent.EmitError(ConnectError.NOT_COMFYUI))
        advanceUntilIdle()
        assertEquals(listOf(ConnectError.NOT_COMFYUI), errors)
        job.cancel()
    }

    @Test fun untrack_inflight_removes_prompt_from_fanout() = runTest {
        val runner = makeRunner(scope = TestScope(testScheduler))
        runner.trackInFlight("p-1")
        runner.trackInFlight("p-2")
        runner.untrackInFlight("p-1")
        runner.run(SideEffectIntent.PollActiveHistory)
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch { runner.producedInputs.take(1).collect { collected += it } }
        advanceUntilIdle()
        assertEquals(1, collected.size)
        val probe = assertIs<ConnectionInput.HistoryProbe>(collected.single())
        assertEquals("p-2", probe.promptId)
        job.cancel()
    }

    // ---------------------------------------------------------------- WS branch

    @Test fun open_ws_forwards_each_frame_as_Ws_input() = runTest {
        val ws = FakeWs()
        val runner = ConnectionEffectRunner(
            http = http(),
            ws = ws,
            scope = TestScope(testScheduler),
        )
        runner.run(SideEffectIntent.OpenWs(clientId = "client-uuid-42"))
        advanceUntilIdle()
        // Push two frames through the fake source.
        val ev1 = WsEvent.ExecutionStart(promptId = "p-1")
        val ev2 = WsEvent.Progress(promptId = "p-1", node = "3", value = 5, max = 20)
        val collected = mutableListOf<ConnectionInput>()
        val collectJob = launch {
            runner.producedInputs.take(2).collect { collected += it }
        }
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
            scope = TestScope(testScheduler),
        )
        runner.run(SideEffectIntent.OpenWs(clientId = "any"))
        advanceUntilIdle()
        val collected = mutableListOf<ConnectionInput>()
        val job = launch {
            runner.producedInputs.take(1).collect { collected += it }
        }
        advanceUntilIdle()
        val drop = assertIs<ConnectionInput.Ws>(collected.single())
        assertEquals(WsDropReason.LAN_FLAKE, drop.droppedReason)
        assertEquals(null, drop.event)
        job.cancel()
    }

    private fun makeRunner(scope: TestScope): ConnectionEffectRunner =
        ConnectionEffectRunner(http = http(), ws = FakeWs(), scope = scope)
}
