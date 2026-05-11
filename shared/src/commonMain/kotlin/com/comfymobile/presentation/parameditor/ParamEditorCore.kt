package com.comfymobile.presentation.parameditor

import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.LocalizedString
import com.comfymobile.domain.node.NodeDescriptor
import com.comfymobile.domain.node.ParamDescriptor
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowWidgetValueIndex
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.LocalizedText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object ParamEditorCore {

    fun open(
        envelope: WorkflowEnvelope,
        nodeId: String,
        registry: NodeDescriptorRegistry,
        language: ConnectionLanguage = ConnectionLanguage.En,
    ): ParamEditorOpenResult {
        if (envelope.format != WorkflowFormat.UI) return ParamEditorOpenResult.UnsupportedFormat
        val raw = envelope.original as? JsonObject ?: return ParamEditorOpenResult.UnsupportedFormat
        val node = findNode(raw, nodeId) ?: return ParamEditorOpenResult.NodeNotFound
        val classType = node[NODE_TYPE]?.asStringOrNull() ?: return ParamEditorOpenResult.NodeNotFound
        val descriptor = registry.lookup(classType) ?: return ParamEditorOpenResult.ReadOnlyNode(classType)
        if (descriptor.editableParams.none { it.control != ControlType.Hidden }) {
            return ParamEditorOpenResult.ReadOnlyNode(classType)
        }

        val widgetsValues = node[NODE_WIDGETS_VALUES].asJsonArrayOrEmpty()
        val title = node[NODE_TITLE]?.asStringOrNull()
            ?: descriptor.displayName.resolve(language)
            ?: classType
        val rows = descriptor.editableParams.map { param ->
            val widgetIndex = WorkflowWidgetValueIndex.indexOf(classType, descriptor, param)
            val draftValue = draftValueFor(param.control, widgetIndex?.let { widgetsValues.getOrNull(it) })
            val row = ParamEditorRowState(
                param = param,
                presentation = presentationFor(param),
                value = draftValue,
                initialValue = draftValue,
                options = if (param.control.sourceOrNull() == null) {
                    ParamOptionsState.NotNeeded
                } else {
                    ParamOptionsState.Idle
                },
            )
            row.copy(validationError = validate(param.control, row.value))
        }
        return ParamEditorOpenResult.Ready(
            ParamEditorState(
                nodeId = nodeId,
                nodeClassType = classType,
                nodeTitle = title,
                descriptor = descriptor,
                rows = rows,
                language = language,
            )
        )
    }

    fun updateValue(
        state: ParamEditorState,
        paramName: String,
        value: ParamDraftValue,
    ): ParamEditorState = state.copy(
        rows = state.rows.map { row ->
            if (row.param.name != paramName) row
            else row.copy(
                value = value,
                validationError = validate(row.param.control, value),
            )
        }
    )

    fun toggleHelp(
        state: ParamEditorState,
        paramName: String,
    ): ParamEditorState = state.copy(
        rows = state.rows.map { row ->
            if (row.param.name == paramName) row.copy(helpExpanded = !row.helpExpanded) else row
        }
    )

    fun reset(state: ParamEditorState): ParamEditorState = state.copy(
        rows = state.rows.map { row ->
            row.copy(
                value = row.initialValue,
                validationError = validate(row.param.control, row.initialValue),
            )
        },
        confirmDiscardVisible = false,
    )

    fun setOptionsLoading(
        state: ParamEditorState,
        paramName: String,
    ): ParamEditorState = updateOptions(state, paramName, ParamOptionsState.Loading)

    fun setOptionsLoaded(
        state: ParamEditorState,
        paramName: String,
        options: List<ParamOption>,
    ): ParamEditorState = updateOptions(state, paramName, ParamOptionsState.Loaded(options))

    fun setOptionsFailed(
        state: ParamEditorState,
        paramName: String,
        message: String?,
    ): ParamEditorState = updateOptions(state, paramName, ParamOptionsState.Failed(message))

    fun apply(
        envelope: WorkflowEnvelope,
        state: ParamEditorState,
        nowEpochMs: Long,
    ): ParamEditorApplyResult {
        val revalidatedRows = state.rows.map { row ->
            row.copy(validationError = validate(row.param.control, row.value))
        }
        val errors = revalidatedRows.mapNotNull { it.validationError }
        if (errors.isNotEmpty()) return ParamEditorApplyResult.Invalid(errors)
        if (revalidatedRows.none { it.isDirty }) return ParamEditorApplyResult.NotDirty
        if (envelope.format != WorkflowFormat.UI) {
            return ParamEditorApplyResult.Invalid(listOf(ParamEditorCopy.unsupportedFormat))
        }

        val raw = envelope.original as? JsonObject
            ?: return ParamEditorApplyResult.Invalid(listOf(ParamEditorCopy.unsupportedFormat))
        val nodes = raw[NODES] as? JsonArray
            ?: return ParamEditorApplyResult.Invalid(listOf(ParamEditorCopy.nodeNotFound))
        val updatedNodes = nodes.map { element ->
            val node = element as? JsonObject ?: return@map element
            if (node[NODE_ID]?.asLooseStringOrNull() != state.nodeId) return@map element
            patchNodeWidgets(node, state)
        }
        val updatedRaw = JsonObject(raw.toMutableMap().apply {
            put(NODES, JsonArray(updatedNodes))
        })
        return ParamEditorApplyResult.Applied(
            envelope.copy(
                original = updatedRaw,
                metadata = envelope.metadata.copy(lastEditedAtEpochMs = nowEpochMs),
            )
        )
    }

    private fun updateOptions(
        state: ParamEditorState,
        paramName: String,
        options: ParamOptionsState,
    ): ParamEditorState = state.copy(
        rows = state.rows.map { row ->
            if (row.param.name == paramName) row.copy(options = options) else row
        }
    )

    private fun patchNodeWidgets(
        node: JsonObject,
        state: ParamEditorState,
    ): JsonObject {
        val existing = node[NODE_WIDGETS_VALUES].asJsonArrayOrEmpty().toMutableList()
        for (row in state.rows) {
            if (row.isHidden) continue
            val index = WorkflowWidgetValueIndex.indexOf(
                classType = state.nodeClassType,
                descriptor = state.descriptor,
                param = row.param,
            ) ?: continue
            while (existing.size <= index) existing.add(JsonNull)
            existing[index] = row.value.toJson(row.param.control)
        }
        return JsonObject(node.toMutableMap().apply {
            put(NODE_WIDGETS_VALUES, JsonArray(existing))
        })
    }

    private fun presentationFor(param: ParamDescriptor): ParamControlPresentation = when (val control = param.control) {
        ControlType.Number -> ParamControlPresentation.Number
        is ControlType.Integer -> if (param.name == "seed") {
            ParamControlPresentation.Seed
        } else {
            ParamControlPresentation.IntegerStepper
        }
        is ControlType.Slider -> ParamControlPresentation.Slider
        ControlType.Toggle -> ParamControlPresentation.Toggle
        ControlType.SingleLineText -> ParamControlPresentation.SingleLineText
        is ControlType.MultilineText -> ParamControlPresentation.MultilineText
        is ControlType.Dropdown -> ParamControlPresentation.Dropdown
        is ControlType.ModelPicker -> ParamControlPresentation.ModelPicker
        is ControlType.ImagePicker -> ParamControlPresentation.ImagePicker
        ControlType.Hidden -> ParamControlPresentation.Hidden
    }

    private fun draftValueFor(control: ControlType, raw: JsonElement?): ParamDraftValue = when (control) {
        ControlType.Toggle -> ParamDraftValue.BooleanValue(raw?.asBooleanOrNull() ?: false)
        ControlType.Number,
        is ControlType.Integer,
        is ControlType.Slider,
        -> ParamDraftValue.NumberText(raw?.asScalarText().orEmpty())
        ControlType.SingleLineText,
        is ControlType.MultilineText,
        is ControlType.Dropdown,
        is ControlType.ModelPicker,
        is ControlType.ImagePicker,
        ControlType.Hidden,
        -> ParamDraftValue.Text(raw?.asScalarText().orEmpty())
    }

    private fun ParamDraftValue.toJson(control: ControlType): JsonElement = when (control) {
        ControlType.Toggle -> JsonPrimitive((this as ParamDraftValue.BooleanValue).value)
        ControlType.Number -> JsonPrimitive((this as ParamDraftValue.NumberText).value.toDouble())
        is ControlType.Integer -> JsonPrimitive((this as ParamDraftValue.NumberText).value.toLong())
        is ControlType.Slider -> {
            val raw = (this as ParamDraftValue.NumberText).value.toDouble()
            val snapped = snapToStep(raw, control)
            if (control.step % 1.0 == 0.0 && snapped % 1.0 == 0.0) {
                JsonPrimitive(snapped.toLong())
            } else {
                JsonPrimitive(snapped)
            }
        }
        ControlType.SingleLineText,
        is ControlType.MultilineText,
        is ControlType.Dropdown,
        is ControlType.ModelPicker,
        is ControlType.ImagePicker,
        ControlType.Hidden,
        -> JsonPrimitive((this as ParamDraftValue.Text).value)
    }

    private fun validate(control: ControlType, value: ParamDraftValue): LocalizedText? = when (control) {
        ControlType.Number -> {
            val text = (value as? ParamDraftValue.NumberText)?.value.orEmpty()
            if (text.toDoubleOrNull() == null) ParamEditorCopy.mustBeNumber else null
        }
        is ControlType.Integer -> {
            val text = (value as? ParamDraftValue.NumberText)?.value.orEmpty()
            val parsed = text.toLongOrNull()
            when {
                parsed == null -> ParamEditorCopy.mustBeInteger
                control.min != null && parsed < control.min -> ParamEditorCopy.minValue(control.min.toString())
                control.max != null && parsed > control.max -> ParamEditorCopy.maxValue(control.max.toString())
                else -> null
            }
        }
        is ControlType.Slider -> {
            val text = (value as? ParamDraftValue.NumberText)?.value.orEmpty()
            val parsed = text.toDoubleOrNull()
            when {
                parsed == null -> ParamEditorCopy.mustBeNumber
                parsed < control.min -> ParamEditorCopy.minValue(control.min.asDisplayNumber())
                parsed > control.max -> ParamEditorCopy.maxValue(control.max.asDisplayNumber())
                else -> null
            }
        }
        else -> null
    }

    private fun findNode(raw: JsonObject, nodeId: String): JsonObject? =
        (raw[NODES] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it[NODE_ID]?.asLooseStringOrNull() == nodeId }

    private fun JsonElement?.asJsonArrayOrEmpty(): JsonArray =
        this as? JsonArray ?: JsonArray(emptyList())

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.asLooseStringOrNull(): String? = when (this) {
        is JsonPrimitive ->
            if (isString) contentOrNull
            else longOrNull?.toString() ?: doubleOrNull?.toLong()?.toString()
        else -> null
    }

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement.asScalarText(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }

    private fun LocalizedString.resolve(language: ConnectionLanguage): String? = when (language) {
        ConnectionLanguage.Zh -> zh
        ConnectionLanguage.En -> en ?: zh
    }

    private fun snapToStep(value: Double, control: ControlType.Slider): Double {
        if (control.step <= 0.0) return value
        val steps = kotlin.math.round((value - control.min) / control.step)
        return (control.min + steps * control.step).coerceIn(control.min, control.max)
    }

    private fun Double.asDisplayNumber(): String =
        if (this % 1.0 == 0.0) toLong().toString() else toString()

    private const val NODES = "nodes"
    private const val NODE_ID = "id"
    private const val NODE_TYPE = "type"
    private const val NODE_TITLE = "title"
    private const val NODE_WIDGETS_VALUES = "widgets_values"
}

object ParamEditorCopy {
    val unsupportedFormat = LocalizedText(
        zh = "这个工作流暂不支持移动端参数编辑",
        en = "This workflow cannot be edited on mobile yet",
    )
    val nodeNotFound = LocalizedText(
        zh = "没有找到这个节点",
        en = "Node not found",
    )
    val readOnlyNode = LocalizedText(
        zh = "此节点暂不支持移动端编辑，可在桌面 ComfyUI 中调整后重新导入",
        en = "This node can't be edited on mobile. Adjust it in desktop ComfyUI and re-import.",
    )
    val mustBeNumber = LocalizedText(
        zh = "请输入数字",
        en = "Enter a number",
    )
    val mustBeInteger = LocalizedText(
        zh = "请输入整数",
        en = "Enter a whole number",
    )
    fun minValue(min: String) = LocalizedText(
        zh = "不能小于 $min",
        en = "Must be at least $min",
    )
    fun maxValue(max: String) = LocalizedText(
        zh = "不能大于 $max",
        en = "Must be at most $max",
    )
}
