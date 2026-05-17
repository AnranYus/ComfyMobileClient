package com.comfymobile.presentation.run

import androidx.compose.foundation.layout.Arrangement
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
 * Localization: every user-facing string comes from [RunCopy] +
 * `state.language`. Connection-banner texts re-use [com.comfymobile.presentation.connection.ConnectionCopy]
 * via [RunCopy] aliases so a copy update lands in one place
 * (per @Ores PR #31 review msg `10846076`).
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
        BranchBanner(state.branchBanner, state.language)
        TopStatusRow(state, onCancelClick = intents.requestCancel)
        Spacer(Modifier.height(8.dp))
        ProgressDetailCard(state)
        Spacer(Modifier.height(12.dp))
        BottomBar(state, intents)
    }

    if (state.cancelConfirmOpen) {
        AlertDialog(
            onDismissRequest = intents.dismissCancel,
            title = {
                Text(
                    RunCopy.cancelConfirmTitle.resolve(state.language),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = { Text(RunCopy.cancelConfirmBody.resolve(state.language)) },
            confirmButton = {
                TextButton(
                    onClick = intents.confirmCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(RunCopy.cancelConfirmConfirm.resolve(state.language)) }
            },
            dismissButton = {
                TextButton(onClick = intents.dismissCancel) {
                    Text(RunCopy.cancelConfirmDismiss.resolve(state.language))
                }
            },
        )
    }

    when (val terminal = state.terminal) {
        is RunUiState.TerminalView.Failure -> TerminalFailureSheet(terminal, state, intents)
        is RunUiState.TerminalView.Cancelled -> TerminalCancelledSheet(terminal, state, intents)
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
private fun BranchBanner(
    banner: RunUiState.BranchBanner,
    language: com.comfymobile.presentation.connection.ConnectionLanguage,
) {
    val (text, color, contentColor) = when (banner) {
        is RunUiState.BranchBanner.None -> return
        is RunUiState.BranchBanner.Reconnecting -> Triple(
            RunCopy.bannerLanFlake.resolve(language),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is RunUiState.BranchBanner.BackgroundResuming -> Triple(
            RunCopy.bannerBackgroundResumed.resolve(language),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        is RunUiState.BranchBanner.Offline -> Triple(
            RunCopy.bannerOffline.resolve(language),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
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
                    text = phaseLabel(state),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                val sub = phaseSubLabel(state)
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
                ) { Text(RunCopy.cancel.resolve(state.language)) }
            }
        }
    }
}

private fun phaseLabel(state: RunUiState): String {
    val lang = state.language
    return when (val phase = state.phase) {
        is RunUiState.Phase.Idle -> phase.workflowTitle ?: RunCopy.phaseIdle.resolve(lang)
        is RunUiState.Phase.Submitting -> RunCopy.phaseSubmitting.resolve(lang)
        is RunUiState.Phase.Queued -> RunCopy.phaseQueued.resolve(lang)
        is RunUiState.Phase.Running -> RunCopy.phaseRunning.resolve(lang)
        is RunUiState.Phase.Succeeded -> RunCopy.phaseSucceeded.resolve(lang)
        is RunUiState.Phase.Failed -> RunCopy.phaseFailed.resolve(lang)
        is RunUiState.Phase.Cancelled -> RunCopy.phaseCancelled.resolve(lang)
    }
}

private fun phaseSubLabel(state: RunUiState): String? {
    val lang = state.language
    return when (val phase = state.phase) {
        is RunUiState.Phase.Idle -> null
        is RunUiState.Phase.Submitting -> null
        is RunUiState.Phase.Queued -> RunCopy.queuePositionLabel(phase.queuePosition, lang)
        is RunUiState.Phase.Running ->
            phase.currentNodeDisplayName?.let { RunCopy.currentNodeLabel(it, lang) }
                ?: phase.currentNodeId?.let { RunCopy.currentNodeIdLabel(it, lang) }
        is RunUiState.Phase.Succeeded -> RunCopy.outputCountLabel(phase.outputs.size, lang)
        is RunUiState.Phase.Failed -> null
        is RunUiState.Phase.Cancelled -> null
    }
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
                running.currentNodeDisplayName
                    ?: running.currentNodeId
                    ?: RunCopy.processing.resolve(state.language),
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
                    RunCopy.lastOutputLine(state.lastOutputThumbnail.filename, state.language),
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
            // Retry CTA reuses the run CTA's enable gate so it goes
            // inactive if the active server was cleared since the run
            // ended (per @Lily PR #31 review msg `18946cd9` blocker 2).
            OutlinedButton(onClick = intents.submit, enabled = state.canSubmit) {
                Text(RunCopy.tryAgain.resolve(state.language))
            }
        } else if (showRun) {
            Button(onClick = intents.submit) {
                Text(RunCopy.run.resolve(state.language))
            }
        }
    }
}

// ----------------------------------------------------------------- terminal sheets

@Composable
private fun TerminalFailureSheet(
    terminal: RunUiState.TerminalView.Failure,
    state: RunUiState,
    intents: RunIntents,
) {
    AlertDialog(
        onDismissRequest = intents.dismissTerminal,
        title = { Text(terminal.title) },
        text = {
            Column {
                if (terminal.failingNodeDisplayName != null) {
                    Text(
                        RunCopy.failingNodeLabel(terminal.failingNodeDisplayName, state.language),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(terminal.message)
            }
        },
        confirmButton = {
            // Same canSubmit gate — if the active server is gone the
            // retry button must NOT silently no-op (@Lily blocker 2).
            Button(onClick = intents.submit, enabled = state.canSubmit) {
                Text(RunCopy.tryAgain.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = intents.dismissTerminal) {
                Text(RunCopy.close.resolve(state.language))
            }
        },
    )
}

@Composable
private fun TerminalCancelledSheet(
    terminal: RunUiState.TerminalView.Cancelled,
    state: RunUiState,
    intents: RunIntents,
) {
    AlertDialog(
        onDismissRequest = intents.dismissTerminal,
        title = { Text(RunCopy.terminalCancelledTitle.resolve(state.language)) },
        text = { Text(RunCopy.terminalCancelledBody.resolve(state.language)) },
        confirmButton = {
            Button(onClick = intents.submit, enabled = state.canSubmit) {
                Text(RunCopy.rerun.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = intents.dismissTerminal) {
                Text(RunCopy.close.resolve(state.language))
            }
        },
    )
}
