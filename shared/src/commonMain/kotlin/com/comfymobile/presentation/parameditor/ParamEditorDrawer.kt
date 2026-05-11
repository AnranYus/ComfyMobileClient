package com.comfymobile.presentation.parameditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfymobile.domain.node.ControlType
import com.comfymobile.domain.node.LocalizedString
import com.comfymobile.presentation.connection.ConnectionLanguage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ParamEditorRoute(
    viewModel: ParamEditorViewModel,
    onApplied: (com.comfymobile.domain.workflow.WorkflowEnvelope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val actions = viewModel.actions()
    LaunchedEffect(state.lastAppliedEnvelope) {
        val envelope = state.lastAppliedEnvelope ?: return@LaunchedEffect
        onApplied(envelope)
        actions.onConsumeApplied()
    }
    ParamEditorOverlay(
        state = state,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
fun ParamEditorOverlay(
    state: ParamEditorScreenState,
    actions: ParamEditorActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val editor = state.editor
        if (editor != null) {
            ParamEditorSheet(
                state = editor,
                actions = actions,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = actions.onDismissError,
            title = { Text(ParamEditorUiCopy.cannotEdit.resolve(state.language)) },
            text = { Text(error.resolve(state.language)) },
            confirmButton = {
                TextButton(onClick = actions.onDismissError) {
                    Text(ParamEditorUiCopy.ok.resolve(state.language))
                }
            },
        )
    }
}

@Composable
fun ParamEditorSheet(
    state: ParamEditorState,
    actions: ParamEditorActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.nodeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.nodeClassType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = actions.onDismissRequested) {
                    Text(ParamEditorUiCopy.close.resolve(state.language))
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                state.rows.filterNot { it.isHidden }.forEach { row ->
                    ParamEditorRow(
                        row = row,
                        language = state.language,
                        actions = actions,
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions.onDismissRequested) {
                    Text(ParamEditorUiCopy.cancel.resolve(state.language))
                }
                OutlinedButton(
                    onClick = actions.onReset,
                    enabled = state.isDirty,
                ) {
                    Text(ParamEditorUiCopy.reset.resolve(state.language))
                }
                Button(
                    onClick = actions.onApply,
                    enabled = state.canApply,
                ) {
                    Text(ParamEditorUiCopy.apply.resolve(state.language))
                }
            }
        }
    }

    if (state.confirmDiscardVisible) {
        AlertDialog(
            onDismissRequest = actions.onKeepEditing,
            title = { Text(ParamEditorUiCopy.discardTitle.resolve(state.language)) },
            text = { Text(ParamEditorUiCopy.discardBody.resolve(state.language)) },
            confirmButton = {
                TextButton(onClick = actions.onDiscard) {
                    Text(ParamEditorUiCopy.discard.resolve(state.language))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onKeepEditing) {
                    Text(ParamEditorUiCopy.keepEditing.resolve(state.language))
                }
            },
        )
    }
}

@Composable
private fun ParamEditorRow(
    row: ParamEditorRowState,
    language: ConnectionLanguage,
    actions: ParamEditorActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.param.displayName.resolve(language),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (row.param.helpText != null) {
                TextButton(
                    onClick = { actions.onToggleHelp(row.param.name) },
                    modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp),
                ) {
                    Text("?")
                }
            }
        }

        if (row.helpExpanded) {
            Text(
                text = row.param.helpText?.resolve(language).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (row.presentation) {
            ParamControlPresentation.Number,
            ParamControlPresentation.IntegerStepper,
            -> NumberField(row, actions)
            ParamControlPresentation.Seed -> SeedField(row, language, actions)
            ParamControlPresentation.Slider -> SliderField(row, actions)
            ParamControlPresentation.Toggle -> ToggleField(row, actions)
            ParamControlPresentation.SingleLineText -> TextField(row, actions, singleLine = true)
            ParamControlPresentation.MultilineText -> TextField(row, actions, singleLine = false)
            ParamControlPresentation.Dropdown,
            ParamControlPresentation.ModelPicker,
            ParamControlPresentation.ImagePicker,
            -> OptionBackedField(row, language, actions)
            ParamControlPresentation.Hidden -> Unit
        }

        row.validationError?.let { error ->
            Text(
                text = error.resolve(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NumberField(
    row: ParamEditorRowState,
    actions: ParamEditorActions,
) {
    val value = (row.value as? ParamDraftValue.NumberText)?.value.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.presentation == ParamControlPresentation.IntegerStepper) {
            OutlinedButton(onClick = { actions.onStepInteger(row.param.name, -1) }) { Text("-") }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { actions.onTextChanged(row.param.name, it) },
            singleLine = true,
            isError = row.validationError != null,
            modifier = Modifier.weight(1f),
        )
        if (row.presentation == ParamControlPresentation.IntegerStepper) {
            OutlinedButton(onClick = { actions.onStepInteger(row.param.name, 1) }) { Text("+") }
        }
    }
}

@Composable
private fun SeedField(
    row: ParamEditorRowState,
    language: ConnectionLanguage,
    actions: ParamEditorActions,
) {
    val value = (row.value as? ParamDraftValue.NumberText)?.value.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { actions.onTextChanged(row.param.name, it) },
            singleLine = true,
            isError = row.validationError != null,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { actions.onRandomSeed(row.param.name) }) {
            Text(ParamEditorUiCopy.randomSeed.resolve(language))
        }
        OutlinedButton(onClick = {}, enabled = false) {
            Text(ParamEditorUiCopy.lockSeed.resolve(language))
        }
    }
}

@Composable
private fun SliderField(
    row: ParamEditorRowState,
    actions: ParamEditorActions,
) {
    val control = row.param.control as? ControlType.Slider ?: return
    val currentText = (row.value as? ParamDraftValue.NumberText)?.value.orEmpty()
    val current = currentText.toDoubleOrNull()?.coerceIn(control.min, control.max) ?: control.min
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = current.toFloat(),
                onValueChange = { actions.onTextChanged(row.param.name, it.toString()) },
                valueRange = control.min.toFloat()..control.max.toFloat(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = currentText,
                onValueChange = { actions.onTextChanged(row.param.name, it) },
                singleLine = true,
                isError = row.validationError != null,
                modifier = Modifier.sizeIn(minWidth = 88.dp, maxWidth = 112.dp),
            )
        }
        if (control.presets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                control.presets.take(4).forEach { preset ->
                    val value = preset.value.asPresetText()
                    OutlinedButton(onClick = { actions.onTextChanged(row.param.name, value) }) {
                        Text(preset.label ?: value)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleField(
    row: ParamEditorRowState,
    actions: ParamEditorActions,
) {
    val value = (row.value as? ParamDraftValue.BooleanValue)?.value ?: false
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = value,
            onCheckedChange = { actions.onBooleanChanged(row.param.name, it) },
        )
        Text(if (value) "On" else "Off", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextField(
    row: ParamEditorRowState,
    actions: ParamEditorActions,
    singleLine: Boolean,
) {
    val value = (row.value as? ParamDraftValue.Text)?.value.orEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = { actions.onTextChanged(row.param.name, it) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OptionBackedField(
    row: ParamEditorRowState,
    language: ConnectionLanguage,
    actions: ParamEditorActions,
) {
    val value = (row.value as? ParamDraftValue.Text)?.value.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { actions.onTextChanged(row.param.name, it) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val options = row.options) {
            ParamOptionsState.NotNeeded,
            ParamOptionsState.Idle,
            -> Unit
            ParamOptionsState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp))
                Text(ParamEditorUiCopy.loadingOptions.resolve(language), style = MaterialTheme.typography.bodySmall)
            }
            is ParamOptionsState.Failed -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ParamEditorUiCopy.optionsFailed.resolve(language),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onRetryOptions) {
                    Text(ParamEditorUiCopy.retry.resolve(language))
                }
            }
            is ParamOptionsState.Loaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.options.take(3).forEach { option ->
                        OutlinedButton(onClick = { actions.onTextChanged(row.param.name, option.value) }) {
                            Text(option.label)
                        }
                    }
                }
            }
        }
    }
}

private fun LocalizedString.resolve(language: ConnectionLanguage): String = when (language) {
    ConnectionLanguage.Zh -> zh
    ConnectionLanguage.En -> en ?: zh
}

private fun JsonElement.asPresetText(): String {
    val primitive = this as? JsonPrimitive ?: return toString()
    return primitive.longOrNull?.toString()
        ?: primitive.doubleOrNull?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        ?: primitive.content
}

private object ParamEditorUiCopy {
    val apply = com.comfymobile.presentation.connection.LocalizedText(zh = "应用", en = "Apply")
    val reset = com.comfymobile.presentation.connection.LocalizedText(zh = "重置", en = "Reset")
    val cancel = com.comfymobile.presentation.connection.LocalizedText(zh = "取消", en = "Cancel")
    val close = com.comfymobile.presentation.connection.LocalizedText(zh = "关闭", en = "Close")
    val ok = com.comfymobile.presentation.connection.LocalizedText(zh = "好的", en = "OK")
    val cannotEdit = com.comfymobile.presentation.connection.LocalizedText(zh = "无法编辑", en = "Cannot edit")
    val discardTitle = com.comfymobile.presentation.connection.LocalizedText(zh = "放弃更改？", en = "Discard changes?")
    val discardBody = com.comfymobile.presentation.connection.LocalizedText(
        zh = "当前抽屉里还有未应用的参数更改。",
        en = "This drawer has unapplied parameter changes.",
    )
    val discard = com.comfymobile.presentation.connection.LocalizedText(zh = "放弃", en = "Discard")
    val keepEditing = com.comfymobile.presentation.connection.LocalizedText(zh = "继续编辑", en = "Keep editing")
    val randomSeed = com.comfymobile.presentation.connection.LocalizedText(zh = "随机", en = "Random")
    val lockSeed = com.comfymobile.presentation.connection.LocalizedText(zh = "锁定", en = "Lock")
    val loadingOptions = com.comfymobile.presentation.connection.LocalizedText(zh = "正在加载选项…", en = "Loading options…")
    val optionsFailed = com.comfymobile.presentation.connection.LocalizedText(zh = "选项加载失败", en = "Could not load options")
    val retry = com.comfymobile.presentation.connection.LocalizedText(zh = "重试", en = "Retry")
}

@Preview
@Composable
private fun ParamEditorDrawerPreview() {
    MaterialTheme {
        ParamEditorSheet(
            state = ParamEditorPreviewData.state,
            actions = ParamEditorActions(),
        )
    }
}

private object ParamEditorPreviewData {
    val state = ParamEditorState(
        nodeId = "1",
        nodeClassType = "KSampler",
        nodeTitle = "Sampler",
        descriptor = com.comfymobile.domain.node.NodeDescriptor(
            classType = "KSampler",
            displayName = LocalizedString(zh = "采样器", en = "Sampler"),
            category = "sampler",
            editableParams = emptyList(),
        ),
        rows = listOf(
            ParamEditorRowState(
                param = com.comfymobile.domain.node.ParamDescriptor(
                    name = "seed",
                    displayName = LocalizedString(zh = "随机种子", en = "Seed"),
                    control = ControlType.Integer(),
                ),
                presentation = ParamControlPresentation.Seed,
                value = ParamDraftValue.NumberText("12345"),
                initialValue = ParamDraftValue.NumberText("12345"),
            ),
            ParamEditorRowState(
                param = com.comfymobile.domain.node.ParamDescriptor(
                    name = "steps",
                    displayName = LocalizedString(zh = "步数", en = "Steps"),
                    control = ControlType.Slider(min = 1.0, max = 150.0, step = 1.0),
                ),
                presentation = ParamControlPresentation.Slider,
                value = ParamDraftValue.NumberText("30"),
                initialValue = ParamDraftValue.NumberText("20"),
            ),
        ),
    )
}
