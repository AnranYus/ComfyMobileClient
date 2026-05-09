package com.comfymobile.presentation.connection

import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectErrorContext
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.server.ServerInfo

data class ConnectScreenState(
    val connectionState: ConnectionState,
    val formState: ServerFormState = ServerFormState(),
    val formValidation: ServerFormValidation = ServerFormValidation(),
    val history: List<ServerInfo> = emptyList(),
    val activeServer: ServerInfo? = null,
    val errorContext: ConnectErrorContext? = null,
    val showErrorDetails: Boolean = true,
    val friendlyNameModal: FriendlyNameModalState = FriendlyNameModalState.Hidden,
    val language: ConnectionLanguage = ConnectionLanguage.En,
) {
    val isFirstRun: Boolean
        get() = history.isEmpty()

    val shouldShowStatusIndicator: Boolean
        get() = connectionState != ConnectionState.Connected || activeServer != null

    val statusUi: ConnectionStatusUi
        get() = ConnectionStatusUi.from(connectionState, activeServer)
}

sealed interface FriendlyNameModalState {
    data object Hidden : FriendlyNameModalState
    data class Visible(
        val host: String,
        val port: Int,
        val value: String = "",
    ) : FriendlyNameModalState {
        val placeholder: String
            get() = "$host at $port"
    }
}

enum class ConnectionStatusTone {
    Success,
    Subtle,
    Info,
    Error,
}

data class ConnectionStatusUi(
    val label: LocalizedText,
    val banner: LocalizedText?,
    val tone: ConnectionStatusTone,
    val pulsing: Boolean,
) {
    companion object {
        fun from(state: ConnectionState, activeServer: ServerInfo?): ConnectionStatusUi = when (state) {
            ConnectionState.Connected -> ConnectionStatusUi(
                label = LocalizedText(
                    zh = "已连接${activeServer?.let { "到 ${it.label}" } ?: ""}",
                    en = "Connected${activeServer?.let { " to ${it.label}" } ?: ""}",
                ),
                banner = null,
                tone = ConnectionStatusTone.Success,
                pulsing = false,
            )
            is ConnectionState.Reconnecting -> when (state.reason) {
                ReconnectReason.LAN_FLAKE -> ConnectionStatusUi(
                    label = ConnectionCopy.lanFlake,
                    banner = null,
                    tone = ConnectionStatusTone.Subtle,
                    pulsing = true,
                )
                ReconnectReason.BACKGROUND_RESUMED -> ConnectionStatusUi(
                    label = ConnectionCopy.backgroundResumed,
                    banner = ConnectionCopy.backgroundResumed,
                    tone = ConnectionStatusTone.Info,
                    pulsing = true,
                )
            }
            is ConnectionState.Lost -> ConnectionStatusUi(
                label = ConnectionCopy.lost,
                banner = errorTitle(state.error),
                tone = ConnectionStatusTone.Error,
                pulsing = false,
            )
        }

        private fun errorTitle(error: ConnectError): LocalizedText {
            val copy = ConnectErrorCopy.lookup(error)
            return LocalizedText(copy.titleZh, copy.titleEn)
        }
    }
}

data class ConnectActions(
    val onHostChanged: (String) -> Unit = {},
    val onPortChanged: (String) -> Unit = {},
    val onFriendlyNameChanged: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onServerSelected: (ServerInfo) -> Unit = {},
    val onServerLongPressed: (ServerInfo) -> Unit = {},
    val onServerDelete: (ServerInfo) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onFriendlyModalChanged: (String) -> Unit = {},
    val onFriendlyModalSave: () -> Unit = {},
    val onFriendlyModalDismiss: () -> Unit = {},
)
