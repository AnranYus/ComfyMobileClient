package com.comfymobile

import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavTest {

    @Test fun run_shortcut_requires_selected_workflow_active_server_and_idle_screen() {
        assertTrue(
            canShowRunShortcut(
                selectedWorkflowId = "wf-1",
                hasActiveServer = true,
                screen = AppScreen.Idle,
            )
        )
        assertFalse(
            canShowRunShortcut(
                selectedWorkflowId = "wf-1",
                hasActiveServer = false,
                screen = AppScreen.Idle,
            )
        )
        assertFalse(
            canShowRunShortcut(
                selectedWorkflowId = null,
                hasActiveServer = true,
                screen = AppScreen.Idle,
            )
        )
        assertFalse(
            canShowRunShortcut(
                selectedWorkflowId = "wf-1",
                hasActiveServer = true,
                screen = AppScreen.Running(envelope()),
            )
        )
    }

    @Test fun selected_workflow_is_cleared_only_by_matching_delete_event() {
        assertFalse(
            shouldClearSelectedWorkflowAfterDelete(
                selectedWorkflowId = "wf-1",
                deletedWorkflowId = "wf-2",
            )
        )
        assertTrue(
            shouldClearSelectedWorkflowAfterDelete(
                selectedWorkflowId = "wf-1",
                deletedWorkflowId = "wf-1",
            )
        )
        assertFalse(
            shouldClearSelectedWorkflowAfterDelete(
                selectedWorkflowId = null,
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
