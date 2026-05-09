package com.comfymobile.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Abstraction for a stream of [WsEvent]s coming off a single
 * ComfyUI WebSocket session.
 *
 * Lives behind an interface so tests can substitute a Flow-returning
 * stub without spinning up a real Ktor WebSocket session. Production
 * code uses [ComfyWebSocketClient].
 */
interface WebSocketSource {
    /** Open a session and emit one [WsEvent] per inbound text frame. */
    fun connect(clientId: String): Flow<WsEvent>
}

/**
 * Ktor-WebSocket driver for ComfyUI's `/ws` endpoint.
 *
 * Construction takes a pre-built [HttpClient] (so MockEngine can
 * drive tests) and a base URL like `http://192.168.1.10:8188`. The
 * driver internally rewrites that to the matching `ws://` /
 * `wss://` URL and opens a single session.
 *
 * The returned [Flow] emits one [WsEvent] per text frame, terminating
 * (with the underlying coroutine cancellation or IO exception
 * propagating) when the session closes. Higher-level code wraps the
 * Flow lifecycle in [ConnectionEffectRunner] and translates
 * exceptions into `ConnectionInput.Ws(droppedReason = …)`.
 *
 * Binary frames (ComfyUI sends preview JPEG/PNG previews on a binary
 * channel) are ignored in T1.1 — preview rendering lands as part of
 * T1.3 product work.
 */
class ComfyWebSocketClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) : WebSocketSource {

    /**
     * Connect to `/ws?clientId={clientId}` and emit one [WsEvent] per
     * text frame received. The returned Flow is cold; collecting it
     * opens a fresh session, so callers wanting reconnect behaviour
     * should re-collect.
     */
    override fun connect(clientId: String): Flow<WsEvent> = flow {
        httpClient.webSocket(buildWsUrl(clientId)) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> emit(WsEventParser.parse(frame.readText()))
                    else -> Unit
                }
            }
        }
    }

    private fun buildWsUrl(clientId: String): String {
        val rewritten = when {
            baseUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + baseUrl.removePrefix("http://").removePrefix("HTTP://")
            baseUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + baseUrl.removePrefix("https://").removePrefix("HTTPS://")
            else -> baseUrl
        }.trimEnd('/')
        val encodedClientId = encodeQueryComponent(clientId)
        return "$rewritten/ws?clientId=$encodedClientId"
    }

    /**
     * Conservative percent-encoding for query string values — covers
     * the ASCII letters/digits/`-_.~` unreserved set. Sufficient for
     * UUID-shaped client ids; richer values would deserve a more
     * complete encoder.
     */
    private fun encodeQueryComponent(value: String): String =
        buildString {
            for (c in value) {
                when {
                    c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                    else -> append("%${c.code.toString(16).uppercase().padStart(2, '0')}")
                }
            }
        }
}
