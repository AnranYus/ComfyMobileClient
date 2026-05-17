package com.comfymobile.data.run

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.data.network.dto.HistoryEntryDto
import com.comfymobile.data.network.dto.HistoryStatusDto
import com.comfymobile.domain.run.ActiveRunContext
import com.comfymobile.domain.run.Clock
import com.comfymobile.domain.run.ReconciledOutcome
import com.comfymobile.domain.run.RunError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Behavioral tests for [RunReconciler]. All collaborators are fakes
 * implementing the public seams; no Ktor, no real coordinator.
 *
 * The reducer / coordinator behavior reached via
 * `RunCoordinator.applyReconciledTerminal` is exercised in
 * `RunCoordinatorTest`; here we test the reconciler's own state
 * machine: when probes fire, what's probed, and how outcomes settle
 * into JobRepository.
 */
class RunReconcilerTest {

    // ----------------------------------------------------------------- fakes

    private class RecordingHistoryProbe : HistoryProbePort {
        val calls = mutableListOf<Pair<String, String>>()
        var response: HistoryEntryDto? = null
        var throwOnNextCall: Throwable? = null

        override suspend fun getHistoryEntry(baseUrl: String, promptId: String): HistoryEntryDto? {
            calls += baseUrl to promptId
            throwOnNextCall?.let {
                throwOnNextCall = null
                throw it
            }
            return response
        }
    }

    /** Recorder for the `applyReconciledTerminal` callback. */
    private class Applier {
        val calls = mutableListOf<Pair<String, ReconciledOutcome>>()
        var returns: Boolean = true
        suspend fun apply(promptId: String, outcome: ReconciledOutcome): Boolean {
            calls += promptId to outcome
            return returns
        }
    }

    /** Wall-clock monotonically advancing by `tickMs` per read. */
    private class FakeClock(
        startMs: Long = 1_700_000_000_000L,
        private val tickMs: Long = 100L,
    ) : Clock {
        private var now = startMs
        override fun nowEpochMs(): Long {
            val v = now
            now += tickMs
            return v
        }
    }

    private val testContext = ActiveRunContext(
        promptId = "p-1",
        baseUrl = "http://srv:8188",
        serverId = "srv:8188",
    )

    /**
     * Build a reconciler with the given fakes plus an InMemoryJobRepository
     * seeded with the active run's prompt so updateStatus has something
     * to write into.
     */
    private fun reconciler(
        activeRun: ActiveRunContext? = testContext,
        connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected),
        probe: RecordingHistoryProbe = RecordingHistoryProbe(),
        applier: Applier = Applier(),
        clock: Clock = FakeClock(),
        bBranchPollPeriodMs: Long = 50,
        bBranchTotalTimeoutMs: Long = 200,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Setup {
        val activeFlow = MutableStateFlow(activeRun)
        val r = RunReconciler(
            activeRunContext = activeFlow,
            applyReconciledTerminal = applier::apply,
            connectionState = connectionState,
            historyProbe = probe,
            clock = clock,
            scope = scope,
            bBranchPollPeriodMs = bBranchPollPeriodMs,
            bBranchTotalTimeoutMs = bBranchTotalTimeoutMs,
        )
        return Setup(r, connectionState, probe, applier)
    }

    private data class Setup(
        val reconciler: RunReconciler,
        val connectionState: MutableStateFlow<ConnectionState>,
        val probe: RecordingHistoryProbe,
        val applier: Applier,
    )

    private fun historyEntryWithStatus(
        completed: Boolean,
        statusStr: String?,
        outputs: JsonElement? = null,
    ): HistoryEntryDto = HistoryEntryDto(
        prompt = null,
        outputs = outputs,
        status = HistoryStatusDto(status_str = statusStr, completed = completed),
    )

    private fun imageOutputs(vararg filenames: String): JsonElement = buildJsonObject {
        put("node_save", buildJsonObject {
            put("images", buildJsonArray {
                for (name in filenames) {
                    add(buildJsonObject {
                        put("filename", JsonPrimitive(name))
                        put("subfolder", JsonPrimitive(""))
                        put("type", JsonPrimitive("output"))
                    })
                }
            })
        })
    }

    // ----------------------------------------------------------------- C-branch single probe

    @Test fun BACKGROUND_RESUMED_triggers_one_shot_probe() = runTest {
        val (r, conn, probe, applier) = reconciler(
            scope = backgroundScope,
        )
        probe.response = historyEntryWithStatus(
            completed = true,
            statusStr = "success",
            outputs = imageOutputs("img_001.png"),
        )

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        // Wait for the probe + apply to land.
        withTimeout(500) {
            while (probe.calls.isEmpty() || applier.calls.isEmpty()) {
                kotlinx.coroutines.delay(5)
            }
        }
        assertEquals(listOf("http://srv:8188" to "p-1"), probe.calls)
        val (promptId, outcome) = applier.calls.single()
        assertEquals("p-1", promptId)
        val succeeded = assertIs<ReconciledOutcome.Succeeded>(outcome)
        assertEquals(1, succeeded.outputs.size)
        assertEquals("img_001.png", succeeded.outputs[0].filename)

        // Persistence is the coordinator's responsibility now (per
        // @Lily PR #34 review msg `4996df44` blocker 2). End-to-end
        // persistence flow is verified in RunCoordinator integration
        // tests via finalizeFromSnapshot.

        r.stop()
    }

    @Test fun completed_error_status_applies_Failed_outcome() = runTest {
        val (r, conn, probe, applier) = reconciler(scope = backgroundScope)
        probe.response = historyEntryWithStatus(completed = true, statusStr = "error")

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        withTimeout(500) {
            while (applier.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        assertIs<ReconciledOutcome.Failed>(applier.calls.single().second)

        r.stop()
    }

    @Test fun completed_interrupted_status_applies_Interrupted_outcome() = runTest {
        val (r, conn, probe, applier) = reconciler(scope = backgroundScope)
        probe.response = historyEntryWithStatus(completed = true, statusStr = "interrupted")

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        withTimeout(500) {
            while (applier.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        assertEquals(ReconciledOutcome.Interrupted, applier.calls.single().second)

        r.stop()
    }

    @Test fun completed_with_null_status_str_defaults_to_Succeeded() = runTest {
        val (r, conn, probe, applier) = reconciler(scope = backgroundScope)
        probe.response = historyEntryWithStatus(completed = true, statusStr = null)

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        withTimeout(500) {
            while (applier.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        assertIs<ReconciledOutcome.Succeeded>(applier.calls.single().second)

        r.stop()
    }

    @Test fun history_entry_not_found_applies_Failed_with_Network_error() = runTest {
        val (r, conn, probe, applier) = reconciler(scope = backgroundScope)
        probe.response = null // server has no entry

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        withTimeout(500) {
            while (applier.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        val outcome = assertIs<ReconciledOutcome.Failed>(applier.calls.single().second)
        assertIs<IllegalStateException>(outcome.error.let { (it as RunError.Network).cause })

        r.stop()
    }

    // ----------------------------------------------------------------- still running → no apply

    @Test fun still_running_does_not_apply_or_settle() = runTest {
        val (r, conn, probe, applier) = reconciler(scope = backgroundScope)
        probe.response = historyEntryWithStatus(completed = false, statusStr = null)

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)

        // Wait long enough for the probe; verify no apply.
        withTimeout(500) {
            while (probe.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        // Apply should never happen.
        kotlinx.coroutines.delay(100)
        assertTrue(applier.calls.isEmpty())

        r.stop()
    }

    // ----------------------------------------------------------------- B-branch polling

    @Test fun LAN_FLAKE_polls_until_completed_then_stops() = runTest {
        val (r, conn, probe, applier) = reconciler(
            scope = backgroundScope,
            bBranchPollPeriodMs = 30,
            bBranchTotalTimeoutMs = 500,
        )
        // First two probes return still-running, then completed.
        var nProbes = 0
        val customProbe = object : HistoryProbePort {
            override suspend fun getHistoryEntry(baseUrl: String, promptId: String): HistoryEntryDto? {
                nProbes += 1
                return if (nProbes < 3) {
                    historyEntryWithStatus(completed = false, statusStr = null)
                } else {
                    historyEntryWithStatus(
                        completed = true,
                        statusStr = "success",
                        outputs = imageOutputs("late.png"),
                    )
                }
            }
        }
        // Rebuild reconciler with the custom probe.
        val applier2 = Applier()
        val r2 = RunReconciler(
            activeRunContext = MutableStateFlow(testContext),
            applyReconciledTerminal = applier2::apply,
            connectionState = conn,
            historyProbe = customProbe,
            clock = FakeClock(),
            scope = backgroundScope,
            bBranchPollPeriodMs = 30,
            bBranchTotalTimeoutMs = 500,
        )

        r2.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)

        withTimeout(2_000) {
            while (applier2.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        assertTrue(nProbes >= 3, "expected ≥3 polls before terminal, got $nProbes")
        assertIs<ReconciledOutcome.Succeeded>(applier2.calls.single().second)

        r2.stop()
    }

    // ----------------------------------------------------------------- cancel on connected/lost

    @Test fun Connected_cancels_in_flight_probe() = runTest {
        val (r, conn, _, applier) = reconciler(
            scope = backgroundScope,
            bBranchPollPeriodMs = 30,
            bBranchTotalTimeoutMs = 500,
        )
        // probe.response defaults to null → reconciler will apply Failed each poll.
        // But we want to cancel BEFORE applying, so use a probe that hangs.
        val hangingProbe = object : HistoryProbePort {
            val started = CompletableDeferred<Unit>()
            override suspend fun getHistoryEntry(baseUrl: String, promptId: String): HistoryEntryDto? {
                started.complete(Unit)
                kotlinx.coroutines.delay(10_000) // hangs
                return null
            }
        }
        val applier2 = Applier()
        val r2 = RunReconciler(
            activeRunContext = MutableStateFlow(testContext),
            applyReconciledTerminal = applier2::apply,
            connectionState = conn,
            historyProbe = hangingProbe,
            clock = FakeClock(),
            scope = backgroundScope,
            bBranchPollPeriodMs = 30,
            bBranchTotalTimeoutMs = 500,
        )

        r2.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)

        // Wait for the probe to start (so we know an in-flight probe exists to cancel).
        withTimeout(500) { hangingProbe.started.await() }
        // Now switch to Connected — should cancel the probe.
        conn.value = ConnectionState.Connected
        kotlinx.coroutines.delay(100)
        assertTrue(applier2.calls.isEmpty(), "Connected should have cancelled the probe before apply")

        r2.stop()
    }

    @Test fun Lost_cancels_in_flight_probe() = runTest {
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val hangingProbe = object : HistoryProbePort {
            val started = CompletableDeferred<Unit>()
            override suspend fun getHistoryEntry(baseUrl: String, promptId: String): HistoryEntryDto? {
                started.complete(Unit)
                kotlinx.coroutines.delay(10_000)
                return null
            }
        }
        val applier = Applier()
        val r = RunReconciler(
            activeRunContext = MutableStateFlow(testContext),
            applyReconciledTerminal = applier::apply,
            connectionState = conn,
            historyProbe = hangingProbe,
            clock = FakeClock(),
            scope = backgroundScope,
        )
        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        withTimeout(500) { hangingProbe.started.await() }
        conn.value = ConnectionState.Lost(ConnectError.REFUSED)
        kotlinx.coroutines.delay(100)
        assertTrue(applier.calls.isEmpty())
        r.stop()
    }

    // ----------------------------------------------------------------- no active run

    @Test fun no_active_run_skips_probe_entirely() = runTest {
        val (r, conn, probe, applier) = reconciler(
            activeRun = null,
            scope = backgroundScope,
        )

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)
        kotlinx.coroutines.delay(100)
        assertTrue(probe.calls.isEmpty(), "no active run → no probe")
        assertTrue(applier.calls.isEmpty())
        r.stop()
    }

    // ----------------------------------------------------------------- probe targets correct server

    @Test fun probe_uses_run_baseUrl_not_global_active_server() = runTest {
        val customContext = ActiveRunContext(
            promptId = "p-9",
            baseUrl = "http://server-bound-to-run:8188",
            serverId = "server-bound-to-run:8188",
        )
        val (r, conn, probe, applier) = reconciler(
            activeRun = customContext,
            scope = backgroundScope,
        )
        probe.response = historyEntryWithStatus(completed = true, statusStr = "success")

        r.start()
        conn.value = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)
        withTimeout(500) {
            while (applier.calls.isEmpty()) kotlinx.coroutines.delay(5)
        }
        assertEquals(
            listOf("http://server-bound-to-run:8188" to "p-9"),
            probe.calls,
        )
        r.stop()
    }

    // ----------------------------------------------------------------- start/stop idempotency

    @Test fun double_start_is_safe() = runTest {
        val (r, _, _, _) = reconciler(scope = backgroundScope)
        r.start()
        r.start() // no-op
        r.stop()
    }
}
