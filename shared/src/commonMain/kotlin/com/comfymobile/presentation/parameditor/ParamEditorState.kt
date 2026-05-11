package com.comfymobile.presentation.parameditor

import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.ParamDescriptor
import com.comfymobile.domain.node.Source
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText

data class ParamEditorState(
    val nodeId: String,
    val nodeClassType: String,
    val nodeTitle: String,
    val descriptor: NodeDescriptor,
    val rows: List<ParamEditorRowState>,
    val language: ConnectionLanguage = ConnectionLanguage.En,
    val confirmDiscardVisible: Boolean = false,
) {
    val isDirty: Boolean get() = rows.any { it.isDirty }
    val canApply: Boolean get() = isDirty && rows.none { it.validationError != null }
}

data class ParamEditorRowState(
    val param: ParamDescriptor,
    val presentation: ParamControlPresentation,
    val value: ParamDraftValue,
    val initialValue: ParamDraftValue,
    val validationError: LocalizedText? = null,
    val helpExpanded: Boolean = false,
    val options: ParamOptionsState = ParamOptionsState.NotNeeded,
) {
    val isDirty: Boolean get() = value != initialValue
    val isHidden: Boolean get() = param.control == ControlType.Hidden
}

sealed interface ParamDraftValue {
    data class Text(val value: String) : ParamDraftValue
    data class NumberText(val value: String) : ParamDraftValue
    data class BooleanValue(val value: Boolean) : ParamDraftValue
}

enum class ParamControlPresentation {
    Number,
    IntegerStepper,
    Seed,
    Slider,
    Toggle,
    SingleLineText,
    MultilineText,
    Dropdown,
    ModelPicker,
    ImagePicker,
    Hidden,
}

data class ParamOption(
    val value: String,
    val label: String = value,
)

data class ParamOptionRequest(
    val classType: String,
    val paramName: String,
    val source: Source,
)

sealed interface ParamOptionsState {
    data object NotNeeded : ParamOptionsState
    data object Idle : ParamOptionsState
    data object Loading : ParamOptionsState
    data class Loaded(val options: List<ParamOption>) : ParamOptionsState
    data class Failed(val message: String?) : ParamOptionsState
}

sealed interface ParamEditorOpenResult {
    data class Ready(val state: ParamEditorState) : ParamEditorOpenResult
    data object UnsupportedFormat : ParamEditorOpenResult
    data object NodeNotFound : ParamEditorOpenResult
    data class ReadOnlyNode(val classType: String) : ParamEditorOpenResult
}

sealed interface ParamEditorApplyResult {
    data class Applied(val envelope: com.comfymobile.domain.workflow.WorkflowEnvelope) : ParamEditorApplyResult
    data object NotDirty : ParamEditorApplyResult
    data class Invalid(val errors: List<LocalizedText>) : ParamEditorApplyResult
}

fun ControlType.sourceOrNull(): Source? = when (this) {
    is ControlType.Dropdown -> source
    is ControlType.ModelPicker -> source
    is ControlType.ImagePicker -> source
    else -> null
}
