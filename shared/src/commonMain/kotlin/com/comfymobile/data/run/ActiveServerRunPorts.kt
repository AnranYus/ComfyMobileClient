package com.comfymobile.data.run

import com.comfymobile.data.network.ComfyHttpClient
import com.comfymobile.data.network.WebSocketSource
import com.comfymobile.data.network.WsEvent
import com.comfymobile.data.network.dto.PromptRequestDto
import com.comfymobile.data.network.dto.PromptResponseDto
import com.comfymobile.domain.run.CancelPort
import com.comfymobile.domain.run.PromptSubmissionPort
import com.comfymobile.domain.run.WsEventPort
import kotlinx.coroutines.flow.Flow

/**
 * Production adapters that wire the [com.comfymobile.domain.run.RunCoordinator]
 * port surface to the existing [ComfyHttpClient] / [WebSocketSource]
 * data layer.
 *
 * **Server context is now passed PER CALL** (per @Lily PR #31 review
 * msg `8bbd4fa1` blocker 1): the run pins to a single `baseUrl` at
 * submission time and threads it through every port call. The adapters
 * no longer read [com.comfymobile.data.connect.ActiveServerHolder] —
 * doing so would let a mid-run active-server switch redirect cancel /
 * WS traffic to a different server.
 *
 * Concurrency: each call to a port method runs in the caller's coroutine
 * with the baseUrl it was passed. No shared mutable state. Factories
 * are pure: the same `baseUrl` always resolves to the same kind of
 * client.
 */
class HttpClientPromptPort(
    private val httpClientFactory: (baseUrl: String) -> ComfyHttpClient,
) : PromptSubmissionPort {

    override suspend fun submit(baseUrl: String, request: PromptRequestDto): PromptResponseDto =
        httpClientFactory(baseUrl).submitPrompt(request)
}

class HttpClientCancelPort(
    private val httpClientFactory: (baseUrl: String) -> ComfyHttpClient,
) : CancelPort {

    override suspend fun interruptRunning(baseUrl: String, promptId: String) {
        httpClientFactory(baseUrl).interruptRunning(promptId)
    }

    override suspend fun deleteQueued(baseUrl: String, promptId: String) {
        httpClientFactory(baseUrl).deleteQueued(promptId)
    }
}

class WebSocketSourceWsEventPort(
    private val webSocketSourceFactory: (baseUrl: String) -> WebSocketSource,
) : WsEventPort {

    override fun events(baseUrl: String, clientId: String): Flow<WsEvent> =
        webSocketSourceFactory(baseUrl).connect(clientId)
}
