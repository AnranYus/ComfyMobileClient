package com.comfymobile.presentation.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.comfymobile.data.image.ComfyOutputRef
import com.comfymobile.presentation.connection.ConnectionLanguage
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun OutputGalleryRoute(
    viewModel: OutputGalleryViewModel,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRunAgain: () -> Unit = {},
    onTweakAndRun: () -> Unit = {},
    onViewWorkflow: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val viewModelActions = viewModel.actions()
    OutputGalleryScreen(
        state = state,
        actions = viewModelActions.copy(
            onBack = onBack,
            onRunAgain = onRunAgain,
            onTweakAndRun = onTweakAndRun,
            onViewWorkflow = onViewWorkflow,
        ),
        imageLoader = imageLoader,
        modifier = modifier,
    )
}

@Composable
fun OutputGalleryScreen(
    state: OutputGalleryState,
    actions: OutputGalleryActions,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutputGalleryTopBar(state, actions)
            if (state.isEmpty) {
                OutputGalleryEmpty(
                    text = OutputGalleryCopy.empty.resolve(state.language),
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutputGrid(
                    state = state,
                    actions = actions,
                    imageLoader = imageLoader,
                    modifier = Modifier.weight(1f),
                )
            }
            MetadataFooter(state, actions)
        }

        val selected = state.selectedItem
        if (selected != null) {
            OutputViewer(
                state = state,
                item = selected,
                imageLoader = imageLoader,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OutputGalleryTopBar(
    state: OutputGalleryState,
    actions: OutputGalleryActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions.onBack) {
            Text("<")
        }
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.isSelectionMode) {
            Text(
                text = state.selectedForBatch.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OutputGrid(
    state: OutputGalleryState,
    actions: OutputGalleryActions,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.items, key = { _, item -> item.ref.cacheKey }) { index, item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .combinedClickable(
                        onClick = {
                            if (state.isSelectionMode) {
                                actions.onToggleBatchSelection(index)
                            } else {
                                actions.onOpenItem(index)
                            }
                        },
                        onLongClick = { actions.onLongPressItem(index) },
                    ),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = if (index in state.selectedForBatch) 6.dp else 1.dp,
            ) {
                OutputImageBox(
                    item = item,
                    imageLoader = imageLoader,
                    language = state.language,
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun OutputViewer(
    state: OutputGalleryState,
    item: OutputGalleryItem,
    imageLoader: ImageLoader,
    actions: OutputGalleryActions,
    modifier: Modifier = Modifier,
) {
    var scale by remember(item.ref.cacheKey) { mutableFloatStateOf(1f) }
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
    }
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.94f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions.onCloseViewer) {
                    Text("<", color = Color.White)
                }
                Text(
                    text = state.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .transformable(transformableState),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                    ),
                ) {
                    OutputImageBox(
                        item = item,
                        imageLoader = imageLoader,
                        language = state.language,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            OutputActionRow(state, actions)
            MetadataFooter(state, actions, dark = true)
        }
    }
}

@Composable
private fun OutputImageBox(
    item: OutputGalleryItem,
    imageLoader: ImageLoader,
    language: ConnectionLanguage,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val url = item.imageUrl
        if (url == null) {
            Text(
                text = OutputGalleryCopy.noImageUrl.resolve(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = item.contentDescription,
                imageLoader = imageLoader,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputActionRow(
    state: OutputGalleryState,
    actions: OutputGalleryActions,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = {}, enabled = false) {
            Text(OutputGalleryCopy.save.resolve(state.language))
        }
        OutlinedButton(onClick = {}, enabled = false) {
            Text(OutputGalleryCopy.share.resolve(state.language))
        }
        OutlinedButton(onClick = {}, enabled = false) {
            Text(OutputGalleryCopy.favorite.resolve(state.language))
        }
        FilledTonalButton(onClick = actions.onRunAgain) {
            Text(OutputGalleryCopy.runAgain.resolve(state.language))
        }
        Button(onClick = actions.onTweakAndRun) {
            Text(OutputGalleryCopy.tweakAndRun.resolve(state.language))
        }
    }
}

@Composable
private fun MetadataFooter(
    state: OutputGalleryState,
    actions: OutputGalleryActions,
    dark: Boolean = false,
) {
    if (!state.metadata.hasAnyValue) return
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (dark) 0.dp else 1.dp,
        color = if (dark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = OutputGalleryCopy.metadata.resolve(state.language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onToggleMetadata) {
                    Text(if (state.metadataExpanded) "-" else "+")
                }
            }
            if (state.metadataExpanded) {
                MetadataLine(OutputGalleryCopy.model.resolve(state.language), state.metadata.modelName, foreground)
                MetadataLine(OutputGalleryCopy.prompt.resolve(state.language), state.metadata.prompt?.shorten(200), foreground)
                MetadataLine(OutputGalleryCopy.seed.resolve(state.language), state.metadata.seed, foreground)
                MetadataLine(OutputGalleryCopy.dimensions.resolve(state.language), state.metadata.dimensions, foreground)
                MetadataLine(OutputGalleryCopy.elapsed.resolve(state.language), state.metadata.elapsed, foreground)
                TextButton(onClick = actions.onViewWorkflow) {
                    Text(OutputGalleryCopy.viewWorkflow.resolve(state.language))
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String?, color: Color) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.68f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OutputGalleryEmpty(
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

private val ComfyOutputRef.cacheKey: String
    get() = "$type/$subfolder/$filename"

private fun String.shorten(max: Int): String =
    if (length <= max) this else take(max).trimEnd() + "..."

@Preview
@Composable
private fun OutputGalleryScreenPreview() {
    MaterialTheme {
        OutputGalleryScreen(
            state = OutputGalleryState(
                title = "Portrait workflow",
                items = listOf(
                    OutputGalleryItem(
                        ref = ComfyOutputRef("ComfyUI_00001_.png", "", "output"),
                        imageUrl = null,
                        contentDescription = "Output 1",
                    ),
                    OutputGalleryItem(
                        ref = ComfyOutputRef("ComfyUI_00002_.png", "", "output"),
                        imageUrl = null,
                        contentDescription = "Output 2",
                    ),
                ),
                metadata = OutputMetadata(
                    modelName = "sdxl.safetensors",
                    prompt = "a cinematic portrait with soft studio lighting",
                    seed = "12345",
                    dimensions = "1024 x 1024",
                    elapsed = "18s",
                ),
            ),
            actions = OutputGalleryActions(
                onBack = {},
                onOpenItem = {},
                onCloseViewer = {},
                onToggleMetadata = {},
                onLongPressItem = {},
                onToggleBatchSelection = {},
                onRunAgain = {},
                onTweakAndRun = {},
                onViewWorkflow = {},
            ),
            imageLoader = coil3.ImageLoader(LocalPlatformContext.current),
        )
    }
}
