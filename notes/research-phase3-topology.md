# Phase 3 research — topology mutation seams

**Status:** draft research, not a decision  
**Author:** @Cody (commissioned by @Priestess after v0.1 P0 hotfix, awaiting Phase 3 kickoff)  
**Scope:** locate the seams where `WorkflowConverter` / `WorkflowEnvelope` need to gain write-side support for **add node / remove node / connect / disconnect**, with **confirm + undo + pre-run validation** per @Lily's Q1 three-things.

This document is read-only research. No code shipped. It is meant to become input for an ADR (probably ADR-0005 unless renumbered) when Phase 3 kicks off.

---

## 1. Current state — write paths today

### 1.1 The two domain types
- `domain/workflow/WorkflowEnvelope.kt` — `{ original: JsonElement, format: WorkflowFormat, metadata }`. `original` is treated as immutable. Per ADR-0003, it is **structure-lossless**: every key/value of the imported document survives.
- `domain/workflow/WorkflowGraph.kt` — sealed interface with `Ui(raw: JsonObject)` and `Api(nodes: Map<String, ApiNode>)`. Both data classes, no setters, no `copy`-based mutation API.

### 1.2 The two render projections
- `presentation/graph/ParsedUiGraph.kt` — flat typed `data class` projection of `WorkflowGraph.Ui` for the Compose Canvas renderer. Pure data, no Compose types, kotlinx-`@Serializable`.
- `presentation/graph/UiGraphParser.kt` — the parser that produces `ParsedUiGraph` from `WorkflowGraph.Ui.raw`.

### 1.3 The single converter
- `domain/workflow/WorkflowConverter.kt` — exposes one public function:
  ```
  fun uiToApi(ui: WorkflowGraph.Ui, objectInfo: JsonElement? = null): WorkflowGraph.Api
  ```
  - Pure transformation. No `apiToUi`, no patch / mutate / diff API.
  - Reads `nodes[]` + `links[]` + `widgets_values[]` and emits a fresh `Api` map.
  - Handles UI-only widgets via a whitelist + `UI_ONLY_WIDGETS` strip.

### 1.4 The data lifecycle
- Import path (T1.2 / WorkflowImporter): bytes → `JsonElement` → `WorkflowEnvelope`.
- Persist path (T2.5 / SQLDelight `workflow` table): `WorkflowEnvelope` ↔ row.
- Submit path (`RunCoordinator.onSubmit`): `Ui` → `uiToApi` → POST `/prompt`. No mutation of `Ui` happens here.

**Net:** the entire domain model is write-free today. There is no place in the code where a Workflow's `original` JSON is rewritten, no API to add/remove a node, and no operation log.

---

## 2. What Phase 3 needs

Per @Lily's Q1 + the May 18 Phase 2 close-out summary, Phase 3 unlocks **node-topology editing** behind a confirm + undo + pre-run validation guard.

The minimal mutation alphabet that closes the user story:

| Op | Effect on UI form | Effect on API form on submit |
|---|---|---|
| `AddNode(classType, position)` | Append to `nodes[]` with a fresh id, default widget values from descriptor, no link wiring yet. | New `node_id → ApiNode { class_type, inputs }` entry. |
| `RemoveNode(id)` | Drop from `nodes[]`. Cascade-remove every `links[]` entry whose `source_node_id == id ∨ target_node_id == id`. | Drop entry; any downstream node that previously consumed this id's outputs ends up with an unwired input (must be caught by pre-run validation). |
| `Connect(srcId, srcSlot, dstId, dstSlot)` | Append to `links[]` as a 6-tuple `[link_id, srcId, srcSlot, dstId, dstSlot, type]` where `type` is derived from the source port. Set the destination node's `inputs[dstSlotName].link = link_id`. | The dst node's `inputs[name]` becomes the `[srcId, srcSlot]` 2-tuple. |
| `Disconnect(linkId)` | Drop from `links[]`. Clear `inputs[name].link` on the destination node. | The dst node's `inputs[name]` falls back to a widget value (if any) or becomes missing — pre-run validation must require explicit user intent. |

Out of scope for Phase 3 (per close-out): rename/reorder slot, group operations, viewport-edits beyond pan/zoom.

---

## 3. Where the mutation seam should live — three candidate shapes

### Shape A: mutate `WorkflowGraph.Ui.raw` directly
Make `Ui` a `var` or expose a `mutate(block: (JsonObject) -> JsonObject)` API and rebuild ParsedUiGraph each time.
- **Pro:** structure-lossless by construction — every untouched subtree of `raw` survives.
- **Pro:** the renderer is already wired to `ParsedUiGraph` and would update through the same path import already uses.
- **Con:** spreads JsonObject-rewrite logic across whoever calls `mutate`. Hard to test in isolation.
- **Con:** undo requires either snapshotting the entire `JsonObject` per op (memory) or replaying inverse ops at the JSON level (brittle).

### Shape B: introduce a `WorkingGraph` mutable model on top of `ParsedUiGraph`
Add `presentation/graph/editor/WorkingGraph.kt` (name TBD) with explicit mutation operations:
```kotlin
class WorkingGraph internal constructor(
    private val state: MutableState<ParsedUiGraph>,
    private val log: OpLog,
) {
    fun addNode(classType: String, position: Position): ParsedNode.Id
    fun removeNode(id: ParsedNode.Id)
    fun connect(...): ParsedLink.Id
    fun disconnect(linkId: ParsedLink.Id)
    fun undo()
    fun redo()
    val snapshot: ParsedUiGraph
}
```
- **Pro:** operations are explicit, testable in pure JVM (commonTest), and have a natural inverse for undo (`OpLog`).
- **Pro:** the layout layer's invariants (slot indices, port types) are enforced in one place.
- **Con:** doesn't itself preserve structure-lossless — we need a separate fold-back step at submit / save time that takes the (operation log OR the diff between original and current `ParsedUiGraph`) and applies it to `Envelope.original`, leaving every key the editor doesn't understand alone.

### Shape C: operation log applied at submit time (B + lazy fold)
- Same `WorkingGraph` API as B, but the source of truth at rest is **the original `WorkflowEnvelope.original` plus an op log**. The editor renders the log's `apply(original)`; submitting / saving folds the log into `original` and clears the log.
- **Pro:** undo is `log.pop()`, redo is `log.push(undone)`. Confirm is "advance head pointer". Pre-run validation re-runs the apply once and inspects the result.
- **Pro:** structure-lossless persistence is preserved across sessions — until the user explicitly hits "save", the original document is byte-identical to the imported one.
- **Pro:** crash safety — partial edits in a crash don't corrupt a stored workflow because the op log can be journaled separately from `original`.
- **Con:** apply-on-read is non-trivial for the renderer (re-apply on every change). The op log itself needs schema versioning.
- **Con:** "Pre-run validation" must know what apply-then-uiToApi would produce, not just what `WorkingGraph` says — small extra wiring.

**Cody's lean:** **C**, with `WorkingGraph` from B as the editor-side facade. It's the only one of the three that keeps **structure-lossless** as a hard, observable invariant (not just a comment), which is exactly what ADR-0003 promised. It also makes confirm + undo a 2-line addition rather than a memory snapshot dance.

---

## 4. Write-side gaps in `WorkflowConverter`

Today's converter only knows `uiToApi`. Phase 3 needs the inverse direction in two flavors:

### 4.1 `apiToUi` (recommended)
Reverse the rules so a user-driven `AddNode("KSampler", pos)` from the editor can fold itself into `Ui.raw.nodes[]` with a synthesised widget-values array, correct widget order via `WHITELIST_WIDGET_ORDER`, the `UI_ONLY_WIDGETS` auxiliary widgets re-inserted, and default values pulled from `/object_info`.
- This is the "fold the op log back into UI shape" step from Shape C.

### 4.2 `applyTopologyOps(envelope, log)` (alternative)
Skip the full converter inverse and have a smaller surface that knows only the four ops in §2 and applies them directly to `Envelope.original` as JSON patches.
- Cheaper for Phase 3.
- Pays back when Phase 3+ wants more ops; `apiToUi` is the right long-term home.

**Cody's lean:** start with 4.2 in Phase 3 (smaller blast radius, four ops are small enough to ship as direct JSON edits). Open an ADR follow-up for 4.1 as Phase 4 polish.

---

## 5. Validation surface — what "pre-run validation" must check

Drawn from the existing converter's invariants + the link semantics in `ParsedUiGraph`:

1. **Reference integrity.** Every link's `sourceNodeId` and `targetNodeId` must reference a node currently in `nodes[]`.
2. **Slot integrity.** Every link's `sourceSlot` < `node.outputs.size`, and `targetSlot` < `node.inputs.size`.
3. **Type compatibility.** Link `type` must match the source port's `type` AND the target port's `type` (ComfyUI is strict on type tokens).
4. **No unfed required inputs.** For each node, every input declared by `/object_info[classType].input.required` that is NOT a primitive widget type must have a link wired to it. Primitives without a link fall back to widget values (already encoded in `ParsedNode.widgetsValues`) — those must be present at the expected indices given the `WHITELIST_WIDGET_ORDER` rules.
5. **No cycles** — ComfyUI's executor rejects cyclic graphs; we should refuse to submit one. (Topological sort during validation; minimal overhead.)
6. **Id uniqueness.** New node ids the editor allocates must not collide with existing ones. ComfyUI uses sequential int ids — easy to maintain.
7. **No dangling widgets_values entries.** After the op log applies, `widgets_values.size` for a given node should equal `widgetOrder(classType).size` (ignoring UI-only widget injections); the editor must keep them aligned when widgets change.

Validation runs at three moments:
- **On op apply** (cheap reject — e.g. `Connect` with mismatched port types fails immediately).
- **Pre-run** (full validation, surfaced to user before POST `/prompt`).
- **Post-import** (best-effort cleanup hint — imports of broken workflows should explain what's wrong without erroring at import time).

---

## 6. Touch points already in the codebase

| File | Relevance |
|---|---|
| `domain/workflow/WorkflowEnvelope.kt` | Hosts `original`. Phase 3 must keep `original` unchanged until "save" / "submit" per Shape C. No new fields probably needed in MVP; possibly an `editsCommitted: Boolean` later. |
| `domain/workflow/WorkflowGraph.kt` | Sealed interface. No change needed if we keep mutations in `WorkingGraph`. |
| `domain/workflow/WorkflowConverter.kt` | Inverse-side methods land here (§4). |
| `presentation/graph/ParsedUiGraph.kt` | Data shape that `WorkingGraph` renders. No change in shape; potentially needs `Id` newtypes if we want compile-time-safe `addNode` returns. |
| `presentation/graph/UiGraphParser.kt` | After folding, re-parses `Ui.raw → ParsedUiGraph`. Performance: full reparse per op is fine for v1 (workflows < ~200 nodes; reparse < 5 ms). |
| `presentation/workflow/WorkflowGraphViewModel.kt` | Will likely own the `WorkingGraph` instance and the op log. Confirm/undo/redo dispatch lives here. |
| `data/run/PromptSubmissionPort` (via `HttpClientPromptPort`) | No change needed — submission still receives `Api`. Phase 3 just changes which `Api` is generated (post-fold, post-validation). |

---

## 7. Concrete open questions to settle in the ADR

1. **Op log scope** — per-editor-session (in memory only) vs persisted (SQLDelight `workflow_edit_log`)? Latter survives kill-restart but adds a write path.
2. **Confirm semantics** — does "confirm" mean "save edits into `original` and clear the log" (destructive) or "freeze the log + bump version" (non-destructive history)?
3. **Versioning of saved workflows** — when the user saves an edited workflow, does it overwrite the row or create a new sibling? Library UX implication.
4. **Descriptor reliance** — type compatibility checks need port `type` tokens. If `/object_info` hasn't been fetched for the active server, do we (a) reject the op, (b) accept optimistically and validate at pre-run, or (c) inline-fetch?
5. **Granularity of undo** — single-op undo or grouped (a UI gesture that performs a multi-node move = 1 undo step)?
6. **Pre-run validation modality** — soft warning ("you have an unfed input — submit anyway?") vs hard block. The Phase 2 RunCoordinator surface already has a `submitDisallowed` signal we could reuse for hard blocks.
7. **iOS parity** — the editor surface is in commonMain so iOS gets Phase 3 for free, but new Compose interaction surfaces (drag-to-connect, long-press menus) need T2.1b's gesture conventions extended. No new iOS-specific glue required.

---

## 8. Out of scope for this research note

- The actual ADR-0005 decision (this is upstream of it).
- Editor UI/UX choices: drag-to-connect vs tap-twice, node-palette layout, multi-select. Belongs in a Phase 3 design doc separate from this seam analysis.
- Migration of existing saved workflows (none needed in Shape C — they remain `original`-immutable until edited).
- iOS native gesture work.

---

**Next step after this note lands:** await @Priestess to formalize Phase 3 via ADR-0005, picking among shapes A / B / C and answering §7's open questions.
