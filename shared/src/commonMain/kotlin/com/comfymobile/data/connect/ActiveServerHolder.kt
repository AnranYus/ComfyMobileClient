package com.comfymobile.data.connect

import com.comfymobile.domain.server.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-source-of-truth for which server the user is currently
 * connected to.
 *
 * Per @Lily PR #16 / #18 reviews: an entry in [ServerHistoryStore]
 * is *not* the same thing as "the user is connected to this server"
 * — history is candidate list; active server is a different concept.
 *
 * The connection coordinator [ConnectAttemptCoordinator] sets this
 * after a successful probe; the UI observes [current] to decide
 * whether to render the connected indicator with a label, and the
 * (future) DI / runner layer reads [current] to know which baseUrl
 * to OpenWs against.
 *
 * Setting the active server does NOT push state into the
 * [com.comfymobile.data.connection.ConnectionStateMachine]; the two
 * are orthogonal concepts and the UI layer combines them when
 * rendering.
 */
class ActiveServerHolder {

    private val _current = MutableStateFlow<ServerInfo?>(null)

    /**
     * Currently connected server (null when no successful probe has
     * happened in this session).
     */
    val current: StateFlow<ServerInfo?> = _current.asStateFlow()

    fun setActive(server: ServerInfo) {
        _current.value = server
    }

    /** Drop the active-server pointer (e.g. user disconnected). */
    fun clear() {
        _current.value = null
    }
}
