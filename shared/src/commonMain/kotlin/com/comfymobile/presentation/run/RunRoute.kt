package com.comfymobile.presentation.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * **Replay protection** (per @Lily PR #32 review msgs `5a73db76` and
 * `cac2df31`): the route binds navigation strictly to a successful
 * submit dispatched FROM THIS route instance, not to any incoming
 * `Succeeded` state. An internal `submittedInThisRoute` flag is armed
 * by [RunIntents.submit] when the user taps Run; the `onSuccess`
 * dispatch consumes the flag exactly once. This guarantees:
 *  - Re-entering RunRoute after a previous successful run no longer
 *    bounces the user back to the gallery (the singleton coordinator's
 *    stale `Succeeded` is ignored because no submit was armed yet).
 *  - A previous attempt to baseline-snapshot at composition entry was
 *    insufficient because `uiState.stateIn(initialValue = Idle, ...)`
 *    publishes the Idle initial value before the coordinator's real
 *    Succeeded propagates — so the baseline read Idle and the next
 *    frame still bounced.
 */
@Composable
fun RunRoute(
    workflow: WorkflowEnvelope?,
    workflowId: String? = null,
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
                    workflowId = workflowId,
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

    // Armed only when the user submits from THIS RunRoute composition.
    // onSuccess consumes (clears) the flag on the next Succeeded phase
    // so a single submit drives at most one navigation. Without this,
    // the singleton coordinator's stale Succeeded would slip through
    // any uiState-derived guard because `stateIn(initialValue = Idle)`
    // publishes Idle first and only later replays the coordinator's
    // real Succeeded — a frame-late race that the baseline approach
    // could not catch.
    val submittedInThisRoute = remember { mutableStateOf(false) }

    val succeededPhase = state.phase as? RunUiState.Phase.Succeeded
    LaunchedEffect(succeededPhase?.promptId) {
        val phase = succeededPhase ?: return@LaunchedEffect
        if (!submittedInThisRoute.value) return@LaunchedEffect
        // Consume the flag BEFORE invoking onSuccess so a re-entry
        // landing on the same Succeeded value (no new submit) does
        // not retrigger navigation.
        submittedInThisRoute.value = false
        onSuccess(phase.promptId, phase.outputs)
    }

    val intents = remember(vm, onClose) {
        RunIntents(
            submit = {
                // Arm the navigation gate BEFORE dispatching the run.
                // Even if the state transitions Submitting → Queued →
                // Running → Succeeded in fast succession, the flag is
                // already armed when LaunchedEffect picks up the
                // terminal Succeeded.
                submittedInThisRoute.value = true
                vm.onSubmit()
            },
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
