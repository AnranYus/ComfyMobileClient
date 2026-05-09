package com.comfymobile.data.di

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyWebSocketClient
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.persistence.JobReconciler
import com.comfymobile.data.persistence.SettingsServerHistoryStore
import com.comfymobile.data.persistence.SqlDelightJobRepository
import com.comfymobile.data.platform.PlatformContext
import com.comfymobile.data.platform.createSettings
import com.comfymobile.data.platform.createSqlDriver
import com.comfymobile.data.platform.nowEpochMs
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.server.ServerHistoryStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Application-scoped Koin module. Builds the long-lived, single-
 * instance commonMain objects: SqlDriver, ComfyMobileDb,
 * repositories, ActiveServerHolder, per-server HTTP / WS factories.
 *
 * **NOT bound here** (deferred to T1.4b part 3d-ii):
 *  - `ConnectionStateReducer` / `ConnectionEffectRunner` /
 *    `ConnectionStateMachine` / `ConnectionStateMachineBootstrap` —
 *    these need a real active-server `ComfyHttpClient` /
 *    `WebSocketSource` (per @Lily PR #18 review msg `75c88c17`,
 *    binding them to a localhost stub here would silently route
 *    history-poll effects to nothing). Part 3d-ii either rebuilds
 *    them per active server or threads `ActiveServerHolder` into
 *    the runner so it picks the right baseUrl at effect-run time.
 *  - `PlatformContext` / `NetworkMonitor` / `LifecycleMonitor` —
 *    bound by platform Koin modules in `androidMain` / `iosMain`.
 *  - `NodeDescriptorRegistry` — its load is suspending
 *    (`Res.readBytes`); App entry point loads + `getKoin().declare`s
 *    it at startup.
 *
 * The Compose `App()` entry point fetches the
 * [com.comfymobile.presentation.connection.ConnectViewModel] factory
 * from this module via `koinInject` (or platform glue) once
 * everything else is bound.
 */
fun appModule(): Module = module {

    // ----------------------------------------------------------------- scope
    /**
     * Application-lifetime [CoroutineScope] used by long-lived
     * components (state machines, monitors, bootstrap). Cancelled
     * when DI is torn down (rare).
     */
    single(qualifier = APP_SCOPE) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    // ----------------------------------------------------------------- persistence
    single<ComfyMobileDb> {
        val context = get<PlatformContext>()
        ComfyMobileDb(driver = createSqlDriver(context))
    }
    single<JobRepository> { SqlDelightJobRepository(db = get()) }

    single<ServerHistoryStore> {
        val context = get<PlatformContext>()
        SettingsServerHistoryStore(settings = createSettings(context))
    }

    // ----------------------------------------------------------------- active server
    /**
     * Single-source-of-truth for "which server is the user connected
     * to right now" (per @Lily PR #16 / #18 reviews — history is
     * candidate list, active server is a different concept).
     * Coordinator sets this on probe success.
     */
    single<ActiveServerHolder> { ActiveServerHolder() }

    // ----------------------------------------------------------------- network
    single<HttpClient>(qualifier = SHARED_HTTP_CLIENT) {
        HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = false
                    }
                )
            }
            install(WebSockets)
        }
    }

    /**
     * Per-server [ComfyHttpClient] factory. UI / coordinator gets a
     * fresh instance keyed by base URL; the underlying HttpClient
     * is shared so the connection pool is reused across servers.
     */
    factory<ComfyHttpClient> { (baseUrl: String) ->
        ComfyHttpClient(
            baseUrl = baseUrl,
            client = get(qualifier = SHARED_HTTP_CLIENT),
        )
    }

    /**
     * Per-server [WebSocketSource]. Same reasoning as ComfyHttpClient
     * — fresh instance per server, shared underlying HttpClient.
     */
    factory<WebSocketSource> { (baseUrl: String) ->
        ComfyWebSocketClient(
            baseUrl = baseUrl,
            httpClient = get(qualifier = SHARED_HTTP_CLIENT),
        )
    }

    // ----------------------------------------------------------------- coordinators / view models
    //
    // Note: ConnectionStateReducer / ConnectionEffectRunner /
    // ConnectionStateMachine / ConnectionStateMachineBootstrap are
    // intentionally NOT bound in this module — they need an
    // active-server-aware HTTP / WS plumbing that part 3d-ii
    // assembles on top of `ActiveServerHolder`. ConnectViewModel and
    // ConnectAttemptCoordinator both depend on the
    // ConnectionStateMachineFacade binding which part 3d-ii will
    // also provide. They are still declared as `factory` here so
    // commonTest compiles; production callers in part 3d-ii must
    // make sure the facade is bound before resolving.

    factory<ConnectAttemptCoordinator> { (vm: com.comfymobile.presentation.connection.ConnectViewModel, vmScope: CoroutineScope) ->
        ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = get(),
            machine = get(),
            activeServer = get(),
            scope = vmScope,
            nowEpochMs = { nowEpochMs() },
            httpClientFor = { baseUrl ->
                get<ComfyHttpClient> { org.koin.core.parameter.parametersOf(baseUrl) }
            },
        )
    }
}

internal val APP_SCOPE = named("app-scope")
internal val SHARED_HTTP_CLIENT = named("shared-http-client")
