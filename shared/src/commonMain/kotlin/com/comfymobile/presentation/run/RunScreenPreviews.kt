package com.comfymobile.presentation.run

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.run.RunError
import com.comfymobile.domain.run.RunState
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compose previews exercising [RunScreen] across the major phase × banner
 * combinations. Per the established T2.x preview pattern (T2.1a, T2.5):
 * previews use the pure [RunUiStateMapper] so the rendering is exactly
 * what the live VM would produce, with no fake data short-circuits.
 *
 * Lily PR #30 deterministic-gate equivalent: these previews encode all
 * the visible state combinations (Idle / Submitting / Queued / Running
 * with progress / Running with thumb / Succeeded / Failed / Cancelled),
 * plus the three banner kinds (Reconnecting / BackgroundResuming /
 * Offline) layered on top of a Running state to verify gate-2
 * (banner ≠ run terminal).
 */

private val NoOpIntents = RunIntents()

private fun makeState(
    runState: RunState,
    connectionState: ConnectionState = ConnectionState.Connected,
    hasPreparedWorkflow: Boolean = true,
    hasActiveServer: Boolean = true,
    cancelConfirmOpen: Boolean = false,
    workflowTitle: String? = "SDXL · Text-to-Image",
    nodeDisplay: Map<String, String> = mapOf("3" to "KSampler", "5" to "VAEDecode", "7" to "SaveImage"),
): RunUiState = RunUiStateMapper.project(
    runState = runState,
    connectionState = connectionState,
    hasPreparedWorkflow = hasPreparedWorkflow,
    hasActiveServer = hasActiveServer,
    cancelConfirmOpen = cancelConfirmOpen,
    workflowTitle = workflowTitle,
    nodeDisplayNameByNodeId = { nodeDisplay[it] },
)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface {
            content()
        }
    }
}

@Preview
@Composable
private fun RunScreenIdlePreview() = PreviewFrame {
    RunScreen(
        state = makeState(RunState.Idle),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenSubmittingPreview() = PreviewFrame {
    RunScreen(
        state = makeState(RunState.Submitting),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenQueuedPreview() = PreviewFrame {
    RunScreen(
        state = makeState(RunState.Queued(promptId = "p-1", queuePosition = 3)),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenRunningWithProgressPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Running(
                promptId = "p-1",
                currentNodeId = "3",
                currentNodeDisplayName = "KSampler",
                cachedNodes = setOf("1"),
                completedNodes = setOf("2"),
                nodeProgress = RunState.NodeProgress(nodeId = "3", value = 12, max = 20),
            ),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenRunningWithThumbnailPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Running(
                promptId = "p-1",
                currentNodeId = "7",
                currentNodeDisplayName = "SaveImage",
                cachedNodes = setOf("1"),
                completedNodes = setOf("2", "3", "5"),
                firstOutput = JobOutputRef("ComfyUI_00012_.png", "", "output"),
            ),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenBranchBReconnectingPreview() = PreviewFrame {
    // Gate 2 visual: B-branch banner layered above the Running phase.
    // The run terminal would still come through if/when the server
    // reports it; banner does not suppress run state.
    RunScreen(
        state = makeState(
            RunState.Running(
                promptId = "p-1",
                currentNodeId = "3",
                currentNodeDisplayName = "KSampler",
                nodeProgress = RunState.NodeProgress(nodeId = "3", value = 7, max = 20),
            ),
            connectionState = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenBranchCBackgroundResumedPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Running(
                promptId = "p-1",
                currentNodeId = "3",
                currentNodeDisplayName = "KSampler",
            ),
            connectionState = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenOfflineBannerWithSucceededPreview() = PreviewFrame {
    // Gate 2: even with Offline banner showing, the Succeeded terminal
    // is authoritative — the Phase is Succeeded, not "stuck running".
    RunScreen(
        state = makeState(
            RunState.Succeeded(
                promptId = "p-1",
                outputs = listOf(JobOutputRef("ComfyUI_00012_.png", "", "output")),
            ),
            connectionState = ConnectionState.Lost(ConnectError.REFUSED),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenFailedNodeExceptionPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Failed(
                promptId = "p-1",
                error = RunError.NodeException(
                    nodeId = "3",
                    nodeType = "KSampler",
                    exceptionMessage = "CUDA out of memory: Tried to allocate 4.00 GiB",
                    exceptionType = "torch.cuda.OutOfMemoryError",
                ),
            ),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenCancelledPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Cancelled(promptId = "p-1", fromNodeId = "3"),
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenCancelConfirmOpenPreview() = PreviewFrame {
    RunScreen(
        state = makeState(
            RunState.Running(
                promptId = "p-1",
                currentNodeId = "3",
                currentNodeDisplayName = "KSampler",
            ),
            cancelConfirmOpen = true,
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Preview
@Composable
private fun RunScreenNoWorkflowDisabledRunPreview() = PreviewFrame {
    // Gate 1: no prepared workflow → canSubmit is false → no Run CTA.
    RunScreen(
        state = makeState(
            RunState.Idle,
            hasPreparedWorkflow = false,
        ),
        intents = NoOpIntents,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}
