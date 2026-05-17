package com.comfymobile.data.di

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.connect.SystemStatsProbe
import com.comfymobile.data.connection.ConnectionStateMachine
import com.comfymobile.data.connection.ConnectionStateMachineBootstrap
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyWebSocketClient
import com.comfymobile.data.network.ConnectionEffectRunner
import com.comfymobile.data.network.ConnectionStateReducer
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.persistence.InMemoryWorkflowRepository
import com.comfymobile.data.persistence.SettingsServerHistoryStore
import com.comfymobile.data.persistence.SqlDelightJobRepository
import com.comfymobile.data.platform.PlatformContext
import com.comfymobile.data.platform.createSettings
import com.comfymobile.data.platform.createSqlDriver
import com.comfymobile.data.platform.nowEpochMs
import com.comfymobile.data.workflow.WorkflowImporter
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.image.ComfyImageMapper
import com.comfymobile.data.image.PreviewFormat
import com.comfymobile.data.image.PreviewSpec
import com.comfymobile.data.image.createComfyImageLoader
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.workflow.WorkflowRepository
import com.comfymobile.presentation.history.ComfyHistoryThumbnailMapper
import com.comfymobile.presentation.history.HistoryThumbnailMapper
import com.comfymobile.presentation.history.HistoryViewModel
import com.comfymobile.presentation.gallery.DisabledOutputGalleryActionGateway
import com.comfymobile.presentation.gallery.OutputGalleryActionGateway
import com.comfymobile.presentation.gallery.OutputGalleryViewModel
import com.comfymobile.presentation.parameditor.ActiveServerParamOptionProvider
import com.comfymobile.presentation.parameditor.ParamEditorViewModel
import com.comfymobile.presentation.parameditor.ParamOptionProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import coil3.ImageLoader
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
 * repositories, ActiveServerHolder, per-server HTTP / WS factories,
 * and (since T1.4b part 3d-ii) the connection state-machine triple
 * (Reducer / Runner / Machine / Bootstrap).
 *
 * **NOT bound here** (platform Koin modules supply these):
 *  - `PlatformContext` — Android binds it with `applicationContext`,
 *    iOS with the empty placeholder actual.
 *  - `NetworkMonitor` / `LifecycleMonitor` — Android binds
 *    `AndroidNetworkMonitor` + `AndroidLifecycleMonitor`; iOS binds
 *    `IosNetworkMonitor` + `IosLifecycleMonitor`. Both interfaces
 *    so commonMain can depend on them via DI without expect/actual.
 *  - `NodeDescriptorRegistry` — its load is suspending
 *    (`Res.readBytes`); App entry point loads + `getKoin().declare`s
 *    it at startup.
 *
 * The Compose `App()` entry point (or `MainActivity` /
 * `iOSApp.swift`) fetches the
 * [com.comfymobile.presentation.connection.ConnectViewModel] factory
 * from this module via Koin once everything else is bound, and is
 * also responsible for calling
 * [ConnectionStateMachineBootstrap.start] / `.stop` at the right
 * lifecycle points. Per @Lily PR #18 thread (`62385887`):
 * `start()` MUST be explicit and idempotent, never via `init { }`.
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
    single<WorkflowRepository> { InMemoryWorkflowRepository() }

    factory<WorkflowImporter> {
        WorkflowImporter(
            repository = get(),
            nowEpochMs = { nowEpochMs() },
            descriptorRegistry = runCatching { get<NodeDescriptorRegistry>() }.getOrNull(),
        )
    }

    single<ParamOptionProvider> {
        ActiveServerParamOptionProvider(
            activeServer = get(),
            httpClientFactory = { server ->
                get<ComfyHttpClient> { org.koin.core.parameter.parametersOf(server.baseUrl) }
            },
        )
    }

    single<HistoryThumbnailMapper> {
        val activeServerHolder = get<ActiveServerHolder>()
        ComfyHistoryThumbnailMapper(
            imageMapper = ComfyImageMapper(
                activeBaseUrlProvider = { activeServerHolder.current.value?.baseUrl },
                defaultPreview = PreviewSpec(
                    format = PreviewFormat.JPEG,
                    quality = 80,
                ),
            ),
        )
    }

    single<ImageLoader> {
        createComfyImageLoader(
            context = get(),
            httpClient = get(qualifier = SHARED_HTTP_CLIENT),
        )
    }

    single<OutputGalleryActionGateway> { DisabledOutputGalleryActionGateway }

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

    // ----------------------------------------------------------------- state machine triple
    //
    // Per @Lily PR #18 thread (`60a7e64a`): the Runner is
    // active-server-aware via factories on the active server. With no
    // active server it emits `ConnectError.NO_ACTIVE_SERVER` and does
    // not touch IO; on active-server change it cancels in-flight
    // server-bound IO so the new baseUrl takes over cleanly. The DI
    // wiring below preserves that contract by passing
    // `ActiveServerHolder` + per-server factories instead of locking
    // a single baseUrl in.

    single<ConnectionStateReducer> {
        ConnectionStateReducer(
            // Stable client-id per process. The Ktor WebSocket
            // /ws?clientId=... handshake uses this; ComfyUI doesn't
            // require it to be a UUID, just a stable identifier.
            // For now we generate a per-process token; a future
            // change can persist this in Settings if needed.
            clientIdProvider = { CLIENT_ID_PER_PROCESS },
        )
    }

    single<ConnectionEffectRunner> {
        ConnectionEffectRunner(
            activeServer = get(),
            httpClientFactory = { server ->
                get<ComfyHttpClient> { org.koin.core.parameter.parametersOf(server.baseUrl) }
            },
            webSocketSourceFactory = { server ->
                get<WebSocketSource> { org.koin.core.parameter.parametersOf(server.baseUrl) }
            },
            scope = get(qualifier = APP_SCOPE),
        )
    }

    /**
     * The state machine is the lifecycle owner of the reducer +
     * runner pair, exposing [ConnectionStateMachineFacade] to the UI
     * so previews / tests can substitute a fake without depending on
     * Ktor or the runner internals.
     *
     * Bound under both [ConnectionStateMachine] (concrete type, for
     * platform code that needs `start`/`stop`) and
     * [ConnectionStateMachineFacade] (interface, for UI / coordinator)
     * so callers depend on the right shape.
     */
    single<ConnectionStateMachine> {
        ConnectionStateMachine(
            reducer = get(),
            runner = get(),
            scope = get(qualifier = APP_SCOPE),
        )
    }
    single<ConnectionStateMachineFacade> { get<ConnectionStateMachine>() }

    /**
     * Translates platform monitor flows into state machine inputs.
     * `start()` is idempotent; the App entry point calls it once on
     * cold-start and `stop()` on terminate. Per @Lily PR #18 thread
     * (`62385887`): no implicit `init { start() }` — explicit
     * lifecycle handoff keeps "is this subscribed?" verifiable.
     */
    single<ConnectionStateMachineBootstrap> {
        ConnectionStateMachineBootstrap(
            machine = get(),
            networkMonitor = get<NetworkMonitor>(),
            lifecycleMonitor = get<LifecycleMonitor>(),
            scope = get(qualifier = APP_SCOPE),
        )
    }

    // ----------------------------------------------------------------- coordinators / view models

    factory<ConnectAttemptCoordinator> { (vm: com.comfymobile.presentation.connection.ConnectViewModel, vmScope: CoroutineScope) ->
        ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = get(),
            machine = get(),
            activeServer = get(),
            scope = vmScope,
            nowEpochMs = { nowEpochMs() },
            probe = SystemStatsProbe { baseUrl ->
                get<ComfyHttpClient> { org.koin.core.parameter.parametersOf(baseUrl) }
                    .getSystemStats()
            },
        )
    }

    factory<ParamEditorViewModel> { (vmScope: CoroutineScope) ->
        ParamEditorViewModel(
            registry = get(),
            optionProvider = get(),
            scope = vmScope,
            nowEpochMs = { nowEpochMs() },
        )
    }

    factory<HistoryViewModel> { (vmScope: CoroutineScope) ->
        HistoryViewModel(
            repository = get(),
            activeServer = get(),
            scope = vmScope,
            nowEpochMs = { nowEpochMs() },
            thumbnailMapper = get(),
        )
    }

    factory<OutputGalleryViewModel> { (vmScope: CoroutineScope) ->
        val activeServerHolder = get<ActiveServerHolder>()
        OutputGalleryViewModel(
            imageMapper = ComfyImageMapper(
                activeBaseUrlProvider = { activeServerHolder.current.value?.baseUrl },
            ),
            jobRepository = get(),
            actionGateway = get(),
            scope = vmScope,
        )
    }
}

internal val APP_SCOPE = named("app-scope")
internal val SHARED_HTTP_CLIENT = named("shared-http-client")

/**
 * Stable per-process client id for ComfyUI's `/ws?clientId=...`
 * handshake. Generated lazily on first read; survives the lifetime of
 * the [appModule]'s [APP_SCOPE].
 *
 * ComfyUI does not require the value to be UUID-shaped — any stable
 * non-empty string works — so a process-bound counter+millis token is
 * sufficient and avoids a `kotlin.uuid` dependency. If a future
 * change wants the id to survive process restarts, persist it in
 * `Settings` and read it from there.
 */
private val CLIENT_ID_PER_PROCESS: String by lazy {
    "comfymobile-" + nowEpochMs().toString(radix = 36)
}
