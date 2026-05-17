package com.comfymobile.presentation.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.comfymobile.domain.workflow.WorkflowEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * Composable shell that owns one [RunViewModel] tied to the screen's
 * lifetime.
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
 * Navigation concerns (back, success → gallery transition) are owned
 * by the caller; this route only renders the surface and forwards
 * intents.
 */
@Composable
fun RunRoute(
    workflow: WorkflowEnvelope?,
    modifier: Modifier = Modifier,
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
    val intents = remember(vm) {
        RunIntents(
            submit = vm::onSubmit,
            requestCancel = vm::requestCancel,
            confirmCancel = vm::confirmCancel,
            dismissCancel = vm::dismissCancel,
            dismissTerminal = vm::dismissTerminal,
        )
    }

    RunScreen(state = state, intents = intents, modifier = modifier)
}
