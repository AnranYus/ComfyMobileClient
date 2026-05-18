package com.comfymobile.presentation.workflow

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.graph.GestureIntent
import com.comfymobile.presentation.graph.GestureState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function behaviour tests for [WorkflowGraphViewModel] per spec
 * §1.10. No Compose / no UI; verifies state transitions on:
 *
 *  - load() → loading → resolved envelope + parsed graph + layout
 *  - load() not-found → error message
 *  - load() API-format → error message, but envelope kept
 *  - long-press → pendingLongPress one-shot with monotonic seq
 *  - tap-on-already-selected → pendingTapReopen one-shot
 *  - setConnected() + isSubmittable → canRun derivation
 *  - onEnvelopeApplied() → graph rebuilt, gesture preserved
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowGraphViewModelTest {

    // ---------------------------------------------------------------- load

    @Test fun load_resolves_ui_format_envelope_into_parsed_graph_and_layout() = runTest {
        val envelope = uiEnvelope(twoNodeUiJson())
        val repo = FakeRepository(
            rows = mapOf(
                "wf-1" to WorkflowRow(
                    workflowId = "wf-1",
                    displayName = "Hello workflow",
                    envelope = envelope,
                    importedAtEpochMs = 100L,
                ),
            ),
        )
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = backgroundScope)
        vm.load("wf-1")
        // Wait for the launch-driven load to settle (per RunViewModelTest
        // pattern — `first { ... }` suspends until the predicate matches,
        // which is reliable under StandardTestDispatcher even when the
        // scope.launch is on backgroundScope).
        val s = vm.state.first { !it.isLoading }
        assertEquals("wf-1", s.workflowId)
        assertEquals("Hello workflow", s.displayName)
        assertEquals(envelope, s.envelope)
        assertNotNull(s.parsedGraph, "expected parsedGraph for UI-format envelope")
        assertNotNull(s.layoutResult, "expected layoutResult for UI-format envelope")
        assertFalse(s.isLoading)
        assertNull(s.errorMessage)
        assertTrue(s.firstFrameHintVisible, "first-frame hint should be on at first load")
    }

    @Test fun load_unknown_workflowId_sets_not_found_error() = runTest {
        val repo = FakeRepository(rows = emptyMap())
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = backgroundScope)
        vm.load("missing")
        val s = vm.state.first { !it.isLoading }
        assertNull(s.envelope)
        assertNull(s.parsedGraph)
        assertFalse(s.isLoading)
        assertEquals(WorkflowGraphCopy.workflowNotFound, s.errorMessage)
        assertFalse(s.firstFrameHintVisible, "not-found state should clear the first-frame hint")
    }

    @Test fun load_api_format_envelope_surfaces_unsupported_error_but_keeps_envelope() = runTest {
        val envelope = apiEnvelope()
        val repo = FakeRepository(
            rows = mapOf(
                "wf-api" to WorkflowRow(
                    workflowId = "wf-api",
                    displayName = "API-only workflow",
                    envelope = envelope,
                    importedAtEpochMs = 100L,
                ),
            ),
        )
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = backgroundScope)
        vm.load("wf-api")
        val s = vm.state.first { !it.isLoading }
        assertEquals(envelope, s.envelope, "envelope still set so other surfaces can read it")
        assertNull(s.parsedGraph, "no parsed graph for API-format envelope")
        assertNull(s.layoutResult)
        assertEquals(WorkflowGraphCopy.apiFormatUnsupported, s.errorMessage)
    }

    @Test fun loadFromEnvelope_skips_repository_and_renders_immediately() = runTest {
        val envelope = uiEnvelope(twoNodeUiJson())
        val repo = FakeRepository(rows = emptyMap())
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = backgroundScope)
        vm.loadFromEnvelope("wf-fresh", "Fresh import", envelope)
        // Synchronous path: no advanceUntilIdle needed because there's no coroutine.

        val s = vm.state.value
        assertEquals("Fresh import", s.displayName)
        assertNotNull(s.parsedGraph)
    }

    // ---------------------------------------------------------------- long-press wiring

    @Test fun long_press_on_node_emits_pending_long_press_with_monotonic_seq() = runTest {
        val vm = loaded(backgroundScope)
        // First press
        vm.onIntent(GestureIntent.LongPress(screenX = 10f, screenY = 10f, hitNodeId = "1"))
        val first = vm.state.value.pendingLongPress
        assertEquals("1", first?.nodeId)
        // Same node again WITHOUT consume — seq must advance so
        // LaunchedEffect re-fires.
        vm.onIntent(GestureIntent.LongPress(screenX = 10f, screenY = 10f, hitNodeId = "1"))
        val second = vm.state.value.pendingLongPress
        assertEquals("1", second?.nodeId)
        assertTrue(
            (second?.seq ?: 0L) > (first?.seq ?: 0L),
            "second long-press on the same node must produce a strictly greater seq; first=${first?.seq}, second=${second?.seq}",
        )
    }

    @Test fun long_press_with_no_hit_does_not_emit_pending_long_press() = runTest {
        val vm = loaded(backgroundScope)
        vm.onIntent(GestureIntent.LongPress(screenX = 10f, screenY = 10f, hitNodeId = null))
        assertNull(vm.state.value.pendingLongPress)
    }

    @Test fun consume_pending_long_press_clears_the_one_shot() = runTest {
        val vm = loaded(backgroundScope)
        vm.onIntent(GestureIntent.LongPress(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        assertNotNull(vm.state.value.pendingLongPress)
        vm.onConsumePendingLongPress()
        assertNull(vm.state.value.pendingLongPress)
    }

    // ---------------------------------------------------------------- tap-on-already-selected re-open

    @Test fun tap_on_already_selected_node_emits_pending_tap_reopen() = runTest {
        val vm = loaded(backgroundScope)
        // First tap selects node "1" but should NOT emit tap-reopen
        // (selection just changed).
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        assertNull(vm.state.value.pendingTapReopen, "first tap selects but doesn't reopen")
        assertEquals("1", vm.state.value.selectedNodeId)
        // Second tap on the same node IS the reopen trigger.
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        val reopen = vm.state.value.pendingTapReopen
        assertEquals("1", reopen?.nodeId)
    }

    @Test fun tap_on_different_node_does_not_reopen() = runTest {
        val vm = loaded(backgroundScope)
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "2"))
        assertNull(
            vm.state.value.pendingTapReopen,
            "tapping a different node must not trigger reopen (selection just moved)",
        )
        assertEquals("2", vm.state.value.selectedNodeId)
    }

    @Test fun tap_on_miss_clears_selection_and_does_not_reopen() = runTest {
        val vm = loaded(backgroundScope)
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = null))
        assertNull(vm.state.value.pendingTapReopen)
        assertNull(vm.state.value.selectedNodeId, "tap on miss clears selection")
    }

    // ---------------------------------------------------------------- canRun derivation

    @Test fun canRun_requires_connected_AND_submittable_envelope() = runTest {
        val vm = loaded(backgroundScope)
        // After load(), connected is false → canRun=false
        assertFalse(vm.state.value.canRun, "canRun must be false when not connected")
        vm.setConnected(true)
        assertTrue(
            vm.state.value.canRun,
            "canRun must be true when connected AND envelope is UI-format submittable",
        )
        vm.setConnected(false)
        assertFalse(vm.state.value.canRun, "canRun toggles back to false on disconnect")
    }

    @Test fun setConnected_before_load_does_not_enable_canRun() = runTest {
        val repo = FakeRepository(rows = emptyMap())
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = backgroundScope)
        vm.setConnected(true)
        assertFalse(
            vm.state.value.canRun,
            "canRun must stay false until an envelope is loaded — connection alone isn't enough",
        )
    }

    // ---------------------------------------------------------------- envelope updates from param editor

    @Test fun onEnvelopeApplied_rebuilds_parsed_graph_and_preserves_gesture_state() = runTest {
        val vm = loaded(backgroundScope)
        // Pan + select so we have non-trivial gesture state.
        vm.onIntent(GestureIntent.Tap(screenX = 0f, screenY = 0f, hitNodeId = "1"))
        vm.onIntent(GestureIntent.Pan(deltaScreenX = 50f, deltaScreenY = 30f))
        val beforeGesture = vm.state.value.gestureState
        val beforeParsed = vm.state.value.parsedGraph

        val newEnvelope = uiEnvelope(twoNodeUiJson(secondClassType = "ChangedClass"))
        vm.onEnvelopeApplied(newEnvelope)

        val s = vm.state.value
        assertEquals(newEnvelope, s.envelope)
        assertNotNull(s.parsedGraph)
        assertTrue(
            s.parsedGraph !== beforeParsed,
            "parsed graph object identity must change after onEnvelopeApplied (new graph derived)",
        )
        // Gesture state preserved (selection + pan untouched by envelope apply).
        assertEquals(beforeGesture, s.gestureState)
    }

    @Test fun dismiss_first_frame_hint_clears_visibility() = runTest {
        val vm = loaded(backgroundScope)
        assertTrue(vm.state.value.firstFrameHintVisible)
        vm.onDismissFirstFrameHint()
        assertFalse(vm.state.value.firstFrameHintVisible)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun loaded(
        scope: kotlinx.coroutines.CoroutineScope,
    ): WorkflowGraphViewModel {
        val envelope = uiEnvelope(twoNodeUiJson())
        val repo = FakeRepository(
            rows = mapOf(
                "wf-1" to WorkflowRow(
                    workflowId = "wf-1",
                    displayName = "Fixture",
                    envelope = envelope,
                    importedAtEpochMs = 100L,
                ),
            ),
        )
        val vm = WorkflowGraphViewModel(repository = repo, registry = emptyRegistry(), scope = scope)
        vm.load("wf-1")
        // Suspend until the load coroutine has populated parsedGraph.
        vm.state.first { it.parsedGraph != null }
        return vm
    }

    private fun twoNodeUiJson(
        secondClassType: String = "KSampler",
    ): JsonObject = buildJsonObject {
        put("nodes", buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1))
                put("type", JsonPrimitive("CheckpointLoaderSimple"))
                put("pos", buildJsonArray {
                    add(JsonPrimitive(40))
                    add(JsonPrimitive(40))
                })
                put("size", buildJsonArray {
                    add(JsonPrimitive(200))
                    add(JsonPrimitive(110))
                })
                put("outputs", buildJsonArray {
                    add(buildJsonObject {
                        put("name", JsonPrimitive("MODEL"))
                        put("type", JsonPrimitive("MODEL"))
                    })
                })
            })
            add(buildJsonObject {
                put("id", JsonPrimitive(2))
                put("type", JsonPrimitive(secondClassType))
                put("pos", buildJsonArray {
                    add(JsonPrimitive(320))
                    add(JsonPrimitive(40))
                })
                put("size", buildJsonArray {
                    add(JsonPrimitive(220))
                    add(JsonPrimitive(160))
                })
                put("inputs", buildJsonArray {
                    add(buildJsonObject {
                        put("name", JsonPrimitive("model"))
                        put("type", JsonPrimitive("MODEL"))
                        put("link", JsonPrimitive(1))
                    })
                })
            })
        })
        put("links", buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(1))
                add(JsonPrimitive(0))
                add(JsonPrimitive(2))
                add(JsonPrimitive(0))
                add(JsonPrimitive("MODEL"))
            })
        })
    }

    private fun uiEnvelope(json: JsonObject): WorkflowEnvelope = WorkflowEnvelope(
        original = json,
        format = WorkflowFormat.UI,
        metadata = WorkflowMetadata(
            label = "fixture",
            createdAtEpochMs = 0L,
            lastEditedAtEpochMs = 0L,
        ),
    )

    private fun apiEnvelope(): WorkflowEnvelope = WorkflowEnvelope(
        original = buildJsonObject {
            put("1", buildJsonObject {
                put("class_type", JsonPrimitive("CheckpointLoaderSimple"))
                put("inputs", buildJsonObject {})
            })
        },
        format = WorkflowFormat.API,
        metadata = WorkflowMetadata(
            label = "api fixture",
            createdAtEpochMs = 0L,
            lastEditedAtEpochMs = 0L,
        ),
    )

    /**
     * Tests don't exercise descriptor lookup directly — the VM
     * forwards the registry to the route's plan builder, which lives
     * in `WorkflowGraphRoute.kt` (covered by Compose previews + manual
     * verification). An empty registry suffices for state-transition
     * tests.
     */
    private fun emptyRegistry(): NodeDescriptorRegistry =
        NodeDescriptorRegistry.fromJson("""{"version":1,"descriptors":[]}""")

    private class FakeRepository(private val rows: Map<String, WorkflowRow>) : WorkflowRepository {
        override suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow =
            error("upsert not exercised here")

        override suspend fun getById(workflowId: String): WorkflowRow? = rows[workflowId]

        override fun observeAll(): Flow<List<WorkflowRow>> = MutableSharedFlow()

        override suspend fun listRecents(limit: Int): List<WorkflowRow> = rows.values.toList()

        override suspend fun markOpened(workflowId: String, openedAtEpochMs: Long): WorkflowRow? =
            rows[workflowId]

        override suspend fun delete(workflowId: String) { /* no-op for these tests */ }
    }
}
