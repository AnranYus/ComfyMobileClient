package com.comfymobile.data.di

import com.comfymobile.data.connect.ConnectAttemptCoordinator
import com.comfymobile.data.connection.ConnectionStateMachine
import com.comfymobile.data.connection.ConnectionStateMachineBootstrap
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.data.descriptor.NodeDescriptorLoader
import com.comfymobile.data.descriptor.NodeDescriptorRegistry
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyWebSocketClient
import com.comfymobile.data.network.ConnectionEffectRunner
import com.comfymobile.data.network.ConnectionStateReducer
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.persistence.JobReconciler
import com.comfymobile.data.persistence.SettingsServerHistoryStore
import com.comfymobile.data.persistence.SqlDelightJobRepository
import com.comfymobile.data.platform.PlatformContext
import com.comfymobile.data.platform.createSettings
import com.comfymobile.data.platform.createSqlDriver
import com.comfymobile.data.platform.nowEpochMs
import com.comfymobile.db.ComfyMobileDb
import com.comfymobile.domain.connection.LifecycleMonitor
import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.job.JobRepository
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.connection.ConnectionLanguage
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
 * instance objects: SqlDriver, ComfyMobileDb, repositories,
 * monitors, descriptor registry, connection state machine + bootstrap.
 *
 * Platform Koin modules (see `androidMain` / `iosMain`) supply:
 *   - `PlatformContext` (singleton, holding Application Context on Android)
 *   - `NetworkMonitor` (AndroidNetworkMonitor / IosNetworkMonitor)
 *   - `LifecycleMonitor` (AndroidLifecycleMonitor / IosLifecycleMonitor)
 *
 * The Compose `App()` entry point fetches the [ConnectViewModel]
 * factory from this module via `koinInject` (or platform glue).
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

    // ----------------------------------------------------------------- descriptor
    /**
     * NodeDescriptorRegistry is loaded lazily on first request; the
     * lambda runs `Res.readBytes` which is a suspend function.
     * Wrap it in a runBlocking-equivalent when DI consumers need it
     * synchronously, or expose an async loader. For Phase 1 we
     * provide a placeholder factory; T1.5 wiring path makes this a
     * lazy holder.
     *
     * v1: skip auto-load here and require the caller to load
     * explicitly via `NodeDescriptorLoader.load()` in commonMain.
     * The registry is bound as a single once produced.
     */
    // The registry is intentionally NOT created in this module —
    // wiring it requires a suspending Compose-resources read. The
    // App entry point loads it once and registers it via
    // `getKoin().declare(...)` at startup.

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

    // ----------------------------------------------------------------- connection state machine

    /**
     * The reducer is parameterless (other than its config) so a
     * single instance is fine. Tests substitute their own.
     */
    single<ConnectionStateReducer> {
        ConnectionStateReducer(
            clientIdProvider = { /* TODO: persistent UUID */ "client-uuid-stub" },
        )
    }

    /**
     * Default [ComfyHttpClient] used by the runner's history-poll
     * effect. Production substitutes the active-server client when
     * one is connected; until then we wire to a 'localhost' default
     * which never responds (poll returns failures, harmless).
     */
    single<ComfyHttpClient>(qualifier = DEFAULT_HTTP_CLIENT) {
        get<ComfyHttpClient> { org.koin.core.parameter.parametersOf("http://localhost:8188") }
    }

    /**
     * Default [WebSocketSource] using the same localhost stub.
     */
    single<WebSocketSource>(qualifier = DEFAULT_WS) {
        get<WebSocketSource> { org.koin.core.parameter.parametersOf("http://localhost:8188") }
    }

    single<ConnectionEffectRunner> {
        ConnectionEffectRunner(
            http = get(qualifier = DEFAULT_HTTP_CLIENT),
            ws = get(qualifier = DEFAULT_WS),
            scope = get(qualifier = APP_SCOPE),
        )
    }

    single<ConnectionStateMachine> {
        ConnectionStateMachine(
            reducer = get(),
            runner = get(),
            scope = get(qualifier = APP_SCOPE),
        )
    }

    single<ConnectionStateMachineFacade> { get<ConnectionStateMachine>() }

    single<ConnectionStateMachineBootstrap> {
        ConnectionStateMachineBootstrap(
            machine = get(),
            networkMonitor = get(),
            lifecycleMonitor = get(),
            scope = get(qualifier = APP_SCOPE),
        )
    }

    // ----------------------------------------------------------------- coordinators / view models

    factory<ConnectViewModel> { (vmScope: CoroutineScope) ->
        ConnectViewModel(
            machine = get(),
            historyStore = get(),
            scope = vmScope,
            language = ConnectionLanguage.Zh,
        )
    }

    factory<ConnectAttemptCoordinator> { (vm: ConnectViewModel, vmScope: CoroutineScope) ->
        ConnectAttemptCoordinator(
            viewModel = vm,
            historyStore = get(),
            machine = get(),
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
internal val DEFAULT_HTTP_CLIENT = named("default-http-client")
internal val DEFAULT_WS = named("default-ws")
