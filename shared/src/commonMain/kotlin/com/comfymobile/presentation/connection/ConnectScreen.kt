package com.comfymobile.presentation.connection

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.comfymobile.data.network.ConnectError
import com.comfymobile.data.network.ConnectErrorContext
import com.comfymobile.data.network.ConnectionState
import com.comfymobile.data.network.ReconnectReason
import com.comfymobile.domain.server.ServerInfo
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ConnectRoute(
    viewModel: ConnectViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.screenState.collectAsState()
    ConnectScreen(
        state = state,
        actions = viewModel.actions(),
        modifier = modifier,
    )
}

@Composable
fun ConnectScreen(
    state: ConnectScreenState,
    actions: ConnectActions,
    modifier: Modifier = Modifier,
) {
    val language = state.language

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.shouldShowStatusIndicator) {
                ConnectionStatusIndicator(
                    ui = state.statusUi,
                    language = language,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.statusUi.banner?.let {
                ReconnectingBanner(
                    copy = it,
                    tone = state.statusUi.tone,
                    language = language,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.isFirstRun) {
                ConnectAssistant(
                    language = language,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ServerForm(
                formState = state.formState,
                validation = state.formValidation,
                language = language,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )

            ServerHistoryList(
                entries = state.history,
                language = language,
                onTap = actions.onServerSelected,
                onLongPress = actions.onServerLongPressed,
                onDelete = actions.onServerDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val lost = state.connectionState as? ConnectionState.Lost
        if (lost != null && state.showErrorDetails) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                ConnectErrorView(
                    error = lost.error,
                    context = state.errorContext,
                    language = language,
                    onRetry = actions.onRetry,
                    onDismiss = actions.onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    FriendlyNameModal(
        state = state.friendlyNameModal,
        language = language,
        actions = actions,
    )
}

@Composable
fun ConnectAssistant(
    language: ConnectionLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = ConnectionCopy.firstRunHeader.resolve(language),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = ConnectionCopy.ipHelperAlt.resolve(language) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = ConnectionCopy.ipHelperBody.resolve(language),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Listening at http://192.168.1.5:8188",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun ServerForm(
    formState: ServerFormState,
    validation: ServerFormValidation,
    language: ConnectionLanguage,
    actions: ConnectActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = formState.host,
            onValueChange = actions.onHostChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(ConnectionCopy.hostLabel.resolve(language)) },
            singleLine = true,
            isError = formState.showValidationErrors && validation.hostError != null,
            supportingText = {
                if (formState.showValidationErrors) {
                    validation.hostError?.let { Text(it.resolve(language)) }
                }
            },
        )

        OutlinedTextField(
            value = formState.port,
            onValueChange = actions.onPortChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(ConnectionCopy.portLabel.resolve(language)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = formState.showValidationErrors && validation.portError != null,
            supportingText = {
                if (formState.showValidationErrors) {
                    validation.portError?.let { Text(it.resolve(language)) }
                }
            },
        )

        OutlinedTextField(
            value = formState.friendlyName,
            onValueChange = actions.onFriendlyNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(ConnectionCopy.friendlyNameLabel.resolve(language)) },
            singleLine = true,
            isError = formState.showValidationErrors && validation.friendlyNameError != null,
            supportingText = {
                if (formState.showValidationErrors) {
                    validation.friendlyNameError?.let { Text(it.resolve(language)) }
                        ?: validation.duplicateNameWarning?.let { Text(it.resolve(language)) }
                }
            },
        )

        Button(
            onClick = actions.onSubmit,
            enabled = validation.canSubmit && !formState.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp),
        ) {
            if (formState.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(ConnectionCopy.connect.resolve(language))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerHistoryList(
    entries: List<ServerInfo>,
    language: ConnectionLanguage,
    onTap: (ServerInfo) -> Unit,
    onLongPress: (ServerInfo) -> Unit,
    onDelete: (ServerInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var actionTarget by remember { mutableStateOf<ServerInfo?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = ConnectionCopy.historyTitle.resolve(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (entries.isEmpty()) {
            Text(
                text = ConnectionCopy.noSavedServers.resolve(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { server ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onTap(server) },
                            onLongClick = { actionTarget = server },
                        ),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = server.label.ifBlank { server.serverId },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = server.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MdnsPlaceholderRow(
            language = language,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    actionTarget?.let { server ->
        ServerHistoryActionMenu(
            server = server,
            language = language,
            onRename = {
                actionTarget = null
                onLongPress(server)
            },
            onDelete = {
                actionTarget = null
                onDelete(server)
            },
            onDismiss = { actionTarget = null },
        )
    }
}

@Composable
private fun ServerHistoryActionMenu(
    server: ServerInfo,
    language: ConnectionLanguage,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.label.ifBlank { server.serverId }) },
        text = { Text(server.baseUrl) },
        confirmButton = {
            TextButton(onClick = onRename) {
                Text(ConnectionCopy.rename.resolve(language))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(ConnectionCopy.delete.resolve(language))
                }
                TextButton(onClick = onDismiss) {
                    Text(ConnectionCopy.cancel.resolve(language))
                }
            }
        },
    )
}

@Composable
fun MdnsPlaceholderRow(
    language: ConnectionLanguage,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = ConnectionCopy.mdnsPlaceholder.resolve(language),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ConnectionStatusIndicator(
    ui: ConnectionStatusUi,
    language: ConnectionLanguage,
    modifier: Modifier = Modifier,
) {
    val dotColor = statusColor(ui.tone)
    val dotAlpha = if (ui.pulsing) {
        val transition = rememberInfiniteTransition(label = "connection-pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "connection-pulse-alpha",
        )
        alpha
    } else {
        1f
    }

    Row(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .semantics { contentDescription = ui.label.resolve(language) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(dotAlpha)
                .background(dotColor, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = ui.label.resolve(language),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ReconnectingBanner(
    copy: LocalizedText,
    tone: ConnectionStatusTone,
    language: ConnectionLanguage,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = when (tone) {
            ConnectionStatusTone.Info -> MaterialTheme.colorScheme.secondaryContainer
            ConnectionStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
            ConnectionStatusTone.Success -> MaterialTheme.colorScheme.tertiaryContainer
            ConnectionStatusTone.Subtle -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when (tone) {
            ConnectionStatusTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
            ConnectionStatusTone.Error -> MaterialTheme.colorScheme.onErrorContainer
            ConnectionStatusTone.Success -> MaterialTheme.colorScheme.onTertiaryContainer
            ConnectionStatusTone.Subtle -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = copy.resolve(language),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ConnectErrorView(
    error: ConnectError,
    context: ConnectErrorContext?,
    language: ConnectionLanguage,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = ConnectErrorCopy.lookup(error)
    Column(
        modifier = modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = LocalizedText(copy.titleZh, copy.titleEn).resolve(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = LocalizedText(copy.bodyZh, copy.bodyEn).resolve(language),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (copy.suggestionZh != null && copy.suggestionEn != null) {
            Text(
                text = LocalizedText(copy.suggestionZh, copy.suggestionEn).resolve(language),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (context?.description != null || context?.statusCode != null) {
            Text(
                text = buildString {
                    append(ConnectionCopy.technicalDetails.resolve(language))
                    append(": ")
                    context.statusCode?.let { append("HTTP ").append(it).append(" ") }
                    context.description?.let { append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Per @Lily PR #19 review `4413981846` blocker 2: route
            // the primary button by `Lookup.primaryAction` so a
            // "Choose / 去选择" CTA does NOT dispatch Retry (which
            // would loop back to Lost(NO_ACTIVE_SERVER)). The
            // routing lives in `ConnectErrorCopy.primaryClickHandler`
            // so a unit test can lock the contract without a Compose
            // UI harness.
            val primaryClick = ConnectErrorCopy.primaryClickHandler(
                primaryAction = copy.primaryAction,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )
            Button(onClick = primaryClick) {
                Text(LocalizedText(copy.primaryCtaZh, copy.primaryCtaEn).resolve(language))
            }
            OutlinedButton(onClick = onDismiss) {
                Text(ConnectionCopy.cancel.resolve(language))
            }
        }
    }
}

@Composable
fun FriendlyNameModal(
    state: FriendlyNameModalState,
    language: ConnectionLanguage,
    actions: ConnectActions,
) {
    val visible = state as? FriendlyNameModalState.Visible ?: return
    AlertDialog(
        onDismissRequest = actions.onFriendlyModalDismiss,
        title = { Text(ConnectionCopy.friendlyNameTitle.resolve(language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ConnectionCopy.friendlyNameBody.resolve(language))
                OutlinedTextField(
                    value = visible.value,
                    onValueChange = actions.onFriendlyModalChanged,
                    singleLine = true,
                    label = { Text(ConnectionCopy.friendlyNameLabel.resolve(language)) },
                    placeholder = { Text(visible.placeholder) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onFriendlyModalSave) {
                Text(ConnectionCopy.save.resolve(language))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onFriendlyModalDismiss) {
                Text(ConnectionCopy.cancel.resolve(language))
            }
        },
    )
}

@Composable
private fun statusColor(tone: ConnectionStatusTone): Color = when (tone) {
    ConnectionStatusTone.Success -> MaterialTheme.colorScheme.tertiary
    ConnectionStatusTone.Subtle -> MaterialTheme.colorScheme.outline
    ConnectionStatusTone.Info -> MaterialTheme.colorScheme.secondary
    ConnectionStatusTone.Error -> MaterialTheme.colorScheme.error
}

@Preview
@Composable
private fun ConnectScreenFirstRunPreview() {
    MaterialTheme {
        ConnectScreen(
            state = ConnectScreenState(
                connectionState = ConnectionState.Lost(ConnectError.TIMEOUT),
                formState = ServerFormState(showValidationErrors = true),
                formValidation = ServerFormValidator.validate(ServerFormState(showValidationErrors = true)),
            ),
            actions = ConnectActions(),
        )
    }
}

@Preview
@Composable
private fun ConnectScreenConnectedPreview() {
    MaterialTheme {
        ConnectScreen(
            state = ConnectScreenState(
                connectionState = ConnectionState.Connected,
                history = listOf(previewServer()),
                activeServer = previewServer(),
            ),
            actions = ConnectActions(),
        )
    }
}

@Preview
@Composable
private fun ConnectScreenReconnectingPreview() {
    MaterialTheme {
        ConnectScreen(
            state = ConnectScreenState(
                connectionState = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED),
                history = listOf(previewServer()),
                activeServer = previewServer(),
            ),
            actions = ConnectActions(),
        )
    }
}

@Preview
@Composable
private fun ConnectScreenErrorPreview() {
    MaterialTheme {
        ConnectScreen(
            state = ConnectScreenState(
                connectionState = ConnectionState.Lost(ConnectError.NOT_COMFYUI),
                history = listOf(previewServer()),
                activeServer = previewServer(),
                errorContext = ConnectErrorContext(statusCode = 200, description = "Unexpected response body"),
            ),
            actions = ConnectActions(),
        )
    }
}

private fun previewServer() = ServerInfo(
    serverId = "192.168.1.5:8188",
    host = "192.168.1.5",
    port = 8188,
    label = "Studio Mac",
    lastConnectedAtEpochMs = 1_000L,
)
