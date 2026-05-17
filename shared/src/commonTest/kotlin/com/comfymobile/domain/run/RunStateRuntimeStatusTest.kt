package com.comfymobile.domain.run

import com.comfymobile.presentation.graph.NodeRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull

class RunStateRuntimeStatusTest {

    @Test fun idle_state_maps_to_empty() {
        assertEquals(emptyMap(), RunState.Idle.toRuntimeStatusMap())
    }

    @Test fun submitting_state_maps_to_empty() {
        assertEquals(emptyMap(), RunState.Submitting.toRuntimeStatusMap())
    }

    @Test fun queued_state_maps_to_empty() {
        val state = RunState.Queued(promptId = "p-1", queuePosition = 3)
        assertEquals(emptyMap(), state.toRuntimeStatusMap())
    }

    @Test fun running_state_maps_cached_completed_and_current() {
        val state = RunState.Running(
            promptId = "p-1",
            currentNodeId = "n-3",
            cachedNodes = setOf("n-1"),
            completedNodes = setOf("n-2"),
        )
        val result = state.toRuntimeStatusMap()
        assertEquals(NodeRuntimeStatus.CACHED, result["n-1"])
        assertEquals(NodeRuntimeStatus.DONE, result["n-2"])
        assertEquals(NodeRuntimeStatus.RUNNING, result["n-3"])
        assertEquals(3, result.size)
    }

    @Test fun running_promotes_a_cached_node_that_is_also_currently_running() {
        // Should not happen in practice (cached nodes don't emit Executing),
        // but the precedence rule still applies: RUNNING > CACHED.
        val state = RunState.Running(
            promptId = "p-1",
            currentNodeId = "n-1",
            cachedNodes = setOf("n-1"),
        )
        assertEquals(NodeRuntimeStatus.RUNNING, state.toRuntimeStatusMap()["n-1"])
    }

    @Test fun failed_with_NodeException_marks_failing_node_as_ERROR() {
        val state = RunState.Failed(
            promptId = "p-1",
            error = RunError.NodeException(
                nodeId = "n-5",
                nodeType = "KSampler",
                exceptionMessage = "boom",
                exceptionType = "RuntimeError",
                traceback = JsonNull,
            ),
        )
        val result = state.toRuntimeStatusMap()
        assertEquals(NodeRuntimeStatus.ERROR, result["n-5"])
        assertEquals(1, result.size)
    }

    @Test fun failed_with_non_node_error_maps_to_empty() {
        val state = RunState.Failed(
            promptId = null,
            error = RunError.Network(RuntimeException("no route")),
        )
        assertEquals(emptyMap(), state.toRuntimeStatusMap())
    }

    @Test fun cancelled_marks_fromNode_as_RUNNING_for_overlay_layer() {
        val state = RunState.Cancelled(promptId = "p-1", fromNodeId = "n-3")
        assertEquals(NodeRuntimeStatus.RUNNING, state.toRuntimeStatusMap()["n-3"])
    }

    @Test fun cancelled_without_node_maps_to_empty() {
        val state = RunState.Cancelled(promptId = "p-1", fromNodeId = null)
        assertEquals(emptyMap(), state.toRuntimeStatusMap())
    }

    @Test fun succeeded_maps_to_empty_because_gallery_takes_over() {
        val state = RunState.Succeeded(promptId = "p-1", outputs = emptyList())
        assertEquals(emptyMap(), state.toRuntimeStatusMap())
    }
}
