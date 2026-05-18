package com.comfymobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.image.ComfyImageMapper
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
import com.comfymobile.presentation.run.RunCopy
import com.comfymobile.presentation.run.RunRoute
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Root Composable shared between Android and iOS.
 *
 * T1.4b part 3d-ii rewires this from the Phase 1.0 Hello-screen to
 * the live connect flow. T2.3 follow-up adds the MVP loop wiring:
 *
 *   ConnectRoute until an active server exists, then WorkflowLibraryRoute
 *   plus WorkflowImportRoute dialogs.
 *     │
 *     ├─ WorkflowLibraryRoute exposes persisted workflows and the
 *     │    import entry. Until WorkflowGraphRoute lands, tapping a row
 *     │    selects it as the active workflow seam for the Run FAB.
 *     │
 *     ├─ tap Run → AppScreen.Running(envelope) → RunRoute overlay.
 *     │
 *     ├─ RunRoute observes Succeeded → onSuccess(outputs) callback fires;
 *     │    AppScreen.Gallery(promptId, outputs) → OutputGalleryRoute overlay.
 *     │
 *     ├─ RunRoute terminal-sheet Close → onClose → AppScreen.Idle.
 *     │
 *     └─ Gallery back → AppScreen.Idle.
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
            var selectedWorkflow by remember { mutableStateOf<com.comfymobile.domain.workflow.WorkflowRow?>(null) }
            val connectState by viewModel.screenState.collectAsState()
            val importState by importViewModel.state.collectAsState()
            LaunchedEffect(importState.lastImportedRow?.workflowId) {
                importState.lastImportedRow?.let { selectedWorkflow = it }
            }
            val hasActiveServer = connectState.activeServer != null
            val canRun = canShowRunShortcut(
                selectedWorkflowId = selectedWorkflow?.workflowId,
                hasActiveServer = hasActiveServer,
                screen = screen,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasActiveServer) {
                    ConnectRoute(viewModel = viewModel)
                } else {
                    WorkflowLibraryRoute(
                        viewModel = libraryViewModel,
                        onImport = { importViewModel.openSheet() },
                        onWorkflowOpened = { selectedWorkflow = it },
                        onWorkflowDeleted = { deletedWorkflowId ->
                            if (shouldClearSelectedWorkflowAfterDelete(
                                    selectedWorkflowId = selectedWorkflow?.workflowId,
                                    deletedWorkflowId = deletedWorkflowId,
                                )
                            ) {
                                selectedWorkflow = null
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

                // Run FAB visible only on the Idle screen with a loaded
                // workflow. The FAB itself does not call into
                // RunCoordinator — it only sets nav state to Running;
                // RunRoute then drives submission via the coordinator.
                if (canRun) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (!hasActiveServer) return@ExtendedFloatingActionButton
                            val envelope = selectedWorkflow?.envelope ?: return@ExtendedFloatingActionButton
                            screen = AppScreen.Running(envelope)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp),
                    ) {
                        // Localized via the import surface's language —
                        // the Run FAB and the import FAB live on the
                        // same screen so they should resolve the same.
                        Text(RunCopy.run.resolve(importState.language))
                    }
                }

                // Exclusive overlays for the active MVP screen.
                when (val current = screen) {
                    is AppScreen.Idle -> Unit
                    is AppScreen.Running -> RunRoute(
                        workflow = current.envelope,
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
