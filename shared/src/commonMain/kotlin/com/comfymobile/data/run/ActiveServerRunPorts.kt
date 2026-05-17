package com.comfymobile.data.run

import com.comfymobile.data.connect.ActiveServerHolder
import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import com.comfymobile.domain.run.CancelPort
import com.comfymobile.domain.run.PromptSubmissionPort
import com.comfymobile.domain.run.WsEventPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production adapters that wire the [com.comfymobile.domain.run.RunCoordinator]
 * port surface to the existing [ComfyHttpClient] / [WebSocketSource]
 * data layer.
 *
 * **Why "active-server-aware" adapters and not direct injection**:
 *  - The user can switch active servers between runs; the coordinator
 *    is a process-lifetime singleton.
 *  - Each `ComfyHttpClient` / `WebSocketSource` is bound to a single
 *    baseUrl (factory binding in `AppModule`).
 *  - At run time, the adapter snapshots the *current* `ActiveServerHolder`
 *    value and asks the factory for a client for that server. If no
 *    active server is set (UI gating failed), the adapter throws
 *    `IllegalStateException`; the coordinator surfaces this as
 *    `RunError.Network`.
 *
 * Concurrency: each call to a port method runs in the caller's
 * coroutine; the factories are pure and the active-server snapshot is
 * lock-free (StateFlow.value read). No shared mutable state inside
 * these adapters.
 *
 * No tests for the adapters themselves — they are thin pass-throughs.
 * `RunCoordinatorTest` exercises the port contracts behind fakes;
 * `ComfyHttpClientTest` exercises the wire behavior of the underlying
 * `submitPrompt` / `interruptRunning` / `deleteQueued` methods.
 */
class ActiveServerPromptPort(
    private val activeServer: ActiveServerHolder,
    private val httpClientFactory: (baseUrl: String) -> ComfyHttpClient,
) : PromptSubmissionPort {

    override suspend fun submit(request: PromptRequestDto): PromptResponseDto {
        val baseUrl = currentBaseUrl()
        return httpClientFactory(baseUrl).submitPrompt(request)
    }

    private fun currentBaseUrl(): String =
        activeServer.current.value?.baseUrl
            ?: throw IllegalStateException("RunPort: no active server")
}

class ActiveServerCancelPort(
    private val activeServer: ActiveServerHolder,
    private val httpClientFactory: (baseUrl: String) -> ComfyHttpClient,
) : CancelPort {

    override suspend fun interruptRunning(promptId: String) {
        httpClientFactory(currentBaseUrl()).interruptRunning(promptId)
    }

    override suspend fun deleteQueued(promptId: String) {
        httpClientFactory(currentBaseUrl()).deleteQueued(promptId)
    }

    private fun currentBaseUrl(): String =
        activeServer.current.value?.baseUrl
            ?: throw IllegalStateException("RunPort: no active server")
}

class ActiveServerWsEventPort(
    private val activeServer: ActiveServerHolder,
    private val webSocketSourceFactory: (baseUrl: String) -> WebSocketSource,
) : WsEventPort {

    override fun events(clientId: String): Flow<WsEvent> = flow {
        val baseUrl = activeServer.current.value?.baseUrl
            ?: throw IllegalStateException("RunPort: no active server")
        webSocketSourceFactory(baseUrl)
            .connect(clientId)
            .collect { emit(it) }
    }
}
