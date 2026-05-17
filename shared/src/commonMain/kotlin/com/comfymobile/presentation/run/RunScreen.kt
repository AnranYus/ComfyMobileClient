package com.comfymobile.presentation.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose surface for one run.
 *
 * Stateless wrt the run lifecycle — every dynamic value comes from
 * [state]; every user gesture dispatches through the [intents] callback
 * struct (which in production points at a [RunViewModel] but in tests /
 * previews can be any compatible lambdas).
 *
 * Layout (per T2.7 §3.2 + T0.4 §3.2 §3.5):
 *
 *   ┌─────────────────────────────────────────────┐
 *   │ Banner (B/C only; None hides this row)      │
 *   ├─────────────────────────────────────────────┤
 *   │ Top status row: phase + step bar + Cancel   │
 *   ├─────────────────────────────────────────────┤
 *   │ (graph annotation is on the host's          │
 *   │  GraphCanvas via state.runtimeStatusByNode  │
 *   │  — not drawn here)                          │
 *   ├─────────────────────────────────────────────┤
 *   │ Bottom detail card: current node + progress │
 *   │   + last output thumbnail (optional)        │
 *   └─────────────────────────────────────────────┘
 *
 * Modal overlays:
 *   - Cancel confirmation (destructive sheet)
 *   - Terminal Failed / Cancelled sheet
 *
 * Per @Lily T2.3 second-segment gate 4 (msg `3e9e7269`): the Cancel
 * confirmation calls [RunIntents.confirmCancel] which delegates to
 * `RunCoordinator.requestCancel()` — this surface does NOT touch
 * `/interrupt` or `/queue` endpoints directly.
 */
@Composable
fun RunScreen(
    state: RunUiState,
    intents: RunIntents,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        BranchBanner(state.branchBanner)
        TopStatusRow(state, onCancelClick = intents.requestCancel)
        Spacer(Modifier.height(8.dp))
        ProgressDetailCard(state)
        Spacer(Modifier.height(12.dp))
        BottomBar(state, intents)
    }

    if (state.cancelConfirmOpen) {
        AlertDialog(
            onDismissRequest = intents.dismissCancel,
            title = { Text("确定取消？", fontWeight = FontWeight.SemiBold) },
            text = { Text("已生成的部分将丢失。") },
            confirmButton = {
                TextButton(
                    onClick = intents.confirmCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("取消生成") }
            },
            dismissButton = {
                TextButton(onClick = intents.dismissCancel) { Text("继续运行") }
            },
        )
    }

    when (val terminal = state.terminal) {
        is RunUiState.TerminalView.Failure -> TerminalFailureSheet(terminal, intents)
        is RunUiState.TerminalView.Cancelled -> TerminalCancelledSheet(terminal, intents)
        null -> Unit
    }
}

/**
 * Intent surface separate from [RunViewModel] so previews can wire
 * no-op lambdas without instantiating a coordinator.
 */
data class RunIntents(
    val submit: () -> Unit = {},
    val requestCancel: () -> Unit = {},
    val confirmCancel: () -> Unit = {},
    val dismissCancel: () -> Unit = {},
    val dismissTerminal: () -> Unit = {},
)

// ----------------------------------------------------------------- banner row

@Composable
private fun BranchBanner(banner: RunUiState.BranchBanner) {
    val (text, color, contentColor) = when (banner) {
        is RunUiState.BranchBanner.None -> return
        is RunUiState.BranchBanner.Reconnecting ->
            Triple("网络小波动，正在重连…", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        is RunUiState.BranchBanner.BackgroundResuming ->
            Triple("欢迎回来，正在检查你的生成…", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        is RunUiState.BranchBanner.Offline ->
            Triple("已离线，请检查 Wi-Fi 后重试", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Surface(color = color, contentColor = contentColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
        )
    }
}

// ----------------------------------------------------------------- top status row

@Composable
private fun TopStatusRow(state: RunUiState, onCancelClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phaseLabel(state.phase),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                val sub = phaseSubLabel(state.phase)
                if (sub != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.canCancel) {
                TextButton(
                    onClick = onCancelClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("取消") }
            }
        }
    }
}

private fun phaseLabel(phase: RunUiState.Phase): String = when (phase) {
    is RunUiState.Phase.Idle -> phase.workflowTitle ?: "尚未运行"
    is RunUiState.Phase.Submitting -> "提交中…"
    is RunUiState.Phase.Queued -> "排队中"
    is RunUiState.Phase.Running -> "运行中"
    is RunUiState.Phase.Succeeded -> "已完成"
    is RunUiState.Phase.Failed -> "生成失败"
    is RunUiState.Phase.Cancelled -> "已取消"
}

private fun phaseSubLabel(phase: RunUiState.Phase): String? = when (phase) {
    is RunUiState.Phase.Idle -> null
    is RunUiState.Phase.Submitting -> null
    is RunUiState.Phase.Queued -> "队列位置 #${phase.queuePosition}"
    is RunUiState.Phase.Running ->
        phase.currentNodeDisplayName?.let { "当前节点：$it" } ?: phase.currentNodeId?.let { "当前节点 #$it" }
    is RunUiState.Phase.Succeeded -> "${phase.outputs.size} 张图已生成"
    is RunUiState.Phase.Failed -> null
    is RunUiState.Phase.Cancelled -> null
}

// ----------------------------------------------------------------- progress detail card

@Composable
private fun ProgressDetailCard(state: RunUiState) {
    val running = state.phase as? RunUiState.Phase.Running ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                running.currentNodeDisplayName ?: running.currentNodeId ?: "处理中",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            val progress = running.progress
            if (progress != null && progress.max > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.value.toFloat() / progress.max.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${progress.value} / ${progress.max}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastOutputThumbnail != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "最近产出：${state.lastOutputThumbnail.filename}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- bottom bar

@Composable
private fun BottomBar(state: RunUiState, intents: RunIntents) {
    val showRun = state.canSubmit
    val showRetry = state.phase is RunUiState.Phase.Failed || state.phase is RunUiState.Phase.Cancelled
    if (!showRun && !showRetry) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (showRetry) {
            OutlinedButton(onClick = intents.submit, enabled = state.canSubmit) {
                Text("再试一次")
            }
        } else if (showRun) {
            Button(onClick = intents.submit) {
                Text("运行")
            }
        }
    }
}

// ----------------------------------------------------------------- terminal sheets

@Composable
private fun TerminalFailureSheet(terminal: RunUiState.TerminalView.Failure, intents: RunIntents) {
    AlertDialog(
        onDismissRequest = intents.dismissTerminal,
        title = { Text(terminal.title) },
        text = {
            Column {
                if (terminal.failingNodeDisplayName != null) {
                    Text(
                        "失败节点：${terminal.failingNodeDisplayName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(terminal.message)
            }
        },
        confirmButton = {
            Button(onClick = intents.submit) { Text("再试一次") }
        },
        dismissButton = {
            TextButton(onClick = intents.dismissTerminal) { Text("关闭") }
        },
    )
}

@Composable
private fun TerminalCancelledSheet(terminal: RunUiState.TerminalView.Cancelled, intents: RunIntents) {
    AlertDialog(
        onDismissRequest = intents.dismissTerminal,
        title = { Text("已取消") },
        text = { Text("本次生成已取消。") },
        confirmButton = {
            Button(onClick = intents.submit) { Text("再次运行") }
        },
        dismissButton = {
            TextButton(onClick = intents.dismissTerminal) { Text("关闭") }
        },
    )
}
