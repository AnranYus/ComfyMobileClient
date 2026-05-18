package com.comfymobile

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-projection tests for [AppNav] helpers per @Ores T2.7 §1.10 nav
 * flow.  The Run FAB shortcut and `selectedWorkflow` state that lived
 * in App.kt during T2.3 follow-up were removed when the graph-route
 * surfaced (Run FAB is now inside `WorkflowGraphRoute`), so this test
 * file collapsed to the one remaining cross-screen invariant —
 * `shouldPopGraphAfterDelete`.
 */
class AppNavTest {

    @Test fun shouldPopGraphAfterDelete_pops_only_for_matching_workflow_id() {
        // Pop: viewing wf-1 and wf-1 is deleted.
        assertTrue(
            shouldPopGraphAfterDelete(
                screen = AppScreen.Graph(workflowId = "wf-1", displayName = "Hello"),
                deletedWorkflowId = "wf-1",
            )
        )
        // No-op: viewing wf-1 but wf-2 was deleted.
        assertFalse(
            shouldPopGraphAfterDelete(
                screen = AppScreen.Graph(workflowId = "wf-1", displayName = "Hello"),
                deletedWorkflowId = "wf-2",
            )
        )
    }

    @Test fun shouldPopGraphAfterDelete_does_not_fire_outside_graph_screen() {
        // In Idle (Library) — delete event is the library's own concern,
        // host nav doesn't need to pop anything.
        assertFalse(
            shouldPopGraphAfterDelete(
                screen = AppScreen.Idle,
                deletedWorkflowId = "wf-1",
            )
        )
        // In Running — should not pop (RunRoute is mid-flight against
        // a snapshotted envelope; deleting the library row doesn't
        // cancel the in-flight run per @Lily T2.3 gate 3).
        assertFalse(
            shouldPopGraphAfterDelete(
                screen = AppScreen.Running(workflowId = "wf-1", envelope = envelope()),
                deletedWorkflowId = "wf-1",
            )
        )
        // In Gallery — terminal state, no graph to pop from.
        assertFalse(
            shouldPopGraphAfterDelete(
                screen = AppScreen.Gallery(promptId = "p-1", outputs = emptyList()),
                deletedWorkflowId = "wf-1",
            )
        )
    }

    private fun envelope(): WorkflowEnvelope = WorkflowEnvelope(
        original = buildJsonObject { },
        format = WorkflowFormat.UI,
        metadata = WorkflowMetadata(
            label = "test",
            createdAtEpochMs = 1L,
            lastEditedAtEpochMs = 1L,
        ),
    )
}
