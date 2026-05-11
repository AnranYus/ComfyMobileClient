package com.comfymobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.workflow.WorkflowImporter
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.presentation.connection.ConnectRoute
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.importer.WorkflowImportRoute
import com.comfymobile.presentation.importer.WorkflowImportViewModel
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Root Composable shared between Android and iOS.
 *
 * T1.4b part 3d-ii rewires this from the Phase 1.0 Hello-screen to
 * the live connect flow:
 *  - Resolves the singleton [ConnectionStateMachineFacade] +
 *    [ServerHistoryStore] from Koin (started by the platform host —
 *    `ComfyMobileApplication` on Android, `MainViewController` on
 *    iOS).
 *  - Creates a [ConnectViewModel] tied to a Compose-managed
 *    [rememberCoroutineScope] so it cancels with the composition
 *    leaving the tree (Activity destroy / SwiftUI scene removal).
 *  - Pulls a fresh [ConnectAttemptCoordinator] from Koin parameterised
 *    on the same VM + scope — mirrors how the AppModule factory was
 *    designed in T1.4b part 3d-i.
 *  - Calls `coordinator.start()` / `coordinator.stop()` from a
 *    `DisposableEffect` so the connect-event observer is alive
 *    exactly while the screen is on the tree.
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

            Box(modifier = Modifier.fillMaxSize()) {
                ConnectRoute(viewModel = viewModel)
                WorkflowImportRoute(
                    viewModel = importViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
