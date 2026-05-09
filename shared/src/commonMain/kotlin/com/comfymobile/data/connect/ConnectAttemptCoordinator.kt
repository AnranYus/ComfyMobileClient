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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Drives the "user submitted a host:port" → "machine sees the right
 * outcome" loop.
 *
 * Subscribes to [ConnectViewModel.events]:
 *  - [ConnectUiEvent.ConnectRequested]: run a `/system_stats` probe;
 *    on success record the server in [ServerHistoryStore], promote
 *    it in [ActiveServerHolder], and dispatch
 *    [ConnectionInput.Retry] to nudge the state machine out of any
 *    prior `Lost` state. On failure, classify via
 *    [ConnectErrorClassifier] and dispatch
 *    [ConnectionInput.ConnectAttempt] so the state machine surfaces
 *    the error to the UI.
 *  - [ConnectUiEvent.RenameRequested]: upsert the renamed server.
 *
 * Per @Lily reviews: server history is **only** updated on probe
 * success; the active server pointer is **only** set on probe
 * success; CancellationException always propagates so structured
 * concurrency works (the coordinator is launched from the VM scope
 * and that scope's cancellation must reach the probe coroutines).
 */
class ConnectAttemptCoordinator(
    private val viewModel: ConnectViewModel,
    private val historyStore: ServerHistoryStore,
    private val machine: ConnectionStateMachineFacade,
    private val activeServer: ActiveServerHolder,
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
            client.getSystemStats()
            // Probe succeeded → record / refresh server entry, mark
            // it active, nudge the state machine out of Lost.
            val server = ServerInfo(
                serverId = ServerInfo.idFor(submit.host, submit.port),
                host = submit.host,
                port = submit.port,
                label = submit.friendlyName?.takeIf { it.isNotBlank() } ?: submit.host,
                lastConnectedAtEpochMs = nowEpochMs(),
            )
            historyStore.upsert(server)
            activeServer.setActive(server)
            // Retry is a no-op while Connected; transitions Lost
            // back to Reconnecting (the runner / bootstrap then
            // takes over).
            machine.dispatch(ConnectionInput.Retry)
        } catch (ce: CancellationException) {
            // Never swallow cancellation — the VM scope cancelling
            // the coordinator must propagate. Per the same pattern
            // as JobReconciler.probeOne (T1.3 part 1).
            throw ce
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
