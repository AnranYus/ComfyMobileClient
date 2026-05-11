package com.comfymobile.presentation.history

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfymobile.presentation.connection.ConnectionLanguage
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onOpenOutputs: (String) -> Unit,
    onOpenWorkflow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val viewModelActions = viewModel.actions()
    HistoryScreen(
        state = state,
        actions = viewModelActions.copy(
            onOpenOutputs = onOpenOutputs,
            onOpenWorkflow = onOpenWorkflow,
        ),
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryScreenState,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HistoryHeader(state)
        HistoryFilterRow(state, actions)

        when {
            !state.hasActiveServer -> HistoryEmpty(
                text = HistoryCopy.noActiveServer.resolve(state.language),
                modifier = Modifier.weight(1f),
            )
            state.isEmpty -> HistoryEmpty(
                text = HistoryCopy.empty.resolve(state.language),
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.promptId }) { row ->
                    HistoryRow(
                        row = row,
                        language = state.language,
                        actions = actions,
                    )
                }
            }
        }
    }

    RenameDialog(state, actions)
    DeleteDialog(state, actions)
}

@Composable
private fun HistoryHeader(state: HistoryScreenState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = HistoryCopy.title.resolve(state.language),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            state.activeServerLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.isReconciling) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun HistoryFilterRow(
    state: HistoryScreenState,
    actions: HistoryActions,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.selectedFilter == filter,
                onClick = { actions.onFilterSelected(filter) },
                label = { Text(HistoryCopy.filterLabel(filter).resolve(state.language)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    row: HistoryRowState,
    language: ConnectionLanguage,
    actions: HistoryActions,
) {
    var menuExpanded by remember(row.promptId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.onOpenOutputs(row.promptId) },
                onLongClick = { menuExpanded = true },
            ),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryThumbnail(row, language)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.promptSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.relativeTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            text = row.status.symbol,
                            color = statusColor(row.status.tone),
                        )
                    },
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                DropdownMenuItem(
                    text = { Text(HistoryCopy.rename.resolve(language)) },
                    onClick = {
                        menuExpanded = false
                        actions.onRenameRequested(row.promptId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(HistoryCopy.openWorkflow.resolve(language)) },
                    enabled = row.canOpenWorkflow,
                    onClick = {
                        menuExpanded = false
                        actions.onOpenWorkflow(row.promptId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(HistoryCopy.delete.resolve(language)) },
                    onClick = {
                        menuExpanded = false
                        actions.onDeleteRequested(row.promptId)
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryThumbnail(row: HistoryRowState, language: ConnectionLanguage) {
    Surface(
        modifier = Modifier.size(width = 64.dp, height = 64.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (row.thumbnailUrl == null) {
                    HistoryCopy.placeholder.resolve(language)
                } else {
                    HistoryCopy.thumbnail.resolve(language)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryEmpty(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialog(
    state: HistoryScreenState,
    actions: HistoryActions,
) {
    if (state.renamePromptId == null) return
    AlertDialog(
        onDismissRequest = actions.onDismissRename,
        title = { Text(HistoryCopy.rename.resolve(state.language)) },
        text = {
            OutlinedTextField(
                value = state.renameDraft,
                onValueChange = actions.onRenameValueChanged,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmRename) {
                Text(HistoryCopy.save.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRename) {
                Text(HistoryCopy.cancel.resolve(state.language))
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    state: HistoryScreenState,
    actions: HistoryActions,
) {
    if (state.deletePromptId == null) return
    AlertDialog(
        onDismissRequest = actions.onDismissDelete,
        title = { Text(HistoryCopy.deleteTitle.resolve(state.language)) },
        text = { Text(HistoryCopy.deleteBody.resolve(state.language)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmDelete) {
                Text(HistoryCopy.delete.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDelete) {
                Text(HistoryCopy.cancel.resolve(state.language))
            }
        },
    )
}

@Composable
private fun statusColor(tone: HistoryStatusTone): Color = when (tone) {
    HistoryStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    HistoryStatusTone.Success -> MaterialTheme.colorScheme.primary
    HistoryStatusTone.Running -> MaterialTheme.colorScheme.tertiary
    HistoryStatusTone.Error -> MaterialTheme.colorScheme.error
}

@Preview
@Composable
private fun HistoryScreenPreview() {
    MaterialTheme {
        HistoryScreen(
            state = HistoryScreenState(
                activeServerLabel = "Studio LAN",
                rows = listOf(
                    HistoryRowState(
                        promptId = "p-1",
                        title = "Portrait workflow",
                        promptSnippet = "a cinematic portrait with soft studio lighting",
                        relativeTime = "2h ago",
                        status = HistoryMapper.status(com.comfymobile.domain.job.JobStatus.SUCCEEDED),
                        thumbnailUrl = "http://example/thumb.png",
                        canOpenWorkflow = true,
                    ),
                    HistoryRowState(
                        promptId = "p-2",
                        title = "Queue test",
                        promptSnippet = "No prompt recorded",
                        relativeTime = "just now",
                        status = HistoryMapper.status(com.comfymobile.domain.job.JobStatus.RUNNING),
                    ),
                ),
            ),
            actions = HistoryActions(
                onFilterSelected = {},
                onOpenOutputs = {},
                onOpenWorkflow = {},
                onRenameRequested = {},
                onRenameValueChanged = {},
                onConfirmRename = {},
                onDismissRename = {},
                onDeleteRequested = {},
                onConfirmDelete = {},
                onDismissDelete = {},
            ),
        )
    }
}
