package com.comfymobile.presentation.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compose previews exercising the [GraphCanvas] across the 5
 * runtime-status states + light / dark themes. Per @Lily PR #19
 * thread `4da46760` point 3 (CI gate two-layer): these previews are
 * the *deterministic* gate — they're recorded golden-image-style
 * without a real GPU and survive runner flakiness. Real fps
 * benchmarks are a separate workflow.
 *
 * The fixture is a 4-node mini workflow:
 *   CheckpointLoaderSimple → KSampler → VAEDecode → SaveImage
 * with an unknown 5th node off to the side. Small enough for
 * previews to fit on a phone-shaped canvas without scaling, big
 * enough to exercise both edge bezier rendering and node body /
 * port colouring.
 */

private fun previewGraph(): ParsedUiGraph = ParsedUiGraph(
    nodes = listOf(
        ParsedNode(
            id = "1", classType = "CheckpointLoaderSimple", title = null,
            originalPos = Position(40f, 40f),
            originalSize = Size(220f, 120f),
            outputs = listOf(
                NodePort(0, "MODEL", "MODEL"),
                NodePort(1, "CLIP",  "CLIP"),
                NodePort(2, "VAE",   "VAE"),
            ),
        ),
        ParsedNode(
            id = "2", classType = "KSampler", title = null,
            originalPos = Position(320f, 40f),
            originalSize = Size(220f, 200f),
            inputs = listOf(
                NodePort(0, "model",     "MODEL"),
                NodePort(1, "positive",  "CONDITIONING"),
                NodePort(2, "negative",  "CONDITIONING"),
                NodePort(3, "latent",    "LATENT"),
            ),
            outputs = listOf(NodePort(0, "LATENT", "LATENT")),
        ),
        ParsedNode(
            id = "3", classType = "VAEDecode", title = null,
            originalPos = Position(600f, 40f),
            originalSize = Size(180f, 100f),
            inputs = listOf(
                NodePort(0, "samples", "LATENT"),
                NodePort(1, "vae",     "VAE"),
            ),
            outputs = listOf(NodePort(0, "IMAGE", "IMAGE")),
        ),
        ParsedNode(
            id = "4", classType = "SaveImage", title = null,
            originalPos = Position(820f, 40f),
            originalSize = Size(180f, 80f),
            inputs = listOf(NodePort(0, "images", "IMAGE")),
        ),
        // Unknown / custom node — exercises TITLE_ONLY body mode.
        ParsedNode(
            id = "5", classType = "MyCustomFancyNode", title = null,
            originalPos = Position(40f, 220f),
            originalSize = Size(220f, 60f),
            outputs = listOf(NodePort(0, "weird", "WEIRD_TYPE")),
        ),
    ),
    links = listOf(
        ParsedLink("a", "1", 0, "2", 0, "MODEL"),
        ParsedLink("b", "1", 2, "3", 1, "VAE"),
        ParsedLink("c", "2", 0, "3", 0, "LATENT"),
        ParsedLink("d", "3", 0, "4", 0, "IMAGE"),
    ),
)

@Composable
private fun previewPlan(
    runtimeStatus: Map<String, NodeRuntimeStatus> = emptyMap(),
    palette: GraphPalette = GraphPalette.defaultLightForTesting,
): RenderPlan {
    val graph = previewGraph()
    val layout = GraphLayout.layout(graph)
    return RenderPlanBuilder.build(
        graph = graph,
        layoutResult = layout,
        resolveStyle = { node ->
            // For the preview we hardcode "node #5 is unknown" by class
            // type rather than wiring up a registry; production callers
            // pass the actual NodeDescriptorRegistry.
            val isKnown = node.classType in setOf(
                "CheckpointLoaderSimple", "KSampler", "VAEDecode", "SaveImage",
            )
            NodeStyleResolver.resolve(
                node = node,
                descriptor = if (isKnown) null else null, // both null in preview; full body for known
                runtimeStatus = runtimeStatus[node.id] ?: NodeRuntimeStatus.IDLE,
                palette = palette,
                isSelected = false,
            ).copy(
                bodyMode = if (isKnown) BodyMode.FULL else BodyMode.TITLE_ONLY,
            )
        },
        resolvePortStyle = { port -> NodeStyleResolver.resolvePort(port, palette) },
        resolveTitle = { node ->
            NodeTitleSpec(
                text = node.classType,
                italic = node.classType == "MyCustomFancyNode",
            )
        },
    )
}

// ---------------------------------------------------------------- Idle (no run yet)

@Preview
@Composable
private fun GraphCanvasIdlePreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(plan = previewPlan())
        }
    }
}

// ---------------------------------------------------------------- Running (KSampler is mid-execution)

@Preview
@Composable
private fun GraphCanvasRunningPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(
                plan = previewPlan(
                    runtimeStatus = mapOf(
                        "1" to NodeRuntimeStatus.DONE,    // checkpoint already loaded
                        "2" to NodeRuntimeStatus.RUNNING, // sampler in progress
                    ),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- Cached + Done mix

@Preview
@Composable
private fun GraphCanvasCachedAndDonePreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(
                plan = previewPlan(
                    runtimeStatus = mapOf(
                        "1" to NodeRuntimeStatus.CACHED,
                        "2" to NodeRuntimeStatus.DONE,
                        "3" to NodeRuntimeStatus.DONE,
                        "4" to NodeRuntimeStatus.DONE,
                    ),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- Error (KSampler failed)

@Preview
@Composable
private fun GraphCanvasErrorPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(
                plan = previewPlan(
                    runtimeStatus = mapOf(
                        "1" to NodeRuntimeStatus.DONE,
                        "2" to NodeRuntimeStatus.ERROR,
                    ),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- Dark mode

@Preview
@Composable
private fun GraphCanvasDarkModePreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(
                plan = previewPlan(
                    runtimeStatus = mapOf(
                        "1" to NodeRuntimeStatus.CACHED,
                        "2" to NodeRuntimeStatus.RUNNING,
                    ),
                    // Dark previews still use the fixed type-colour
                    // palette per @Ores §1.4; only the surface /
                    // status accent colours flip.
                    palette = GraphPalette.defaultLightForTesting,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- Empty graph (just shouldn't crash)

@Preview
@Composable
private fun GraphCanvasEmptyPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(plan = RenderPlan(emptyList()))
        }
    }
}
