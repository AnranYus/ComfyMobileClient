package com.comfymobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.data.run.RunReconciler
import com.comfymobile.data.workflow.WorkflowImporter
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.presentation.connection.ConnectRoute
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.gallery.OutputGalleryRoute
import com.comfymobile.presentation.gallery.OutputGalleryViewModel
import com.comfymobile.presentation.importer.WorkflowImportRoute
import com.comfymobile.presentation.importer.WorkflowImportViewModel
import com.comfymobile.presentation.library.WorkflowLibraryRoute
import com.comfymobile.presentation.library.WorkflowLibraryViewModel
import com.comfymobile.presentation.parameditor.ParamEditorViewModel
import com.comfymobile.presentation.run.RunRoute
import com.comfymobile.presentation.workflow.WorkflowGraphRoute
import com.comfymobile.presentation.workflow.WorkflowGraphViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * Root Composable shared between Android and iOS.
 *
 * Phase 2 close-out nav flow per @Ores T2.7 §1.10 / @Priestess msg
 * `e79c6991`:
 *
 *   ConnectRoute (until active server)
 *     │
 *     └─→ WorkflowLibraryRoute (always-on background once connected)
 *           │
 *           ├─ tap row ───────→ AppScreen.Graph(workflowId)
 *           │                        │
 *           │                        ├─ back arrow → AppScreen.Idle
 *           │                        └─ Run FAB ─→ AppScreen.Running(workflowId, envelope)
 *           │                                          │
 *           │                                          ├─ RunState.Succeeded
 *           │                                          │   → AppScreen.Gallery
 *           │                                          └─ user dismisses
 *           │                                              terminal → Idle
 *           │
 *           └─ import FAB → WorkflowImportRoute dialog
 *
 * Per @Lily T2.3 follow-up gates (msg `39168de4`):
 *  - Navigation never bypasses RunCoordinator (the FAB calls
 *    `vm.onSubmit` via RunRoute; the gallery consumes
 *    `RunState.Succeeded.outputs` via the same VM path).
 *  - Gallery only consumes a stable outputs source.
 *  - B/C banner & history reconciliation do not override the run
 *    terminal — terminal is authoritative, so the host fires
 *    onSuccess off the Succeeded phase only, not off ConnectionState.
 *
 * Per @Ores §1.10 acceptance contract: the Run FAB is the only Run
 * entry point post-T2.6 and it lives inside `WorkflowGraphRoute`. The
 * App.kt bottom-start Run FAB (T2.3 follow-up MVP shortcut) and the
 * `selectedWorkflow` state that drove it were removed in this PR.
 *
 * Process-level lifecycle (state machine + bootstrap start/stop) is
 * the platform host's job, not this composable's. Per @Lily PR #18
 * thread (`62385887`): rotation must NOT cancel the state machine,
 * so `ConnectionStateMachine.start` happens in `Application.onCreate`
 * (Android) or app boot (iOS), never in this Composable.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val koin = getKoin()
            // Singletons resolved once per composition root.
            val machine = remember { koin.get<ConnectionStateMachineFacade>() }
            val historyStore = remember { koin.get<ServerHistoryStore>() }
            val activeServer = remember { koin.get<ActiveServerHolder>() }

            // VM-scoped collaborators — keyed on `scope` so that if
            // the composition is recreated with a fresh scope (e.g.
            // process death restoration) the VM and Coordinator
            // rebuild together.
            val scope = rememberCoroutineScope()
            val viewModel = remember(scope, machine, historyStore, activeServer) {
                ConnectViewModel(
                    machine = machine,
                    historyStore = historyStore,
                    scope = scope,
                    activeServer = activeServer,
                )
            }
            val coordinator = remember(scope, viewModel) {
                koin.get<ConnectAttemptCoordinator> {
                    parametersOf(viewModel, scope)
                }
            }
            val importViewModel = remember(scope) {
                WorkflowImportViewModel(
                    importerFactory = { koin.get<WorkflowImporter>() },
                    scope = scope,
                )
            }
            val libraryViewModel = remember(scope) {
                koin.get<WorkflowLibraryViewModel> { parametersOf(scope) }
            }
            DisposableEffect(coordinator) {
                coordinator.start()
                onDispose { coordinator.stop() }
            }

            // B/C ghost-state reconciliation. Side-channel /history
            // probing for an in-flight RunCoordinator run when WS is
            // unreliable. Idempotent start/stop; lives off the
            // process-lifetime APP_SCOPE so it survives composition
            // rebuilds (per @Lily T2.3 follow-up gate 3 + Bootstrap
            // pattern from PR #18 thread `62385887`).
            val runReconciler = remember { koin.get<RunReconciler>() }
            DisposableEffect(runReconciler) {
                runReconciler.start()
                onDispose {
                    // RunReconciler.stop is suspend; fire from app
                    // scope. The reconciler's observers are tied to
                    // APP_SCOPE so they'll be cancelled there too,
                    // making this stop() idempotent.
                    scope.launch { runReconciler.stop() }
                }
            }

            // ----------------------------------------------------------------- MVP nav state
            var screen by remember { mutableStateOf<AppScreen>(AppScreen.Idle) }
            val connectState by viewModel.screenState.collectAsState()
            val hasActiveServer = connectState.activeServer != null

            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasActiveServer) {
                    ConnectRoute(viewModel = viewModel)
                } else {
                    WorkflowLibraryRoute(
                        viewModel = libraryViewModel,
                        onImport = { importViewModel.openSheet() },
                        // Per @Ores T2.7 §1.10: tap-row destination is
                        // WorkflowGraphRoute. The library VM owns the
                        // `markOpened` side effect; we just navigate.
                        onWorkflowOpened = { row ->
                            screen = AppScreen.Graph(
                                workflowId = row.workflowId,
                                displayName = row.displayName,
                            )
                        },
                        onWorkflowDeleted = { deletedWorkflowId ->
                            // If the user is currently inside the Graph
                            // view for the workflow that's being
                            // deleted, pop back to Library so they don't
                            // see a broken "not found" state on the next
                            // re-render. See `shouldPopGraphAfterDelete`.
                            if (shouldPopGraphAfterDelete(screen, deletedWorkflowId)) {
                                screen = AppScreen.Idle
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                WorkflowImportRoute(
                    viewModel = importViewModel,
                    modifier = Modifier.fillMaxSize(),
                    showFab = false,
                )

                // Exclusive overlays for the active MVP screen.
                when (val current = screen) {
                    is AppScreen.Idle -> Unit
                    is AppScreen.Graph -> AppGraphOverlay(
                        workflowId = current.workflowId,
                        displayName = current.displayName,
                        onBack = { screen = AppScreen.Idle },
                        onRun = { envelope ->
                            screen = AppScreen.Running(
                                workflowId = current.workflowId,
                                envelope = envelope,
                            )
                        },
                    )
                    is AppScreen.Running -> RunRoute(
                        workflow = current.envelope,
                        workflowId = current.workflowId,
                        onSuccess = { promptId, outputs ->
                            // Per @Lily PR #32 review msg `5a73db76`
                            // blocker 2: preserve stable job identity
                            // through to the gallery for downstream
                            // favorite / share / history features.
                            screen = AppScreen.Gallery(
                                promptId = promptId,
                                outputs = outputs,
                            )
                        },
                        // On run-terminal close we drop the Graph
                        // overlay too: returning to Library is the
                        // simplest mental model and avoids stale graph
                        // state if the user just applied params and
                        // came back from a failure. Phase 3 may revisit
                        // (return-to-graph option).
                        onClose = { screen = AppScreen.Idle },
                        modifier = Modifier.fillMaxSize(),
                    )
                    is AppScreen.Gallery -> AppGalleryOverlay(
                        outputs = current.outputs,
                        promptId = current.promptId,
                        activeServer = activeServer,
                        koin = koin,
                        scope = scope,
                        onBack = { screen = AppScreen.Idle },
                    )
                }
            }
        }
    }
}

/**
 * Thin wrapper that resolves a page-scoped [WorkflowGraphViewModel] +
 * [ParamEditorViewModel] pair from Koin and feeds them to
 * [WorkflowGraphRoute]. Both VMs share a single page scope so they're
 * cancelled together when the user leaves the Graph overlay.
 *
 * Per @Ores T2.7 §1.10 page-scope contract (and @Lily PR #25 / PR #40
 * reviews): leaving the Graph overlay (back arrow / Run FAB) MUST
 * cancel both the workflow-load coroutine and any in-flight
 * `ParamOptionProvider.load` request. The `SupervisorJob` parented to
 * the composable scope gives us exactly that — when the overlay
 * leaves composition, the parent job is cancelled, which cancels
 * `pageScope`, which cancels both VMs' coroutines.
 */
@Composable
private fun AppGraphOverlay(
    workflowId: String,
    displayName: String,
    onBack: () -> Unit,
    onRun: (com.comfymobile.domain.workflow.WorkflowEnvelope) -> Unit,
) {
    val composeScope = rememberCoroutineScope()
    val pageScope = remember(composeScope) {
        CoroutineScope(SupervisorJob(composeScope.coroutineContext[Job]))
    }
    val graphVm: WorkflowGraphViewModel =
        koinInject { parametersOf(pageScope) }
    val paramVm: ParamEditorViewModel =
        koinInject { parametersOf(pageScope) }

    // Load on (re)compose for this workflowId. Internal generation
    // guards inside the VM make repeated calls with the same id safe.
    DisposableEffect(graphVm, workflowId) {
        graphVm.load(workflowId)
        onDispose { /* page scope cancellation handles teardown */ }
    }

    // Bridge `ConnectionState` → graph VM's connected flag so the Run
    // FAB enables when the server is reachable. We keep this
    // observation out of the VM so its unit tests don't need a state
    // machine fixture (per WorkflowGraphViewModel design comment).
    val koin = getKoin()
    val connectMachine = remember { koin.get<ConnectionStateMachineFacade>() }
    val connectionState by connectMachine.currentState.collectAsState()
    LaunchedEffectConnected(connectionState, graphVm)

    // `displayName` is captured by the host nav as a hint so the top
    // bar doesn't flash empty during the suspending load. We don't
    // need to propagate it into the VM today — the VM's load() sets
    // displayName once getById returns — but it stays in
    // [AppScreen.Graph] for a future enhancement that surfaces a
    // header skeleton.
    @Suppress("UNUSED_PARAMETER")
    val displayNameAnchor = displayName

    WorkflowGraphRoute(
        viewModel = graphVm,
        paramEditorViewModel = paramVm,
        onBack = onBack,
        onRun = onRun,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Compose-side helper that maps `ConnectionState.isConnected` (proxy:
 * any state subclass that is `Connected`) → `graphVm.setConnected`.
 * Keeps the boolean-pump pattern out of the route Composable.
 */
@Composable
private fun LaunchedEffectConnected(
    state: com.comfymobile.data.network.ConnectionState,
    vm: WorkflowGraphViewModel,
) {
    val connected = state is com.comfymobile.data.network.ConnectionState.Connected
    androidx.compose.runtime.LaunchedEffect(connected) {
        vm.setConnected(connected)
    }
}

/**
 * Thin wrapper that resolves an [OutputGalleryViewModel] from Koin
 * (scoped to the App scope so the gallery survives configuration
 * changes) and feeds it the just-completed run's outputs.
 *
 * Per @Lily T2.3 follow-up gate 2 (msg `39168de4`): gallery only ever
 * consumes the immediate Succeeded outputs (passed in here as
 * `JobOutputRef` from `RunState.Succeeded.outputs`) or the historical
 * `JobRepository.observeByServer + Job.firstOutput` source. There is
 * NO third source.
 */
@Composable
private fun AppGalleryOverlay(
    outputs: List<JobOutputRef>,
    promptId: String?,
    activeServer: ActiveServerHolder,
    koin: org.koin.core.Koin,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    val galleryVm = remember(scope) {
        koin.get<OutputGalleryViewModel> { parametersOf(scope) }
    }
    val imageLoader = remember { koin.get<ImageLoader>() }

    // Hand the gallery the outputs to render. Conversion is trivial —
    // both types are { filename, subfolder, type }; the data layer's
    // ComfyOutputRef is what ImageMapper expects.
    val galleryRefs = remember(outputs) {
        outputs.map { ComfyOutputRef(it.filename, it.subfolder, it.type) }
    }
    DisposableEffect(galleryRefs, promptId) {
        galleryVm.show(
            outputs = galleryRefs,
            promptId = promptId,
        )
        onDispose { /* gallery VM state clears on next show() */ }
    }

    OutputGalleryRoute(
        viewModel = galleryVm,
        imageLoader = imageLoader,
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
    )
}
