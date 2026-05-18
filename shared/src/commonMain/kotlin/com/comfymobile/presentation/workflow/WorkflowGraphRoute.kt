package com.comfymobile.presentation.workflow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfymobile.presentation.graph.InteractiveGraphCanvas
import com.comfymobile.presentation.graph.LayoutResult
import com.comfymobile.presentation.graph.NodeRuntimeStatus
import com.comfymobile.presentation.graph.NodeStyleResolver
import com.comfymobile.presentation.graph.NodeTitleSpec
import com.comfymobile.presentation.graph.GraphPalette
import com.comfymobile.presentation.graph.RenderPlan
import com.comfymobile.presentation.graph.RenderPlanBuilder
import com.comfymobile.presentation.parameditor.ParamEditorOverlay
import com.comfymobile.presentation.parameditor.ParamEditorViewModel

/**
 * Phase 2 close-out route per @Ores T2.7 §1.10.
 *
 * Hosts an interactive workflow graph view that ties together three
 * previously-shipped-but-disconnected feature areas:
 *
 *  - T2.1b's [InteractiveGraphCanvas] (gesture-aware canvas)
 *  - T2.2's [ParamEditorViewModel] / [ParamEditorOverlay] (param drawer)
 *  - T2.6's `WorkflowRepository` + `WorkflowLibraryRoute` (library entry)
 *
 * Surface layers (top → bottom):
 *  - top app bar: back arrow + workflow display name
 *  - main canvas: `InteractiveGraphCanvas` bound to the loaded envelope
 *  - bottom-right Run FAB: enabled when connected + envelope submittable
 *  - param drawer overlay: T2.2's [ParamEditorOverlay] in front of the
 *    canvas when a node is opened
 *  - first-frame onboarding tooltip: one-shot per session
 *
 * **What this route does NOT do** (intentional, per Priestess scope):
 *  - Wire itself into [com.comfymobile.App]'s nav. That happens as a
 *    follow-up after Andy's T2.6 PR #39 merges (avoids a three-way
 *    diff). Library tap-row → this route is the LibraryRoute caller's
 *    job (`onTapRow` callback contract).
 *  - Mutate the import overlay's existing Run FAB. Spec calls for the
 *    Run FAB to migrate from import overlay to this route, but that's
 *    an `App.kt` diff handled by the same follow-up.
 *
 * @param viewModel page-scoped [WorkflowGraphViewModel] — the caller
 *   is responsible for resolving it from Koin with the page scope so
 *   that leaving the route cancels the load coroutine. Same lifetime
 *   contract Andy + Lily settled for [ParamEditorViewModel] in PR #25.
 * @param paramEditorViewModel page-scoped [ParamEditorViewModel] — same
 *   lifetime contract. The route forwards long-press / tap-on-selected
 *   events into [ParamEditorViewModel.open] via [LaunchedEffect] on
 *   the VM's pending-event fields.
 * @param onBack invoked when the user taps the back arrow. The caller
 *   decides where to go (typically `LibraryRoute`).
 * @param onRun invoked when the user taps the Run FAB. Receives the
 *   current envelope (which may differ from the originally-loaded row
 *   if ParamEditor has applied changes since). Caller navigates to
 *   [com.comfymobile.presentation.run.RunRoute] with this envelope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowGraphRoute(
    viewModel: WorkflowGraphViewModel,
    paramEditorViewModel: ParamEditorViewModel,
    onBack: () -> Unit,
    onRun: (com.comfymobile.domain.workflow.WorkflowEnvelope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val paramState by paramEditorViewModel.state.collectAsState()
    val paramActions = remember(paramEditorViewModel) { paramEditorViewModel.actions() }

    // Long-press from canvas → open ParamEditor for that node. The VM
    // wraps the hit in a `PendingNodeEvent(seq)` so two presses on the
    // same node still trigger two opens (LaunchedEffect keys on the
    // whole value, not just nodeId).
    LaunchedEffect(state.pendingLongPress) {
        val event = state.pendingLongPress ?: return@LaunchedEffect
        val envelope = state.envelope ?: return@LaunchedEffect
        paramEditorViewModel.open(envelope, event.nodeId)
        viewModel.onConsumePendingLongPress()
    }

    // Tap on already-selected node → re-open ParamEditor (per
    // §2.1 second trigger). Same one-shot consumption pattern.
    LaunchedEffect(state.pendingTapReopen) {
        val event = state.pendingTapReopen ?: return@LaunchedEffect
        val envelope = state.envelope ?: return@LaunchedEffect
        paramEditorViewModel.open(envelope, event.nodeId)
        viewModel.onConsumePendingTapReopen()
    }

    // When ParamEditor applies, push the new envelope back into our
    // VM so the canvas re-renders against the latest state.
    LaunchedEffect(paramState.lastAppliedEnvelope) {
        val applied = paramState.lastAppliedEnvelope ?: return@LaunchedEffect
        viewModel.onEnvelopeApplied(applied)
        paramActions.onConsumeApplied()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.displayName.ifBlank {
                            WorkflowGraphCopy.loading.resolve(state.language)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // Glyph back-arrow until the cross-platform
                        // icon pack is wired (see `InteractiveGraphCanvas`
                        // overlay for the same approach with `⟳`).
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canRun) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val envelope = state.envelope ?: return@ExtendedFloatingActionButton
                        onRun(envelope)
                    },
                ) {
                    Text(WorkflowGraphCopy.run.resolve(state.language))
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            WorkflowGraphContent(state = state, viewModel = viewModel)
            // T2.2 drawer overlay — sheet at bottom + error AlertDialog
            // routing. Z-order: drawn AFTER the canvas so the drawer
            // covers it as expected.
            ParamEditorOverlay(
                state = paramState,
                actions = paramActions,
            )
            // First-frame onboarding hint per §1.9. AlertDialog so the
            // user has to dismiss before continuing; one-shot per VM
            // load() call (clears `firstFrameHintVisible`).
            if (state.firstFrameHintVisible && state.parsedGraph != null) {
                AlertDialog(
                    onDismissRequest = viewModel::onDismissFirstFrameHint,
                    title = null,
                    text = { Text(WorkflowGraphCopy.firstFrameHint.resolve(state.language)) },
                    confirmButton = {
                        TextButton(onClick = viewModel::onDismissFirstFrameHint) {
                            Text(WorkflowGraphCopy.gotIt.resolve(state.language))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkflowGraphContent(
    state: WorkflowGraphScreenState,
    viewModel: WorkflowGraphViewModel,
) {
    when {
        state.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.errorMessage != null && state.parsedGraph == null -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.errorMessage.resolve(state.language),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        state.parsedGraph != null && state.layoutResult != null -> {
            val layout: LayoutResult = state.layoutResult
            InteractiveGraphCanvas(
                layoutResult = layout,
                gestureState = state.gestureState,
                onIntent = viewModel::onIntent,
                buildPlan = { visibleBounds -> state.buildPlan(visibleBounds) },
            )
            Spacer(modifier = Modifier.fillMaxWidth())
        }
        else -> {
            // Defensive fallback: not loading, no error, no parsed
            // graph. Shouldn't occur in normal flows; render an empty
            // surface so the user isn't stuck on a blank box.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = WorkflowGraphCopy.loading.resolve(state.language),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Build a [RenderPlan] for the current state, scoped to [visibleBounds]
 * derived inside the canvas by [InteractiveGraphCanvas] (per @Lily PR
 * #36 viewport-virtualisation contract).
 *
 * Style choices here mirror the production-light path used in
 * `GraphCanvasPreviews`:
 *  - palette: [GraphPalette.defaultLightForTesting] (production
 *    callers wire a theme-derived palette via `rememberGraphPalette`;
 *    for Phase 2 close-out we use the default until the theme palette
 *    seam reaches this route).
 *  - runtime status: all `IDLE` (the route doesn't know about runs;
 *    when a Run starts and the user navigates back here, this route
 *    is no longer in foreground).
 *  - selection: from [WorkflowGraphScreenState.selectedNodeId].
 *
 * Phase-3 polish item: thread an `interactiveLodDowngrade` derived
 * from `state.gestureState.isInteracting` into the builder so edges
 * fall back to straight-line during pan/zoom (the canvas already does
 * this from inside `InteractiveGraphCanvas`'s build closure — we pass
 * it here too for consistency).
 */
private fun WorkflowGraphScreenState.buildPlan(
    visibleBounds: com.comfymobile.presentation.graph.Rect?,
): RenderPlan {
    val parsed = parsedGraph ?: return RenderPlan(emptyList())
    val layout = layoutResult ?: return RenderPlan(emptyList())
    val palette = GraphPalette.defaultLightForTesting
    return RenderPlanBuilder.build(
        graph = parsed,
        layoutResult = layout,
        resolveStyle = { node ->
            NodeStyleResolver.resolve(
                node = node,
                descriptor = null,
                runtimeStatus = NodeRuntimeStatus.IDLE,
                palette = palette,
                isSelected = node.id == selectedNodeId,
            )
        },
        resolvePortStyle = { port -> NodeStyleResolver.resolvePort(port, palette) },
        resolveTitle = { node -> NodeTitleSpec(text = node.classType, italic = false) },
        visibleBounds = visibleBounds,
        graphPalette = palette,
        interactiveLodDowngrade = gestureState.isInteracting,
    )
}

