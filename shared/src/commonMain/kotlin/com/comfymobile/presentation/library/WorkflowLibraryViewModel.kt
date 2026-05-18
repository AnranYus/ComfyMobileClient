package com.comfymobile.presentation.library

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ConnectionStatusTone
import com.comfymobile.presentation.connection.ConnectionStatusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorkflowLibraryViewModel(
    private val repository: WorkflowRepository,
    private val activeServer: ActiveServerHolder,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val pendingDeleteId = MutableStateFlow<String?>(null)
    private val pendingRenameId = MutableStateFlow<String?>(null)
    private val renameDraft = MutableStateFlow("")
    private val pendingRename = combine(pendingRenameId, renameDraft) { id, draft ->
        PendingRename(id = id, draft = draft)
    }
    private val mutableOpenEvents = MutableSharedFlow<WorkflowRow>(extraBufferCapacity = 1)
    val openEvents: Flow<WorkflowRow> = mutableOpenEvents.asSharedFlow()

    val state: StateFlow<WorkflowLibraryScreenState> = combine(
        repository.observeAll(),
        activeServer.current,
        connectionState,
        pendingDeleteId,
        pendingRename,
    ) { workflows, server, connection, pendingDelete, pendingRename ->
        val rows = workflows.map { it.toLibraryRowState() }
        val statusUi = ConnectionStatusUi.from(connection, server)
        WorkflowLibraryScreenState(
            rows = rows,
            activeServerLabel = server?.label,
            connectionLabel = statusUi.label,
            connectionTone = statusUi.tone,
            connectionPulsing = statusUi.pulsing,
            pendingDelete = rows.firstOrNull { it.workflowId == pendingDelete },
            pendingRename = rows.firstOrNull { it.workflowId == pendingRename.id },
            renameDraft = pendingRename.draft,
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
}
