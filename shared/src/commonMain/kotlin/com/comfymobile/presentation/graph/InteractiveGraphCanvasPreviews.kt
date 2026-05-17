package com.comfymobile.presentation.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Compose previews for [InteractiveGraphCanvas].
 *
 * Demonstrates the gesture-aware variants of [GraphCanvas] across the
 * gesture-state combinations the rendering layer treats specially:
 *  - Identity state (no pan/zoom/selection) — sanity for diff vs
 *    `GraphCanvasIdlePreview`.
 *  - Panned + zoomed in — verifies the `translate` + `scale`
 *    drawscope wrapping.
 *  - Selected node — verifies that selection state is observable in
 *    the gestureState input.
 *  - Interacting (LOD downgrade) — verifies edges render as straight
 *    lines when `isInteracting = true`.
 *
 * The previews wire a real [GestureReducer] so taps actually toggle
 * selection in the IDE preview interactive mode. (Pan / zoom require
 * gestures the IDE preview doesn't synthesise; visual verification of
 * the static layout is the gate this PR aims for. Real touch
 * verification ships in the Android / iOS app entry.)
 */

@Composable
private fun rememberGesture(initial: GestureState = GestureState.Identity): Pair<GestureState, (GestureIntent) -> Unit> {
    var state by remember { mutableStateOf(initial) }
    val dispatch: (GestureIntent) -> Unit = { intent -> state = GestureReducer.reduce(state, intent) }
    return state to dispatch
}

private fun previewLayout(): LayoutResult =
    GraphLayout.layout(graph = previewGraphForGestures())

@Composable
private fun gestureAwarePlan(gesture: GestureState): RenderPlan =
    RenderPlanBuilder.build(
        graph = previewGraphForGestures(),
        layoutResult = previewLayout(),
        resolveStyle = { node ->
            NodeStyleResolver.resolve(
                node = node,
                descriptor = null,
                runtimeStatus = NodeRuntimeStatus.IDLE,
                palette = GraphPalette.defaultLightForTesting,
                isSelected = node.id == gesture.selectedNodeId,
            )
        },
        resolvePortStyle = { port ->
            NodeStyleResolver.resolvePort(port, GraphPalette.defaultLightForTesting)
        },
        resolveTitle = { node ->
            NodeTitleSpec(text = node.classType, italic = false)
        },
        interactiveLodDowngrade = gesture.isInteracting,
    )

// ---------------------------------------------------------------- Identity

@Preview
@Composable
private fun InteractiveGraphCanvasIdentityPreview() {
    val (state, dispatch) = rememberGesture()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            InteractiveGraphCanvas(
                plan = gestureAwarePlan(state),
                layoutResult = previewLayout(),
                gestureState = state,
                onIntent = dispatch,
            )
        }
    }
}

// ---------------------------------------------------------------- Panned + zoomed

@Preview
@Composable
private fun InteractiveGraphCanvasPannedZoomedPreview() {
    val (state, dispatch) = rememberGesture(
        initial = GestureState(panX = -50f, panY = -30f, zoomScale = 1.4f),
    )
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            InteractiveGraphCanvas(
                plan = gestureAwarePlan(state),
                layoutResult = previewLayout(),
                gestureState = state,
                onIntent = dispatch,
            )
        }
    }
}

// ---------------------------------------------------------------- Selected node

@Preview
@Composable
private fun InteractiveGraphCanvasSelectedPreview() {
    val (state, dispatch) = rememberGesture(
        initial = GestureState(selectedNodeId = "2"),
    )
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            InteractiveGraphCanvas(
                plan = gestureAwarePlan(state),
                layoutResult = previewLayout(),
                gestureState = state,
                onIntent = dispatch,
            )
        }
    }
}

// ---------------------------------------------------------------- Interacting (LOD downgrade)

@Preview
@Composable
private fun InteractiveGraphCanvasInteractingLodDowngradePreview() {
    val (state, dispatch) = rememberGesture(
        initial = GestureState(isInteracting = true),
    )
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            InteractiveGraphCanvas(
                plan = gestureAwarePlan(state),
                layoutResult = previewLayout(),
                gestureState = state,
                onIntent = dispatch,
            )
        }
    }
}

// ---------------------------------------------------------------- preview fixture

/**
 * Mini 4-node fixture shared across the previews (mirrors the one in
 * GraphCanvasPreviews to keep the visual diff easy).
 */
private fun previewGraphForGestures(): ParsedUiGraph = ParsedUiGraph(
    nodes = listOf(
        ParsedNode(
            id = "1",
            classType = "CheckpointLoaderSimple",
            originalPos = Position(40f, 40f),
            originalSize = Size(200f, 110f),
            inputs = emptyList(),
            outputs = listOf(
                NodePort(0, "MODEL", "MODEL"),
                NodePort(1, "CLIP", "CLIP"),
                NodePort(2, "VAE", "VAE"),
            ),
        ),
        ParsedNode(
            id = "2",
            classType = "KSampler",
            originalPos = Position(320f, 30f),
            originalSize = Size(220f, 160f),
            inputs = listOf(
                NodePort(0, "model", "MODEL"),
            ),
            outputs = listOf(
                NodePort(0, "LATENT", "LATENT"),
            ),
        ),
        ParsedNode(
            id = "3",
            classType = "VAEDecode",
            originalPos = Position(620f, 60f),
            originalSize = Size(180f, 100f),
            inputs = listOf(
                NodePort(0, "samples", "LATENT"),
                NodePort(1, "vae", "VAE"),
            ),
            outputs = listOf(
                NodePort(0, "IMAGE", "IMAGE"),
            ),
        ),
        ParsedNode(
            id = "4",
            classType = "SaveImage",
            originalPos = Position(880f, 90f),
            originalSize = Size(180f, 80f),
            inputs = listOf(
                NodePort(0, "images", "IMAGE"),
            ),
            outputs = emptyList(),
        ),
    ),
    links = listOf(
        ParsedLink("a", "1", 0, "2", 0, "MODEL"),
        ParsedLink("b", "1", 2, "3", 1, "VAE"),
        ParsedLink("c", "2", 0, "3", 0, "LATENT"),
        ParsedLink("d", "3", 0, "4", 0, "IMAGE"),
    ),
)
