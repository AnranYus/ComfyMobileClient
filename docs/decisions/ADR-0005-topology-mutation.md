# ADR-0005: Topology mutation strategy — op-log + lazy fold

- Date: 2026-05-31
- Status: Accepted
- Decided in: Slock #ComfyMobile (msg `7d6c30b1` Phase 3 dispatch, msg `acea64f3` ADR-0005 commission); supersedes the "topology immutable in MVP" stance from [ADR-0003](ADR-0003-workflow-roundtrip-strategy.md) §3.
- Owner (decision): @Priestess (project host), @Cody (main dev), @Longly (review)
- Related research: [notes/research-phase3-topology.md](../../notes/research-phase3-topology.md)

## Context

ADR-0003 froze topology as immutable for Phase 2. Phase 3 unlocks node-topology editing per the May 18 close-out summary and @Lily's Q1: **the user can add nodes, remove nodes, and edit links between them, guarded by confirm + undo + pre-run validation**.

The Phase 2 codebase reaches this point write-free:

- `WorkflowEnvelope.original: JsonElement` is treated as immutable. ADR-0003 promised it would survive **structure-losslessly** across import + export.
- `WorkflowGraph` is a sealed interface with `data class` arms (`Ui(raw: JsonObject)`, `Api(nodes: Map<String, ApiNode>)`) — no mutation API.
- `WorkflowConverter` exposes only `uiToApi(...)`. No `apiToUi`, no patch / mutate / diff.
- `ParsedUiGraph` (the render projection) is read-only `data class` chain (`ParsedNode` / `NodePort` / `ParsedLink` / `Position` / `Size`).

Three design pressures compete:

1. **Lossless invariant.** Whatever Phase 3 ships must not lose user data on round-trip — every unknown field the imported document carried must still be present when the user exits the editor. ADR-0003 made this a hard contract.
2. **Undo / confirm.** The user must be able to back out of any single mutation, and an edit session must be discardable until "confirm".
3. **Pre-run validation.** Submitting an invalid graph (cycle, dangling link, unfed required input) should be caught before `POST /prompt`, with a clear surface to the user.

## Decision

### 1. The mutation alphabet is four ops

Phase 3 ships exactly these:

| Op | Effect on UI form (when folded) | Effect on API form on submit |
|---|---|---|
| `AddNode(classType, position)` | Appends to `nodes[]` with a fresh id; default widget values and widget ordering taken from `/object_info[class_type].input.required` per `T0.1 M2` registration-order rules; no link wiring. **Pre-condition:** `/object_info[classType]` must be available (see §6 Q4); if not, the op is blocked at the editor level with a banner. | New `node_id → ApiNode { class_type, inputs }`. |
| `RemoveNode(id)` | Drops from `nodes[]`. **Compound atomic op:** carries a cascade of synthesised `Disconnect` entries for every `links[]` row whose `source_node_id == id ∨ target_node_id == id`; `undo` reverses the whole bundle in one call (see §6 Q5). | Drops entry. |
| `Connect(srcId, srcSlot, dstId, dstSlot)` | Appends a `links[]` 6-tuple `[link_id, srcId, srcSlot, dstId, dstSlot, type]`. Sets the destination node's `inputs[dstSlotName].link = link_id`. | The dst node's `inputs[name]` becomes the `[srcId, srcSlot]` 2-tuple. |
| `Disconnect(linkId)` | Drops from `links[]`. Clears `inputs[name].link` on the destination node. | The dst node's `inputs[name]` becomes a widget value (if any) or missing — caught by §5. |

Out of scope for Phase 3:

- Rename / reorder slot, node grouping, viewport edits beyond pan/zoom, multi-server type compatibility resolution, custom-node category trees.
- **API-format workflows remain run-only.** This matches Phase 2 PR #40 `WorkflowGraphRoute`'s `apiFormatUnsupported` graceful degrade copy. The four ops in this table assume `WorkflowEnvelope.format == WorkflowFormat.UI`. Unlocking API-format editing requires a full `apiToUi` inverse converter (see §4) and is deferred to Phase 4 polish.

### 2. Editor source of truth = `WorkflowEnvelope.original` + an op log, with `imported_original_json` as the rollback anchor

The `WorkflowEnvelope.original` JSON tree is **not mutated** during an edit session. Instead, the editor maintains an `OpLog: List<TopologyOp>` alongside it. At every render, the editor shows the result of `apply(original, log)`. At submit / save, the log is folded into a new `JsonElement` and the log clears.

**The "reset to imported" rollback (T0.3 / CONTEXT.md UX safety valve) is preserved with a second persisted column.** SQLDelight's `workflow` table gains an `imported_original_json TEXT NOT NULL` column, initialised on import to the same value as `original_json`. From Phase 3 onward:

- `imported_original_json` is **immutable for the lifetime of the row**. It is the as-imported JSON, byte-identical to what `WorkflowImporter` produced.
- `original_json` is the "current head" — what `WorkingGraph.confirm()` writes into when the user commits a session.
- "Reset to imported" rebuilds `WorkflowEnvelope` from `imported_original_json`, sets `original_json = imported_original_json`, and clears any in-flight `OpLog`. ADR-0003's lossless guarantee carries forward unbroken.

The schema migration lands in v0.2 as the first concrete data migration (see §Related — to be tracked under ADR-0006 / v0.2 data migration when that ADR opens).

Why this design and not the alternatives (see §Alternatives considered):

- It's the only candidate where **structure-lossless across an edit session is enforced by construction**, not by reviewer vigilance.
- The `imported_original_json` column gives "reset to imported" a single-row, O(1) implementation. Without it, supporting the same UX would either forbid confirm (breaking the editing story) or require derived event-sourcing on top of `original_json` (heavy).
- Undo collapses to `log.removeLast()`. Redo is `redoStack.removeLast() → log.add`. Confirm is "fold log into `original_json`, clear log + redoStack; `imported_original_json` untouched". No memory snapshots, no `JsonObject` diff algorithm.
- Cross-session crash safety: until "confirm" the `original_json` on disk is byte-identical to its last confirm point (which on import equals `imported_original_json`). Adding journal persistence for the in-flight log later is additive.

### 3. The mutable editor facade is `WorkingGraph`

`presentation/graph/editor/WorkingGraph.kt` exposes:

```kotlin
class WorkingGraph(
    private val envelope: WorkflowEnvelope,            // current head — = imported on a fresh row
    private val importedOriginal: JsonElement,         // §2 rollback anchor
    private val descriptors: NodeDescriptorRegistry,
    private val objectInfo: JsonElement?,
) {
    val rendered: StateFlow<ParsedUiGraph>     // apply(envelope.original, log) → parser
    val canUndo: StateFlow<Boolean>
    val canRedo: StateFlow<Boolean>
    val canAddNode: StateFlow<Boolean>         // false when objectInfo is null — §6 Q4
    val validation: StateFlow<ValidationResult> // §5

    /**
     * Throws if `objectInfo[classType]` is unavailable; UI should
     * disable the AddNode affordance via [canAddNode] / per-class
     * lookup before invoking.
     */
    fun addNode(classType: String, position: Position): ParsedNode.Id
    fun removeNode(id: ParsedNode.Id)
    fun connect(src: PortRef, dst: PortRef): ParsedLink.Id
    fun disconnect(linkId: ParsedLink.Id)

    fun undo()
    fun redo()

    /** Submit-time materialization. Doesn't clear the log. */
    fun materializeUiSnapshot(): WorkflowGraph.Ui
    fun materializeApi(): WorkflowGraph.Api

    /** Persist the log into envelope.original_json, clear log + redoStack. imported_original_json untouched. */
    fun confirm(): WorkflowEnvelope

    /**
     * T0.3 "Reset to imported" UX safety valve. Rebuilds envelope
     * from [importedOriginal], clears log + redoStack. Always
     * available, regardless of whether confirm() has run before.
     */
    fun resetToImported(): WorkflowEnvelope
}
```

`WorkflowGraphViewModel` owns the `WorkingGraph` instance. All editor UI dispatches through it.

### 4. The converter gains `applyTopologyOps`, not full `apiToUi`

`WorkflowConverter` gets one new method:

```kotlin
fun applyTopologyOps(original: WorkflowGraph.Ui, log: List<TopologyOp>): WorkflowGraph.Ui
```

It walks the log, applying each op as a targeted JSON edit on a copy of `original.raw`. Unknown fields the editor doesn't understand pass through untouched. We deliberately do **not** introduce a full `apiToUi` in Phase 3 — the four-op surface is small enough that targeted edits beat a generic inverse converter on both implementation cost and review surface. Full `apiToUi` is parked for Phase 4 polish.

`uiToApi` (the existing forward direction) is unchanged. Submit path becomes `uiToApi(working.materializeUiSnapshot())`.

### 5. Pre-run validation surface

`ValidationResult` is computed on every op (cheap rejects) and re-computed once before submit (full pass). The seven checks:

| # | Check | Severity | Where |
|---|---|---|---|
| V1 | **Reference integrity** — every link's source/target node id exists in `nodes[]`. | Hard block. | On-op + pre-run. |
| V2 | **Slot bounds** — `sourceSlot < node.outputs.size`, `targetSlot < node.inputs.size`. | Hard block. | On-op + pre-run. |
| V3 | **Type compatibility** — link `type` matches both source and target port `type`. | Hard block when ports' types are known; soft warn when `/object_info` for either side is unknown. | On-op + pre-run. |
| V4 | **No unfed required inputs** — for each node, every required input declared by `/object_info[classType].input.required` that is NOT a primitive widget type must have a link wired. Skipped per affected class when `/object_info[classType]` is missing (§6 Q4). | Soft warn ("submit anyway?"). | Pre-run only — the editor lets the user wire inputs in any order. |
| V5 | **No cycles** — Kahn's algorithm on the projected DAG. ComfyUI's executor rejects cyclic graphs. | Hard block. | Pre-run only. (Per-op incremental check is parked as a polish item — see §Consequences.) |
| V6 | **Id uniqueness** — newly-allocated node ids do not collide with existing ones. ComfyUI uses sequential ints; we mint `max(existing) + 1`. | Invariant, enforced by `WorkingGraph` allocator. | On-op (by construction). |
| V7 | **`widgets_values` alignment** — after fold, `widgets_values.size == widgetOrder(classType).size` (counting `UI_ONLY_WIDGETS`). Requires `/object_info[classType]` to know `widgetOrder`; same skip path as V4 when missing (§6 Q4). | Hard block. | Pre-run only. |

Maps to T0.5 acceptance suite: **V1 + V2 + V3 + V7 jointly underpin G-07** ("UI → API 转换可靠 ... link 和 widget 参数映射正确") — without those four checks the conversion can produce an invalid prompt. V4 (unfed required inputs), V5 (no cycles) and V6 (id uniqueness) are net-new invariants Phase 3 introduces and have no direct T0.5 ID. New IDs Phase 3 will introduce: G-15..G-21 (one per check above) — landed in a follow-up to `docs/qa/`. (G-04 "不重复提交" is submit idempotency, orthogonal to all seven checks here.)

### 6. Specific answers to the questions Phase 2 left open

| # | Question | Decision | Rationale |
|---|---|---|---|
| Q1 | Op log scope — in-memory or persisted? | **In-memory per editor session.** | Crash-safety of the *original* document is already preserved by §2. Persisting the log is additive (`workflow_edit_log` table) and is parked for v0.3+. |
| Q2 | Confirm semantics — destructive fold or freeze + version? | **Destructive fold by default.** A "save as new" command (sibling row) is available from the editor menu. | Matches the user mental model of a single editable file. Versioning UI is its own polish track. |
| Q3 | When user saves an edited workflow — overwrite or sibling row? | **Overwrite** by default; "save as new" is the explicit non-default path. | Avoids unbounded sibling-row proliferation in the library. |
| Q4 | Descriptor reliance — what if `/object_info` is missing? | **Asymmetric: edits on existing nodes are optimistic, new node creation is blocked.** Specifically: (a) **`AddNode` requires `/object_info[classType]`** for the class being added — the op cannot synthesise default widget values or widget order without it, and is blocked at the editor level with a banner "此节点类型尚未从服务器加载，请重连或等待 schema 同步". The class-availability check exposes through `WorkingGraph.canAddNode` so the UI can disable the affordance. (b) Edits on **already-imported** nodes (Connect / Disconnect / RemoveNode) stay editable; pre-run validation degrades: V3 downgrades to a soft warning per port whose type token can't be checked; V4 and V7 require descriptors for the affected node's class — same skip path as V3 when the entry is missing, with a "validation incomplete: server didn't return object_info" banner. | The server is the authority on schemas. Refusing all edits when offline would punish the LAN-disconnected case (per ADR-0004 LAN-only); refusing AddNode specifically is mandatory because the op has no defensible fallback for "what widgets does this class have". |
| Q5 | Undo granularity — single-op or gesture-grouped? | **Single-op for Phase 3, with one carve-out:** `RemoveNode` is a **compound atomic op** — its log entry carries the cascade of synthesised `Disconnect` entries (one per `links[]` row whose `source_node_id == removedId ∨ target_node_id == removedId`), and `undo` reverses the node deletion + all cascaded link removals in one call. Gesture-grouping (e.g. drag-select multi-node delete = 1 undo) is a different concept — it bundles **multiple distinct user ops** under one undo step and is deferred to Phase 3.x polish via `OpLog.beginGroup() / endGroup()`. | Smallest correct surface for user-perceived "one action = one undo": cascade is part of `RemoveNode`'s defined semantics in §1 and cannot meaningfully be split across log entries without breaking the "one undo, one node back" expectation. Gesture grouping is purely additive. |
| Q6 | Validation modality — hard block or soft warn? | **Per-check** (see §5 table). | Structural invariants (V1, V2, V5, V7) block; semantic ambiguity (V3 when types unknown, V4 unfed input) warns. |
| Q7 | iOS parity? | **Full parity at the data layer; no iOS-specific glue.** Drag-to-connect / long-press menu gestures extend T2.1b conventions in commonMain. | The editor surface is entirely commonMain. Compose Multiplatform handles input on both targets. |

## Alternatives considered

- **Shape A: Mutate `WorkflowGraph.Ui.raw` directly** (treat `JsonObject` as the source of truth, rebuild `ParsedUiGraph` on every edit). Rejected. Spreads JSON-rewrite logic across whoever invokes the mutate function; testing in isolation is awkward; undo requires whole-tree snapshots OR JSON-level inverse-ops (brittle); ADR-0003's structure-lossless promise becomes "reviewer must check that no edit drops a key" instead of being structural.
- **Shape B: `WorkingGraph` mutable model, no op log** (state-only undo via copy-back). Rejected. Same lossless concern as A unless we also keep `original` immutable, at which point we have most of Shape C's machinery without its observability benefits. We pay full cost, miss "log can be journaled" later.
- **Full `apiToUi` inverse converter in Phase 3.** Rejected for now. A complete inverse must round-trip every UI-only field (viewport, group boxes, custom-node extension keys, `widgets_values` ordering for arbitrary classTypes). At the scope of the four ops above, targeted JSON edits are 1-2 orders of magnitude smaller. Park for Phase 4 polish.
- **Persisted op log from day one (`workflow_edit_log` SQLDelight table).** Rejected for MVP. The original document on disk is untouched until confirm, so crash-recovery already preserves the user's last saved state. Persisting in-flight edits across kill-restart is a polish path, additive.
- **Hard-block on every validation failure.** Rejected. V3 (type compatibility under unknown descriptors) and V4 (unfed input — the user might be partway through wiring) would force ordering constraints onto the editor UX that don't match how desktop ComfyUI works.

## Consequences

- Pro: ADR-0003's structure-lossless contract holds by construction across editor sessions — `WorkflowEnvelope.original_json` is bit-identical to its last confirm point (which on a fresh import equals `imported_original_json`) until the user hits "confirm" or "save as new".
- Pro: T0.3 "reset to imported" remains a one-row, O(1) operation even after the user has edited and confirmed — `imported_original_json` is the rollback anchor.
- Pro: Undo / confirm / pre-run validation collapse to small, testable surfaces (`OpLog.removeLast`, `WorkingGraph.confirm`, `ValidationResult` recompute). Each is unit-testable in commonTest without Compose or platform glue.
- Pro: The four ops are small enough to ship as targeted JSON edits via `applyTopologyOps`, avoiding the larger `apiToUi` work.
- Pro: iOS parity is free at the data layer; only gesture surface needs T2.1b extension.
- Con: Render path goes through `apply(original_json, log) → ParsedUiGraph` on every state change. Workflows up to ~200 nodes reparse in <5 ms; larger workflows may need incremental reparse — flagged for Phase 4 polish if perf regresses.
- Con: `applyTopologyOps` lives next to `uiToApi` in `WorkflowConverter`; two methods with overlapping invariants but disjoint scopes. Future readers may try to merge them into one — KDoc on the class should pin the asymmetry.
- Con: Decision Q4 (asymmetric — AddNode blocked, edits on imported nodes stay optimistic when `/object_info` is missing) means LAN-disconnected editing of existing nodes can produce a graph that the server later rejects. We surface this via a banner; the user accepts the tradeoff.
- Con: V5 cycle detection is pre-run only — the user can wire a cycle mid-edit and only sees the error on submit. An incremental per-op check is doable (reverse-reachability from `dst` to `src` at `Connect` time) but parked as a v0.2.1 polish item to keep Phase 3's surface tight.
- Schema deferred: the new `imported_original_json` column on the SQLDelight `workflow` table is the first concrete data migration for v0.2. It will be tracked under a separate ADR-0006 / "v0.2 data migration" when that ADR opens; this ADR commits to the column existing and being initialised at import time, but does not specify the migration mechanics.

## Related

- [ADR-0001](ADR-0001-kmp-compose-multiplatform.md): KMP + Compose Multiplatform. Editor surface lives in commonMain.
- [ADR-0002](ADR-0002-rendering-layer-candidate-A.md): Compose Canvas renderer. `ParsedUiGraph` is its input; this ADR only adds a new producer for that input.
- [ADR-0003](ADR-0003-workflow-roundtrip-strategy.md): Structure-lossless `original` is what this ADR has to preserve across edits.
- [ADR-0004](ADR-0004-lan-only-manual-ip.md): LAN-only deployment. Q4's asymmetric LAN-disconnected behaviour (AddNode blocked, edits on imported nodes optimistic) follows from "may be offline against the editing server".
- **ADR-0006 (forthcoming): v0.2 data migration** — owns the `workflow.imported_original_json` schema migration this ADR depends on. To be opened when v0.2 implementation starts.
- `docs/architecture/T0.1-comfyui-integration.md` §3 — UI vs API format details. §3.5 / T0.1 M2 — `/object_info[class_type].input.required` widget registration-order rules referenced by §1 `AddNode`.
- `docs/qa/T0.5-*.md` — acceptance suite this ADR extends with G-15..G-21.
- Phase 2 PR #40 `WorkflowGraphRoute` — the `apiFormatUnsupported` graceful degrade copy that §1 out-of-scope inherits.
