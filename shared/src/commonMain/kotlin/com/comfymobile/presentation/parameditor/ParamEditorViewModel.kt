package com.comfymobile.presentation.parameditor

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ParamEditorViewModel(
    private val registry: NodeDescriptorRegistry,
    private val optionProvider: ParamOptionProvider,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val nextSeed: () -> Long = { nowEpochMs().coerceAtLeast(0L) },
    private val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    private val mutableState = MutableStateFlow(ParamEditorScreenState(language = language))
    val state: StateFlow<ParamEditorScreenState> = mutableState.asStateFlow()

    private var sourceEnvelope: WorkflowEnvelope? = null

    fun actions(): ParamEditorActions = ParamEditorActions(
        onOpen = ::open,
        onTextChanged = ::onTextChanged,
        onBooleanChanged = ::onBooleanChanged,
        onStepInteger = ::onStepInteger,
        onRandomSeed = ::onRandomSeed,
        onToggleHelp = ::onToggleHelp,
        onRetryOptions = ::loadOptions,
        onReset = ::onReset,
        onDismissRequested = ::onDismissRequested,
        onKeepEditing = ::onKeepEditing,
        onDiscard = ::onDiscard,
        onApply = ::onApply,
        onConsumeApplied = ::onConsumeApplied,
        onDismissError = ::onDismissError,
    )

    fun open(envelope: WorkflowEnvelope, nodeId: String) {
        sourceEnvelope = envelope
        when (val result = ParamEditorCore.open(envelope, nodeId, registry, language)) {
            is ParamEditorOpenResult.Ready -> {
                mutableState.value = ParamEditorScreenState(
                    editor = result.state,
                    language = language,
                )
                loadOptions()
            }
            ParamEditorOpenResult.NodeNotFound -> {
                mutableState.value = ParamEditorScreenState(
                    error = ParamEditorCopy.nodeNotFound,
                    language = language,
                )
            }
            is ParamEditorOpenResult.ReadOnlyNode -> {
                mutableState.value = ParamEditorScreenState(
                    error = ParamEditorCopy.readOnlyNode,
                    language = language,
                )
            }
            ParamEditorOpenResult.UnsupportedFormat -> {
                mutableState.value = ParamEditorScreenState(
                    error = ParamEditorCopy.unsupportedFormat,
                    language = language,
                )
            }
        }
    }

    fun onTextChanged(paramName: String, value: String) {
        val editor = mutableState.value.editor ?: return
        val row = editor.rows.firstOrNull { it.param.name == paramName } ?: return
        val draftValue = when (row.value) {
            is ParamDraftValue.BooleanValue -> return
            is ParamDraftValue.NumberText -> ParamDraftValue.NumberText(value)
            is ParamDraftValue.Text -> ParamDraftValue.Text(value)
        }
        mutableState.value = mutableState.value.copy(
            editor = ParamEditorCore.updateValue(editor, paramName, draftValue),
        )
    }

    fun onBooleanChanged(paramName: String, value: Boolean) {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(
            editor = ParamEditorCore.updateValue(
                editor,
                paramName,
                ParamDraftValue.BooleanValue(value),
            ),
        )
    }

    fun onStepInteger(paramName: String, delta: Long) {
        val editor = mutableState.value.editor ?: return
        val row = editor.rows.firstOrNull { it.param.name == paramName } ?: return
        val current = (row.value as? ParamDraftValue.NumberText)
            ?.value
            ?.toLongOrNull()
            ?: 0L
        onTextChanged(paramName, (current + delta).toString())
    }

    fun onRandomSeed(paramName: String) {
        onTextChanged(paramName, nextSeed().toString())
    }

    fun onToggleHelp(paramName: String) {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(
            editor = ParamEditorCore.toggleHelp(editor, paramName),
        )
    }

    fun loadOptions() {
        val editor = mutableState.value.editor ?: return
        editor.rows
            .mapNotNull { row ->
                val source = row.param.control.sourceOrNull() ?: return@mapNotNull null
                row.param.name to ParamOptionRequest(
                    classType = editor.nodeClassType,
                    paramName = row.param.name,
                    source = source,
                )
            }
            .forEach { (paramName, request) ->
                mutableState.value = mutableState.value.copy(
                    editor = mutableState.value.editor?.let {
                        ParamEditorCore.setOptionsLoading(it, paramName)
                    },
                )
                scope.launch {
                    val result = optionProvider.load(request)
                    val currentEditor = mutableState.value.editor ?: return@launch
                    mutableState.value = mutableState.value.copy(
                        editor = result.fold(
                            onSuccess = { ParamEditorCore.setOptionsLoaded(currentEditor, paramName, it) },
                            onFailure = {
                                ParamEditorCore.setOptionsFailed(
                                    currentEditor,
                                    paramName,
                                    it.message,
                                )
                            },
                        ),
                    )
                }
            }
    }

    fun onDismissRequested() {
        val editor = mutableState.value.editor ?: return
        mutableState.value = if (editor.isDirty) {
            mutableState.value.copy(editor = editor.copy(confirmDiscardVisible = true))
        } else {
            mutableState.value.copy(editor = null)
        }
    }

    fun onReset() {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(
            editor = ParamEditorCore.reset(editor),
        )
    }

    fun onKeepEditing() {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(
            editor = editor.copy(confirmDiscardVisible = false),
        )
    }

    fun onDiscard() {
        mutableState.value = mutableState.value.copy(editor = null)
    }

    fun onApply() {
        val envelope = sourceEnvelope ?: return
        val editor = mutableState.value.editor ?: return
        when (val result = ParamEditorCore.apply(envelope, editor, nowEpochMs())) {
            is ParamEditorApplyResult.Applied -> {
                sourceEnvelope = result.envelope
                mutableState.value = mutableState.value.copy(
                    editor = null,
                    lastAppliedEnvelope = result.envelope,
                    error = null,
                )
            }
            is ParamEditorApplyResult.Invalid -> {
                mutableState.value = mutableState.value.copy(
                    error = result.errors.firstOrNull(),
                )
            }
            ParamEditorApplyResult.NotDirty -> {
                mutableState.value = mutableState.value.copy(editor = null)
            }
        }
    }

    fun onConsumeApplied() {
        mutableState.value = mutableState.value.copy(lastAppliedEnvelope = null)
    }

    fun onDismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }
}

data class ParamEditorScreenState(
    val editor: ParamEditorState? = null,
    val error: com.comfymobile.presentation.connection.LocalizedText? = null,
    val lastAppliedEnvelope: WorkflowEnvelope? = null,
    val language: ConnectionLanguage = ConnectionLanguage.En,
)

data class ParamEditorActions(
    val onOpen: (WorkflowEnvelope, String) -> Unit = { _, _ -> },
    val onTextChanged: (paramName: String, value: String) -> Unit = { _, _ -> },
    val onBooleanChanged: (paramName: String, value: Boolean) -> Unit = { _, _ -> },
    val onStepInteger: (paramName: String, delta: Long) -> Unit = { _, _ -> },
    val onRandomSeed: (paramName: String) -> Unit = {},
    val onToggleHelp: (paramName: String) -> Unit = {},
    val onRetryOptions: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onDismissRequested: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onConsumeApplied: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)
