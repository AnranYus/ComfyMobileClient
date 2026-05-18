package com.comfymobile.presentation.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.comfymobile.domain.workflow.WorkflowRow
import com.comfymobile.presentation.connection.ConnectionLanguage
import com.comfymobile.presentation.connection.ConnectionStatusTone
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun WorkflowLibraryRoute(
    viewModel: WorkflowLibraryViewModel,
    onImport: () -> Unit,
    onWorkflowOpened: (WorkflowRow) -> Unit,
    onWorkflowDeleted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.openEvents.collect { onWorkflowOpened(it) }
    }
    WorkflowLibraryScreen(
        state = state,
        actions = viewModel.actions(onImport = onImport).withDeleteCallback(onWorkflowDeleted),
        modifier = modifier,
    )
}

@Composable
fun WorkflowLibraryScreen(
    state: WorkflowLibraryScreenState,
    actions: WorkflowLibraryActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WorkflowLibraryHeader(state)
            if (state.isEmpty) {
                WorkflowLibraryEmpty(
                    language = state.language,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.workflowId }) { row ->
                        WorkflowLibraryRow(
                            row = row,
                            language = state.language,
                            actions = actions,
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = actions.onImport,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Text(WorkflowLibraryCopy.importWorkflow.resolve(state.language))
        }
    }

    RenameDialog(state, actions)
    DeleteDialog(state, actions)
}

@Composable
private fun WorkflowLibraryHeader(state: WorkflowLibraryScreenState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = WorkflowLibraryCopy.title.resolve(state.language),
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
        WorkflowConnectionChip(state)
    }
}

@Composable
private fun WorkflowConnectionChip(state: WorkflowLibraryScreenState) {
    val label = state.connectionLabel ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(statusColor(state.connectionTone), CircleShape),
        )
        Text(
            text = label.resolve(state.language),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkflowLibraryRow(
    row: WorkflowLibraryRowState,
    language: ConnectionLanguage,
    actions: WorkflowLibraryActions,
) {
    var menuExpanded by remember(row.workflowId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.onOpenWorkflow(row.workflowId) },
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
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = WorkflowLibraryCopy.format(row.format, language)
                                .split(" ")
                                .firstOrNull()
                                .orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
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
                        text = buildString {
                            append(WorkflowLibraryCopy.nodeCount(row.nodeCount, language))
                            append(" · ")
                            append(WorkflowLibraryCopy.format(row.format, language))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (row.lastOpenedAtEpochMs == null) {
                            WorkflowLibraryCopy.imported.resolve(language)
                        } else {
                            WorkflowLibraryCopy.lastOpened.resolve(language)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                DropdownMenuItem(
                    text = { Text(WorkflowLibraryCopy.rename.resolve(language)) },
                    onClick = {
                        menuExpanded = false
                        actions.onRenameRequested(row.workflowId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(WorkflowLibraryCopy.delete.resolve(language)) },
                    onClick = {
                        menuExpanded = false
                        actions.onDeleteRequested(row.workflowId)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkflowLibraryEmpty(
    language: ConnectionLanguage,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = WorkflowLibraryCopy.emptyTitle.resolve(language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = WorkflowLibraryCopy.emptyBody.resolve(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(
    state: WorkflowLibraryScreenState,
    actions: WorkflowLibraryActions,
) {
    state.pendingRename ?: return
    AlertDialog(
        onDismissRequest = actions.onDismissRename,
        title = { Text(WorkflowLibraryCopy.renameTitle.resolve(state.language)) },
        text = {
            OutlinedTextField(
                value = state.renameDraft,
                onValueChange = actions.onRenameValueChanged,
                label = { Text(WorkflowLibraryCopy.workflowName.resolve(state.language)) },
                singleLine = true,
                maxLines = 1,
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmRename,
                enabled = state.canConfirmRename,
            ) {
                Text(WorkflowLibraryCopy.save.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRename) {
                Text(WorkflowLibraryCopy.cancel.resolve(state.language))
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    state: WorkflowLibraryScreenState,
    actions: WorkflowLibraryActions,
) {
    val row = state.pendingDelete ?: return
    AlertDialog(
        onDismissRequest = actions.onDismissDelete,
        title = { Text(WorkflowLibraryCopy.deleteTitle.resolve(state.language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title)
                Text(WorkflowLibraryCopy.deleteBody.resolve(state.language))
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onConfirmDelete(row.workflowId) }) {
                Text(WorkflowLibraryCopy.delete.resolve(state.language))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDelete) {
                Text(WorkflowLibraryCopy.cancel.resolve(state.language))
            }
        },
    )
}

private fun WorkflowLibraryActions.withDeleteCallback(
    onWorkflowDeleted: (String) -> Unit,
): WorkflowLibraryActions = copy(
    onConfirmDelete = { workflowId ->
        onConfirmDelete(workflowId)
        onWorkflowDeleted(workflowId)
    },
)

@Composable
private fun statusColor(tone: ConnectionStatusTone): Color = when (tone) {
    ConnectionStatusTone.Success -> MaterialTheme.colorScheme.primary
    ConnectionStatusTone.Info -> MaterialTheme.colorScheme.tertiary
    ConnectionStatusTone.Subtle -> MaterialTheme.colorScheme.outline
    ConnectionStatusTone.Error -> MaterialTheme.colorScheme.error
}

@Preview
@Composable
private fun WorkflowLibraryScreenPreview() {
    MaterialTheme {
        WorkflowLibraryScreen(
            state = WorkflowLibraryScreenState(
                rows = listOf(
                    WorkflowLibraryRowState(
                        workflowId = "wf_1",
                        title = "Portrait retouch",
                        nodeCount = 18,
                        format = WorkflowLibraryFormat.Ui,
                        importedAtEpochMs = 100L,
                        lastOpenedAtEpochMs = 200L,
                    ),
                    WorkflowLibraryRowState(
                        workflowId = "wf_2",
                        title = "API upscaler",
                        nodeCount = 5,
                        format = WorkflowLibraryFormat.Api,
                        importedAtEpochMs = 80L,
                        lastOpenedAtEpochMs = null,
                    ),
                ),
            ),
            actions = WorkflowLibraryActions(),
        )
    }
}
