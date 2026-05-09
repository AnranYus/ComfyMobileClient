package com.comfymobile.data.connect

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.ComfyHttpException
import com.comfymobile.data.network.ConnectAttemptOutcome
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectErrorClassifier
import com.comfymobile.data.network.ConnectionInput
import com.comfymobile.data.connection.ConnectionStateMachineFacade
import com.comfymobile.domain.server.ServerHistoryStore
import com.comfymobile.domain.server.ServerInfo
import com.comfymobile.presentation.connection.ConnectUiEvent
import com.comfymobile.presentation.connection.ConnectViewModel
import com.comfymobile.presentation.connection.ServerFormSubmit
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Drives the "user submitted a host:port" → "machine sees the right
 * outcome" loop.
 *
 * Subscribes to [ConnectViewModel.events] and, for each
 * [ConnectUiEvent.ConnectRequested], runs a `/system_stats` probe
 * against the requested server. The probe outcome is translated into
 * a [ConnectionInput] (success path: Retry to keep the machine in
 * Connected state; failure path: ConnectAttempt(classifiedError) so
 * the state machine surfaces the error to the UI). Successful probes
 * upsert the server entry into [ServerHistoryStore] so the friendly
 * label and `lastConnectedAtEpochMs` reflect the latest attempt.
 *
 * Per @Lily PR #16 + #17 reviews: server history is **only** updated
 * on probe success — we never write a row before we've actually
 * verified the LAN endpoint speaks ComfyUI.
 */
class ConnectAttemptCoordinator(
    private val viewModel: ConnectViewModel,
    private val historyStore: ServerHistoryStore,
    private val machine: ConnectionStateMachineFacade,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    /**
     * Factory hook for the per-server [ComfyHttpClient]. The DI
     * module supplies `{ baseUrl -> ComfyHttpClient(baseUrl, …) }`;
     * tests substitute a fake.
     */
    private val httpClientFor: (baseUrl: String) -> ComfyHttpClient,
) {

    private var job: Job? = null

    /** Begin observing [ConnectViewModel.events]. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = viewModel.events
            .onEach { event ->
                when (event) {
                    is ConnectUiEvent.ConnectRequested -> handleConnectRequested(event.submit)
                    is ConnectUiEvent.RenameRequested -> historyStore.upsert(event.server)
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handleConnectRequested(submit: ServerFormSubmit) {
        val baseUrl = "http://${submit.host}:${submit.port}"
        val client = httpClientFor(baseUrl)
        try {
            val stats = client.getSystemStats()
            // Probe succeeded → record / refresh server entry.
            val server = ServerInfo(
                serverId = ServerInfo.idFor(submit.host, submit.port),
                host = submit.host,
                port = submit.port,
                label = submit.friendlyName?.takeIf { it.isNotBlank() } ?: submit.host,
                lastConnectedAtEpochMs = nowEpochMs(),
            )
            historyStore.upsert(server)
            // Stay in Connected (no input needed; reducer keeps state).
        } catch (httpEx: ComfyHttpException) {
            val outcome = httpEx.toAttemptOutcome()
            val (error, _) = ConnectErrorClassifier.classify(outcome)
            machine.dispatch(ConnectionInput.ConnectAttempt(classified = error))
        } catch (t: Throwable) {
            val (error, _) = ConnectErrorClassifier.classify(
                ConnectAttemptOutcome.NetworkFailure(message = t.message),
            )
            machine.dispatch(ConnectionInput.ConnectAttempt(classified = error))
        }
    }

    private fun ComfyHttpException.toAttemptOutcome(): ConnectAttemptOutcome = when (this) {
        is ComfyHttpException.HttpStatus -> ConnectAttemptOutcome.HttpResponse(
            statusCode = statusCode,
            bodyLooksLikeComfyUi = false,
        )
        is ComfyHttpException.MalformedResponse -> ConnectAttemptOutcome.HttpResponse(
            statusCode = 200,
            bodyLooksLikeComfyUi = false,
        )
        is ComfyHttpException.MissingField -> ConnectAttemptOutcome.HttpResponse(
            statusCode = 200,
            bodyLooksLikeComfyUi = false,
        )
    }
}
