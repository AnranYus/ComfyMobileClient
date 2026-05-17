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
import com.comfymobile.data.workflow.WorkflowImporter
import com.comfymobile.domain.job.JobOutputRef
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.presentation.connection.ConnectRoute
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.gallery.OutputGalleryActionGateway
import com.comfymobile.presentation.gallery.OutputGalleryRoute
import com.comfymobile.presentation.gallery.OutputGalleryViewModel
import com.comfymobile.presentation.importer.WorkflowImportRoute
import com.comfymobile.presentation.importer.WorkflowImportViewModel
import com.comfymobile.presentation.run.RunCopy
import com.comfymobile.presentation.run.RunRoute
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Root Composable shared between Android and iOS.
 *
 * T1.4b part 3d-ii rewires this from the Phase 1.0 Hello-screen to
 * the live connect flow. T2.3 follow-up adds the MVP loop wiring:
 *
 *   ConnectRoute (always) + WorkflowImportRoute (always overlay)
 *     │
 *     ├─ when a workflow is imported (importViewModel.state.lastImportedRow != null):
 *     │    show a "Run" FAB (bottom-start to avoid collision with the
 *     │    import FAB at bottom-end).
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
            DisposableEffect(coordinator) {
                coordinator.start()
                onDispose { coordinator.stop() }
            }

            // ----------------------------------------------------------------- MVP nav state
            var screen by remember { mutableStateOf<AppScreen>(AppScreen.Idle) }
            val importState by importViewModel.state.collectAsState()
            val canRun = importState.lastImportedRow != null

            Box(modifier = Modifier.fillMaxSize()) {
                ConnectRoute(viewModel = viewModel)
                WorkflowImportRoute(
                    viewModel = importViewModel,
                    modifier = Modifier.fillMaxSize(),
                )

                // Run FAB visible only on the Idle screen with a loaded
                // workflow. The FAB itself does not call into
                // RunCoordinator — it only sets nav state to Running;
                // RunRoute then drives submission via the coordinator.
                if (canRun && screen is AppScreen.Idle) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val envelope = importState.lastImportedRow?.envelope ?: return@ExtendedFloatingActionButton
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
                        onSuccess = { outputs ->
                            // Find the most-recent RunCoordinator.state to
                            // capture promptId for the gallery — but per
                            // gate 1, we don't reach into it. The outputs
                            // already in the callback ARE Succeeded's.
                            // promptId is derivable from the latest Job in
                            // the repo by Andy's T2.4 second segment; for
                            // now we leave it null.
                            screen = AppScreen.Gallery(
                                promptId = null,
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
