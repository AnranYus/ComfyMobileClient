package com.comfymobile.presentation.connection

import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.network.ConnectErrorContext
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectUiEvent {
    data class ConnectRequested(val submit: ServerFormSubmit) : ConnectUiEvent
    data class RenameRequested(val server: ServerInfo) : ConnectUiEvent
}

class ConnectViewModel(
    private val machine: ConnectionStateMachineFacade,
    private val historyStore: ServerHistoryStore,
    private val scope: CoroutineScope,
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val formState = MutableStateFlow(ServerFormState())
    private val modalState = MutableStateFlow<FriendlyNameModalState>(FriendlyNameModalState.Hidden)
    private val errorContext = MutableStateFlow<ConnectErrorContext?>(null)
    private val showErrorDetails = MutableStateFlow(true)
    private val eventsChannel = Channel<ConnectUiEvent>(Channel.BUFFERED)
    private var validationDebounceJob: Job? = null

    val events = eventsChannel.receiveAsFlow()

    private val errorPresentation = combine(errorContext, showErrorDetails) { context, showDetails ->
        ErrorPresentation(context = context, showDetails = showDetails)
    }

    val screenState: StateFlow<ConnectScreenState> = combine(
        machine.currentState,
        historyStore.observeAll(),
        formState,
        modalState,
        errorPresentation,
    ) { connectionState, history, form, modal, error ->
        val validation = ServerFormValidator.validate(form, history.map { it.label })
        ConnectScreenState(
            connectionState = connectionState,
            formState = form,
            formValidation = validation,
            history = history,
            errorContext = error.context,
            showErrorDetails = error.showDetails,
            friendlyNameModal = modal,
            language = language,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ConnectScreenState(
            connectionState = machine.currentState.value,
            language = language,
        ),
    )

    fun actions(): ConnectActions = ConnectActions(
        onHostChanged = ::onHostChanged,
        onPortChanged = ::onPortChanged,
        onFriendlyNameChanged = ::onFriendlyNameChanged,
        onSubmit = ::onSubmit,
        onServerSelected = ::onServerSelected,
        onServerLongPressed = ::onServerLongPressed,
        onServerDelete = ::onServerDelete,
        onRetry = ::onRetry,
        onDismissError = ::onDismissError,
        onFriendlyModalChanged = ::onFriendlyModalChanged,
        onFriendlyModalSave = ::onFriendlyModalSave,
        onFriendlyModalDismiss = ::onFriendlyModalDismiss,
    )

    fun onHostChanged(value: String) = mutateForm { it.copy(host = value) }

    fun onPortChanged(value: String) = mutateForm { it.copy(port = value.filter { char -> char.isDigit() }) }

    fun onFriendlyNameChanged(value: String) = mutateForm { it.copy(friendlyName = value) }

    fun onSubmit() {
        val current = formState.value.copy(showValidationErrors = true)
        formState.value = current
        val existingNames = screenState.value.history.map { it.label }
        val submit = ServerFormValidator.submitOrNull(current, existingNames) ?: return
        eventsChannel.trySend(ConnectUiEvent.ConnectRequested(submit))
    }

    fun onServerSelected(server: ServerInfo) {
        formState.value = ServerFormState(
            host = server.host,
            port = server.port.toString(),
            friendlyName = server.label,
        )
        eventsChannel.trySend(
            ConnectUiEvent.ConnectRequested(
                ServerFormSubmit(
                    host = server.host,
                    port = server.port,
                    friendlyName = server.label,
                ),
            ),
        )
    }

    fun onServerLongPressed(server: ServerInfo) {
        eventsChannel.trySend(ConnectUiEvent.RenameRequested(server))
        modalState.value = FriendlyNameModalState.Visible(
            host = server.host,
            port = server.port,
            value = server.label,
        )
    }

    fun onServerDelete(server: ServerInfo) {
        scope.launch {
            historyStore.delete(server.serverId)
        }
    }

    fun onRetry() {
        showErrorDetails.value = true
        machine.dispatch(ConnectionInput.Retry)
    }

    fun onDismissError() {
        showErrorDetails.value = false
    }

    fun onFriendlyModalChanged(value: String) {
        val current = modalState.value
        if (current is FriendlyNameModalState.Visible) {
            modalState.value = current.copy(value = value)
        }
    }

    fun onFriendlyModalSave() {
        val current = modalState.value as? FriendlyNameModalState.Visible ?: return
        val label = current.value.trim().ifEmpty { current.placeholder }
        scope.launch {
            val serverId = ServerInfo.idFor(current.host, current.port)
            val existing = historyStore.getById(serverId)
            historyStore.upsert(
                existing?.copy(label = label) ?: ServerInfo(
                    serverId = serverId,
                    host = current.host,
                    port = current.port,
                    label = label,
                    lastConnectedAtEpochMs = 0L,
                ),
            )
            modalState.value = FriendlyNameModalState.Hidden
        }
    }

    fun onFriendlyModalDismiss() {
        modalState.value = FriendlyNameModalState.Hidden
    }

    private fun mutateForm(transform: (ServerFormState) -> ServerFormState) {
        formState.value = transform(formState.value).copy(showValidationErrors = false)
        validationDebounceJob?.cancel()
        validationDebounceJob = scope.launch {
            delay(ServerFormValidator.INLINE_ERROR_DEBOUNCE_MS)
            formState.value = formState.value.copy(showValidationErrors = true)
        }
    }

    private data class ErrorPresentation(
        val context: ConnectErrorContext?,
        val showDetails: Boolean,
    )
}
