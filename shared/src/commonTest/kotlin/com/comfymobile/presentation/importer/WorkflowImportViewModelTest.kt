package com.comfymobile.presentation.importer

import com.comfymobile.data.importer.PlatformWorkflowImportPayload
import com.comfymobile.data.persistence.InMemoryWorkflowRepository
import com.comfymobile.data.workflow.WorkflowImportError
import com.comfymobile.data.workflow.WorkflowImportSource
import com.comfymobile.data.workflow.WorkflowImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WorkflowImportViewModelTest {

    @Test fun payload_prepare_shows_preview_without_persisting() = runTest {
        val repository = InMemoryWorkflowRepository()
        val vm = viewModel(repository, this)

        vm.onPayload(
            PlatformWorkflowImportPayload.Text(
                value = uiWorkflowJson("KSampler"),
                source = WorkflowImportSource.File,
                sourceName = "mobile.json",
            )
        )
        runCurrent()

        assertEquals("mobile", vm.state.value.displayNameInput)
        assertEquals(1, vm.state.value.preview?.nodeStats?.totalNodes)
        assertEquals(emptyList(), repository.listRecents())
    }

    @Test fun confirm_import_persists_display_name_override() = runTest {
        val repository = InMemoryWorkflowRepository()
        val vm = viewModel(repository, this)

        vm.onPayload(
            PlatformWorkflowImportPayload.Text(
                value = uiWorkflowJson("KSampler"),
                source = WorkflowImportSource.PasteText,
            )
        )
        runCurrent()
        vm.onDisplayNameChanged("Edited workflow")
        vm.confirmImport()
        runCurrent()

        val row = repository.listRecents().single()
        assertEquals("Edited workflow", row.displayName)
        assertEquals(row, vm.state.value.lastImportedRow)
        assertNull(vm.state.value.preview)
    }

    @Test fun invalid_json_payload_sets_import_error() = runTest {
        val vm = viewModel(InMemoryWorkflowRepository(), this)

        vm.onPayload(
            PlatformWorkflowImportPayload.Text(
                value = "{ broken",
                source = WorkflowImportSource.PasteText,
            )
        )
        runCurrent()

        val error = assertIs<WorkflowImportUiError.Import>(vm.state.value.error)
        assertEquals(WorkflowImportError.InvalidJson(keyword = null), error.error)
    }

    private fun viewModel(
        repository: InMemoryWorkflowRepository,
        scope: CoroutineScope,
    ) = WorkflowImportViewModel(
        importerFactory = {
            WorkflowImporter(
                repository = repository,
                nowEpochMs = { 100L },
            )
        },
        scope = scope,
    )

    private fun uiWorkflowJson(vararg classTypes: String): String =
        classTypes
            .mapIndexed { index, classType ->
                """{"id":${index + 1},"type":"$classType","inputs":[],"widgets_values":[]}"""
            }
            .joinToString(prefix = """{"nodes":[""", separator = ",", postfix = """],"links":[],"version":0.4}""")
}
