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
 *  - [onSuccess]: invoked exactly once when the run reaches
 *    [com.comfymobile.domain.run.RunState.Succeeded]. The host typically
 *    transitions to the output gallery with the supplied outputs.
 *  - [onClose]: invoked when the user dismisses the surface (back
 *    button or "Close" on a terminal sheet). The host typically
 *    returns to the workflow / graph view.
 *
 * Both callbacks are wired through the same `state.terminal` observation
 * — the surface never reaches into [com.comfymobile.domain.run.RunCoordinator]
 * directly for navigation decisions. Per @Lily T2.3 follow-up gate 1
 * (msg `39168de4`): navigation does NOT bypass the coordinator.
 */
@Composable
fun RunRoute(
    workflow: WorkflowEnvelope?,
    modifier: Modifier = Modifier,
    onSuccess: (List<JobOutputRef>) -> Unit = {},
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

    // Drive the host's onSuccess callback off the Succeeded phase. Use
    // promptId as the LaunchedEffect key so we fire exactly once per
    // successful run (re-running on the same RunRoute would produce a
    // new promptId and re-trigger the navigation).
    val succeededPhase = state.phase as? RunUiState.Phase.Succeeded
    LaunchedEffect(succeededPhase?.promptId) {
        if (succeededPhase != null) {
            onSuccess(succeededPhase.outputs)
        }
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
