package com.comfymobile.presentation.library

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.job.Job
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ConnectionStatusTone
import com.comfymobile.presentation.connection.ConnectionStatusUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowLibraryViewModel(
    private val repository: WorkflowRepository,
    private val jobRepository: JobRepository,
    private val activeServer: ActiveServerHolder,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val thumbnailUrlForOutput: (JobOutputRef) -> String? = { null },
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val pendingDeleteId = MutableStateFlow<String?>(null)
    private val pendingRenameId = MutableStateFlow<String?>(null)
    private val renameDraft = MutableStateFlow("")
    private val pendingRename = combine(pendingRenameId, renameDraft) { id, draft ->
        PendingRename(id = id, draft = draft)
    }
    private val succeededOutputJobs = activeServer.current.flatMapLatest { server ->
        if (server == null) {
            flowOf(ThumbnailJobSnapshot(serverId = null, jobs = emptyList()))
        } else {
            jobRepository.observeSucceededWithFirstOutputByServer(server.serverId)
                .map { jobs -> ThumbnailJobSnapshot(serverId = server.serverId, jobs = jobs) }
        }
    }
    private val librarySources = combine(
        repository.observeAll(),
        activeServer.current,
        connectionState,
        pendingDeleteId,
        pendingRename,
    ) { workflows, server, connection, pendingDelete, pendingRename ->
        LibrarySources(
            workflows = workflows,
            server = server,
            connection = connection,
            pendingDelete = pendingDelete,
            pendingRename = pendingRename,
        )
    }
    private val mutableOpenEvents = MutableSharedFlow<WorkflowRow>(extraBufferCapacity = 1)
    val openEvents: Flow<WorkflowRow> = mutableOpenEvents.asSharedFlow()

    val state: StateFlow<WorkflowLibraryScreenState> = combine(
        librarySources,
        succeededOutputJobs,
    ) { sources, outputSnapshot ->
        val latestOutputsByWorkflowId =
            if (outputSnapshot.serverId == sources.server?.serverId) {
                outputSnapshot.jobs.latestOutputsByWorkflowId()
            } else {
                emptyMap()
            }
        val rows = sources.workflows.map { workflow ->
            workflow.toLibraryRowState(
                thumbnailUrl = latestOutputsByWorkflowId[workflow.workflowId]
                    ?.let(thumbnailUrlForOutput),
            )
        }
        val statusUi = ConnectionStatusUi.from(sources.connection, sources.server)
        WorkflowLibraryScreenState(
            rows = rows,
            activeServerLabel = sources.server?.label,
            connectionLabel = statusUi.label,
            connectionTone = statusUi.tone,
            connectionPulsing = statusUi.pulsing,
            pendingDelete = rows.firstOrNull { it.workflowId == sources.pendingDelete },
            pendingRename = rows.firstOrNull { it.workflowId == sources.pendingRename.id },
            renameDraft = sources.pendingRename.draft,
            language = language,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = WorkflowLibraryScreenState(
            connectionLabel = WorkflowLibraryCopy.connectionUnavailable,
            connectionTone = ConnectionStatusTone.Subtle,
            language = language,
        ),
    )

    fun actions(onImport: () -> Unit = {}): WorkflowLibraryActions = WorkflowLibraryActions(
        onImport = onImport,
        onOpenWorkflow = ::openWorkflow,
        onRenameRequested = ::requestRename,
        onRenameValueChanged = ::updateRenameDraft,
        onDismissRename = ::dismissRename,
        onConfirmRename = ::confirmRename,
        onDeleteRequested = ::requestDelete,
        onDismissDelete = ::dismissDelete,
        onConfirmDelete = ::deleteWorkflow,
    )

    fun openWorkflow(workflowId: String) {
        scope.launch {
            repository.markOpened(
                workflowId = workflowId,
                openedAtEpochMs = nowEpochMs(),
            )?.let { mutableOpenEvents.emit(it) }
        }
    }

    fun requestRename(workflowId: String) {
        val row = state.value.rows.firstOrNull { it.workflowId == workflowId } ?: return
        pendingRenameId.value = workflowId
        renameDraft.value = row.title
    }

    fun updateRenameDraft(value: String) {
        renameDraft.value = value
    }

    fun dismissRename() {
        pendingRenameId.value = null
        renameDraft.value = ""
    }

    fun confirmRename() {
        val workflowId = pendingRenameId.value ?: return
        val displayName = renameDraft.value.trim()
        val currentName = state.value.pendingRename?.title
        if (displayName.isEmpty() || displayName == currentName) return
        pendingRenameId.value = null
        renameDraft.value = ""
        scope.launch {
            repository.rename(
                workflowId = workflowId,
                displayName = displayName,
            )
        }
    }

    fun requestDelete(workflowId: String) {
        pendingDeleteId.value = workflowId
    }

    fun dismissDelete() {
        pendingDeleteId.value = null
    }

    fun deleteWorkflow(workflowId: String) {
        pendingDeleteId.value = null
        scope.launch {
            repository.delete(workflowId)
        }
    }

    private data class PendingRename(
        val id: String?,
        val draft: String,
    )

    private data class LibrarySources(
        val workflows: List<WorkflowRow>,
        val server: com.comfymobile.domain.server.ServerInfo?,
        val connection: ConnectionState,
        val pendingDelete: String?,
        val pendingRename: PendingRename,
    )

    private data class ThumbnailJobSnapshot(
        val serverId: String?,
        val jobs: List<Job>,
    )

    private fun List<Job>.latestOutputsByWorkflowId(): Map<String, JobOutputRef> =
        buildMap {
            for (job in this@latestOutputsByWorkflowId) {
                val key = job.workflowId?.takeIf { it.isNotBlank() } ?: continue
                val output = job.firstOutput ?: continue
                if (!containsKey(key)) put(key, output)
            }
        }
}
