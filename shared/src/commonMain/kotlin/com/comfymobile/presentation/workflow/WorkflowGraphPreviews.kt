package com.comfymobile.presentation.workflow

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compose previews for [WorkflowGraphRoute]. These are intentionally
 * minimal — the route's behaviour is fully covered by
 * `WorkflowGraphViewModelTest`; the previews are a visual sanity check
 * for the surface layout (top bar + canvas + Run FAB).
 *
 * Per @Lily PR #19 thread (`4da46760`): previews are the deterministic
 * gate for visual diffs; they're recorded without a real GPU and
 * survive runner flakiness.
 */

@Preview
@Composable
private fun WorkflowGraphRoutePreview() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val repo = previewRepository("preview-1", "Hello workflow")
    val graphVm = WorkflowGraphViewModel(
        repository = repo,
        registry = emptyPreviewRegistry(),
        scope = scope,
        language = ConnectionLanguage.En,
    ).also { vm ->
        vm.loadFromEnvelope(
            workflowId = "preview-1",
            displayName = "Hello workflow",
            envelope = previewEnvelope(),
        )
        vm.setConnected(true)
    }
    val paramEditorVm = previewParamEditorViewModel(scope, ConnectionLanguage.En)

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            WorkflowGraphRoute(
                viewModel = graphVm,
                paramEditorViewModel = paramEditorVm,
                onBack = {},
                onRun = {},
            )
        }
    }
}

@Preview
@Composable
private fun WorkflowGraphRouteNotFoundPreview() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val repo = previewRepository(rows = emptyMap())
    val graphVm = WorkflowGraphViewModel(
        repository = repo,
        registry = emptyPreviewRegistry(),
        scope = scope,
        language = ConnectionLanguage.En,
    ).also { vm -> vm.load("definitely-missing") }
    val paramEditorVm = previewParamEditorViewModel(scope, ConnectionLanguage.En)

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            WorkflowGraphRoute(
                viewModel = graphVm,
                paramEditorViewModel = paramEditorVm,
                onBack = {},
                onRun = {},
            )
        }
    }
}

// ---------------------------------------------------------------- preview fixtures

private fun previewRepository(
    workflowId: String = "preview-1",
    displayName: String = "Hello workflow",
): WorkflowRepository = previewRepository(
    rows = mapOf(
        workflowId to WorkflowRow(
            workflowId = workflowId,
            displayName = displayName,
            envelope = previewEnvelope(),
            importedAtEpochMs = 0L,
        ),
    ),
)

private fun emptyPreviewRegistry(): com.comfymobile.data.descriptor.NodeDescriptorRegistry =
    com.comfymobile.data.descriptor.NodeDescriptorRegistry
        .fromJson("""{"version":1,"descriptors":[]}""")

private fun previewRepository(rows: Map<String, WorkflowRow>): WorkflowRepository =
    object : WorkflowRepository {
        override suspend fun upsert(envelope: WorkflowEnvelope): WorkflowRow =
            error("not exercised in preview")

        override suspend fun getById(workflowId: String): WorkflowRow? = rows[workflowId]

        override fun observeAll(): Flow<List<WorkflowRow>> = MutableSharedFlow()

        override suspend fun listRecents(limit: Int): List<WorkflowRow> = rows.values.toList()

        override suspend fun markOpened(workflowId: String, openedAtEpochMs: Long): WorkflowRow? =
            rows[workflowId]

        override suspend fun delete(workflowId: String) { /* no-op */ }
    }

private fun previewEnvelope(): WorkflowEnvelope = WorkflowEnvelope(
    original = previewUiJson(),
    format = WorkflowFormat.UI,
    metadata = WorkflowMetadata(
        label = "preview",
        createdAtEpochMs = 0L,
        lastEditedAtEpochMs = 0L,
    ),
)

private fun previewUiJson(): JsonObject = buildJsonObject {
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
            put("type", JsonPrimitive("KSampler"))
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

/**
 * Constructs a minimal `ParamEditorViewModel` for preview use. The
 * registry is built from an empty descriptors JSON document so no
 * nodes count as whitelisted (which is fine — the preview doesn't
 * exercise long-press; the drawer stays closed throughout).
 */
private fun previewParamEditorViewModel(
    scope: CoroutineScope,
    language: ConnectionLanguage,
): com.comfymobile.presentation.parameditor.ParamEditorViewModel =
    com.comfymobile.presentation.parameditor.ParamEditorViewModel(
        registry = com.comfymobile.data.descriptor.NodeDescriptorRegistry
            .fromJson("""{"version":1,"descriptors":[]}"""),
        optionProvider = com.comfymobile.presentation.parameditor.ParamOptionProvider { _ ->
            Result.success(emptyList())
        },
        scope = scope,
        nowEpochMs = { 0L },
        language = language,
    )
