package com.comfymobile.presentation.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.workflow.WorkflowEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * Composable host shell for [RunScreen] + [RunViewModel].
 *
 * Host wiring contract:
 *  - Caller passes in the [workflow] to run (the user's currently-loaded
 *    edited graph). The route stages it via [RunViewModel.prepare]
 *    once per workflow change.
 *  - The [RunViewModel] is resolved from Koin with a screen-scoped
 *    [CoroutineScope] that is cancelled when the composable leaves
 *    the composition (per @Lily PR #29 review pattern: factory VMs
 *    take vmScope, no APP_SCOPE for UI actions).
 *
 * Navigation callbacks:
 *  - [onSuccess]: invoked once when the run reaches
 *    [com.comfymobile.domain.run.RunState.Succeeded] AFTER this route
 *    has been entered. The callback receives both the `promptId` and
 *    the outputs so the host can preserve stable job identity for
 *    downstream features (favorite, share, history deep-link) — per
 *    @Lily PR #32 review msg `5a73db76` blocker 2.
 *  - [onClose]: invoked when the user dismisses the surface (back
 *    button or "Close" on a terminal sheet). The host typically
 *    returns to the workflow / graph view.
 *
 * Both callbacks read off [RunUiState] which is a pure projection of
 * `RunCoordinator.state`. The surface never reaches into
 * [com.comfymobile.domain.run.RunCoordinator] directly for navigation
 * (per @Lily T2.3 follow-up gate 1, msg `39168de4`).
 *
 * **Replay protection** (per @Lily PR #32 review msg `5a73db76` blocker
 * 1): on entry, the route captures the singleton coordinator's
 * currently-displayed Succeeded promptId (if any) as a baseline. The
 * route IGNORES that baseline promptId and fires `onSuccess` only for
 * a promptId that differs from it. Without this, navigating away after
 * a successful run and re-entering RunRoute would immediately bounce
 * the user back to the gallery without a real new submission, because
 * the coordinator (a singleton) still carries the prior Succeeded.
 */
@Composable
fun RunRoute(
    workflow: WorkflowEnvelope?,
    modifier: Modifier = Modifier,
    onSuccess: (promptId: String, outputs: List<JobOutputRef>) -> Unit = { _, _ -> },
    onClose: () -> Unit = {},
) {
    val composeScope = rememberCoroutineScope()
    val vmScope = remember(composeScope) {
        // Build a child scope so we can cancel just this VM's
        // coroutines on dispose without touching the parent.
        CoroutineScope(SupervisorJob(composeScope.coroutineContext[Job]))
    }

    val vm: RunViewModel = koinInject { parametersOf(vmScope) }

    LaunchedEffect(workflow) {
        if (workflow != null) {
            vm.prepare(
                PreparedWorkflow(
                    envelope = workflow,
                    label = workflow.metadata.label,
                )
            )
        }
    }

    DisposableEffect(vmScope) {
        onDispose {
            try {
                vmScope.cancel()
            } catch (_: CancellationException) { /* ignore */ }
        }
    }

    val state by vm.uiState.collectAsState()

    // Baseline captured at entry: if a stale Succeeded is already
    // visible on the singleton coordinator, treat that promptId as
    // "already handled" so we don't bounce the user out to the gallery
    // on a stale value.
    val baselineSucceededPromptId = remember(vm) {
        (vm.uiState.value.phase as? RunUiState.Phase.Succeeded)?.promptId
    }

    // Fire onSuccess exactly once per NEW Succeeded promptId. The
    // LaunchedEffect key still uses the promptId so repeated runs in
    // the same RunRoute composition (rare, but possible if the host
    // doesn't unwind on success) re-fire on each distinct success.
    val succeededPhase = state.phase as? RunUiState.Phase.Succeeded
    LaunchedEffect(succeededPhase?.promptId) {
        val phase = succeededPhase ?: return@LaunchedEffect
        if (phase.promptId == baselineSucceededPromptId) return@LaunchedEffect
        onSuccess(phase.promptId, phase.outputs)
    }

    val intents = remember(vm, onClose) {
        RunIntents(
            submit = vm::onSubmit,
            requestCancel = vm::requestCancel,
            confirmCancel = vm::confirmCancel,
            dismissCancel = vm::dismissCancel,
            // "Close" on a terminal sheet hides the modal AND signals
            // the host to navigate away. Host can choose: stay on
            // RunRoute (no-op onClose) or unwind to the workflow view.
            dismissTerminal = {
                vm.dismissTerminal()
                onClose()
            },
        )
    }

    RunScreen(state = state, intents = intents, modifier = modifier)
}
