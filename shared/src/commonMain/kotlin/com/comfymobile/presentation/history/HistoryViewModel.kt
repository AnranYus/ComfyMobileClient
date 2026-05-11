package com.comfymobile.presentation.history

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: JobRepository,
    private val activeServer: ActiveServerHolder,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val thumbnailMapper: HistoryThumbnailMapper = NoopHistoryThumbnailMapper,
    private val isReconciling: StateFlow<Boolean> = MutableStateFlow(false),
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val selectedFilter = MutableStateFlow(HistoryFilter.All)
    private val mutableState = MutableStateFlow(HistoryScreenState(language = language))
    val state: StateFlow<HistoryScreenState> = mutableState.asStateFlow()

    init {
        combine(activeServer.current, selectedFilter, isReconciling) { server, filter, reconciling ->
            HistorySource(server, filter, reconciling)
        }
            .flatMapLatest { source ->
                val server = source.server
                if (server == null) {
                    flowOf(
                        HistoryScreenState(
                            selectedFilter = source.filter,
                            isReconciling = source.reconciling,
                            language = language,
                        ),
                    )
                } else {
                    repository.observeByServer(server.serverId)
                        .map { jobs ->
                            HistoryScreenState(
                                rows = HistoryMapper.rows(
                                    jobs = jobs,
                                    selectedFilter = source.filter,
                                    language = language,
                                    nowEpochMs = nowEpochMs(),
                                    thumbnailMapper = thumbnailMapper,
                                ),
                                selectedFilter = source.filter,
                                activeServerLabel = server.label,
                                isReconciling = source.reconciling,
                                renamePromptId = mutableState.value.renamePromptId,
                                renameDraft = mutableState.value.renameDraft,
                                deletePromptId = mutableState.value.deletePromptId,
                                language = language,
                            )
                        }
                }
            }
            .onEach { next ->
                mutableState.value = next.withValidDialogs()
            }
            .launchIn(scope)
    }

    fun actions(): HistoryActions = HistoryActions(
        onFilterSelected = ::onFilterSelected,
        onOpenOutputs = {},
        onOpenWorkflow = {},
        onRenameRequested = ::onRenameRequested,
        onRenameValueChanged = ::onRenameValueChanged,
        onConfirmRename = ::onConfirmRename,
        onDismissRename = ::onDismissRename,
        onDeleteRequested = ::onDeleteRequested,
        onConfirmDelete = ::onConfirmDelete,
        onDismissDelete = ::onDismissDelete,
    )

    fun onFilterSelected(filter: HistoryFilter) {
        selectedFilter.value = filter
    }

    fun onRenameRequested(promptId: String) {
        val row = mutableState.value.rows.firstOrNull { it.promptId == promptId } ?: return
        mutableState.value = mutableState.value.copy(
            renamePromptId = promptId,
            renameDraft = row.title,
        )
    }

    fun onRenameValueChanged(value: String) {
        mutableState.value = mutableState.value.copy(renameDraft = value)
    }

    fun onConfirmRename() {
        val promptId = mutableState.value.renamePromptId ?: return
        val label = mutableState.value.renameDraft.trim().takeIf { it.isNotEmpty() }
        scope.launch {
            repository.updateLabel(promptId, label)
            mutableState.value = mutableState.value.copy(
                renamePromptId = null,
                renameDraft = "",
            )
        }
    }

    fun onDismissRename() {
        mutableState.value = mutableState.value.copy(
            renamePromptId = null,
            renameDraft = "",
        )
    }

    fun onDeleteRequested(promptId: String) {
        if (mutableState.value.rows.none { it.promptId == promptId }) return
        mutableState.value = mutableState.value.copy(deletePromptId = promptId)
    }

    fun onConfirmDelete() {
        val promptId = mutableState.value.deletePromptId ?: return
        scope.launch {
            repository.deleteByPromptId(promptId)
            mutableState.value = mutableState.value.copy(deletePromptId = null)
        }
    }

    fun onDismissDelete() {
        mutableState.value = mutableState.value.copy(deletePromptId = null)
    }

    private fun HistoryScreenState.withValidDialogs(): HistoryScreenState {
        val promptIds = rows.map { it.promptId }.toSet()
        val renameStillValid = renamePromptId?.let { it in promptIds } == true
        return copy(
            renamePromptId = renamePromptId?.takeIf { it in promptIds },
            renameDraft = if (renameStillValid) renameDraft else "",
            deletePromptId = deletePromptId?.takeIf { it in promptIds },
        )
    }

    private data class HistorySource(
        val server: com.comfymobile.domain.server.ServerInfo?,
        val filter: HistoryFilter,
        val reconciling: Boolean,
    )
}
