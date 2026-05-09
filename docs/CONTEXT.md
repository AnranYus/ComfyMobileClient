# ComfyMobileClient — CONTEXT

> Glossary of canonical terms for **ComfyMobileClient** (the project's repo name; the product is also referred to in chat as "Comfy Mobile" / "ComfyMobile" — these are synonymous). All design docs, ADRs, code, and task descriptions should use these exact terms. If you find yourself wanting a synonym, prefer to extend this glossary or replace one term — never let two terms quietly mean the same thing.
>
> Status: Phase 0 working draft (2026-05-10). Owners may extend; rename only with team agreement.

---

## Users

### 取用型轻度用户 (Light consumer)
The MVP target user. Imports a workflow that someone else already authored, tweaks a small set of key parameters (most often the prompt), runs it, and looks at the result.
- Does **not** build workflows from scratch.
- Does **not** re-wire node graphs.
- **In v1, the user only edits whitelist-node parameters.** Adding or removing nodes is **not** a v1 default capability; if it is unlocked later, it is gated by T0.5's "confirm + undo + pre-run validation" trio and remains restricted to whitelist nodes.

### 工作流作者 (Workflow author)
A user who builds new workflows from scratch on desktop ComfyUI. **Out of scope for v1.** Mentioned only because their output (the workflow JSON) is what 取用型轻度用户 imports.

---

## Workflow

### Workflow
A directed graph of nodes plus their parameters that ComfyUI executes to produce one or more `outputs`. Stored as JSON. Imported, edited, and run as a single unit.

The official Workflow JSON (UI form, per ComfyUI docs) carries these top-level fields:
`nodes[]` (each with its own `widgets_values`, `pos`, `size`, `title`), `links[]`, `groups`, `reroutes`, `state` / `extra`, plus extension fields.
**The mobile client must preserve unknown fields verbatim** to honour roundtrip with desktop ComfyUI.

> **API format vs UI format** (per T0.1 M2):
> - **API format** — flat `{node_id_string: {class_type, inputs}}`, where each input is either a literal or a link tuple `["src_node_id", src_output_index]`. Zero UI metadata. This is what `/prompt` accepts.
> - **UI format** — full desktop ComfyUI export: `nodes[]` (each with `pos`, `size`, `widgets_values`, `title`), `links[]`, `groups`, `version`, etc. **Not** accepted by `/prompt` directly.
> - Mobile must convert UI → API on submit. The hard part is `widgets_values[]` ↔ named-parameter mapping, resolved via `/object_info[class_type].input.required` registration order (whitelist nodes ship hard-coded fallbacks).
> - Mobile must embed the **current UI-format workflow snapshot (after the user's mobile edits)** into `extra_data.extra_pnginfo.workflow` so the generated PNG carries it. Embedding the as-imported original would lose the user's edits in the PNG roundtrip. This embed is what makes "drop the PNG back into desktop ComfyUI to recover the workflow" work — i.e. **roundtrip**.

### Node
One vertex in the workflow graph. Has a `type` (e.g. `KSampler`, `CLIPTextEncode`), zero or more parameter slots, and zero or more input/output ports.

### Link / Edge
A directed connection from one node's output port to another node's input port. Carries a typed value (latent, image, conditioning, …).

### Parameter (slot)
A named value attached to a node — e.g. `KSampler.steps`, `CLIPTextEncode.text`. The set and types depend on the node's `type`.

### Prompt
Two distinct uses; we keep both, disambiguate by context:
- **Prompt (user-facing)** — the natural-language text inside a `CLIPTextEncode` node. The thing users edit when they want a different image.
- **API prompt** — ComfyUI's term for the executable JSON form of a workflow. Use only in technical/T0.1 contexts; never in user-facing copy.

---

## Simplified view

### Simplified view (移动端简化视图)
The mobile UI's reduced presentation of a workflow. Decided by a **node descriptor table**, not by re-shaping the underlying workflow JSON. The original workflow JSON is preserved unchanged so that round-trip with desktop ComfyUI is lossless.

### Whitelist node
A node `class_type` listed in the **node descriptor table** as having editable parameters in mobile. The canonical key is the real ComfyUI `class_type`; friendly display names live in the descriptor entry.

Initial set (10 entries, per T0.3 v1 table):
`CheckpointLoaderSimple`, `CLIPTextEncode`, `KSampler`, `EmptyLatentImage`, `VAEDecode`, `SaveImage`, `LoraLoader`, `ControlNetLoader`, `ControlNetApply`, `ControlNetApplyAdvanced`.

> **ControlNet v1 boundary** (decided 2026-05-10 by Alice + Lily + Priestess, default-adopted unless @nothing objects):
> - `ControlNetLoader.control_net_name` editable.
> - `ControlNetApply.strength` editable (Slider 0..2 step 0.05); other params read-only.
> - `ControlNetApplyAdvanced.strength` editable; `start_percent` / `end_percent` and others read-only.
> - `LoadImage` (control image) read-only in v1 — swapping the control image is "rebuilding the workflow" and falls into v1.5+.
> - Legacy `ApplyControlNet` (no `Advanced` suffix) and any custom ControlNet nodes are treated as unknown nodes — read-only and immutable.

### Unknown node
Any node whose `type` is not in the descriptor table.
- Visible in the mobile view; display label = `class_type` directly from JSON.
- Parameters are **read-only** and rendered as a folded JSON detail view.
- **Topology is immutable in v1**: cannot delete, cannot re-wire links, cannot reorder. Three-way agreed (Lily QA, Alice arch, Ores UX). v1.5 may unlock behind a "confirm + undo + pre-run validation" trio; pre-run validation uses ComfyUI's `node_errors` field on `/prompt` POST.

**UX safety valves (Ores, T0.3)**:
- "重置到导入时" button in the workflow-view top-right menu — one-click rollback to the as-imported state.
- Touching an unknown node surfaces a toast: "此节点暂不支持移动端编辑,可在桌面 ComfyUI 中调整后重新导入".
- Post-MVP "advanced mode" toggle would unlock topology editing for power users.

This is the strongest guarantee for **roundtrip**: for any structure the mobile UI does not understand (unknown nodes, unknown fields, links, layout, etc.), the workflow is **structure-lossless** end-to-end — byte-equivalence isn't required (the user's whitelist edits will of course change parameter bytes), but unsupported parts are preserved exactly.

### Node descriptor table
A data-driven mapping `node type → display name (zh+en) / editable parameters / control widget / short help text`. Lives in config (not source code) so:
- Custom community nodes can be added without a code release.
- @nothing can incrementally extend the table.
- The architecture exposes the abstraction layer in `commonMain` (T0.2); the table itself ships separately.

The descriptor schema (per T0.1 M2):
- `NodeDescriptor` — one entry per `class_type`.
- `ParamDescriptor` — one per editable parameter; carries `ControlType` and `ValueSource`.
- `ControlType` — one of 10: `Number` / `Integer` / `Slider(min, max, step)` / `Toggle` / `SingleLineText` / `MultilineText` / `Dropdown` / `ModelPicker` / `ImagePicker` / `Hidden`. (The mobile UX layer adds three presentation-only variants — Integer-with-stepper, Seed special with 🎲/🔒, MultilineText rich autocomplete — without extending this enum.)
- `ValueSource` — for enumerable parameters, identifies where the values come from at runtime (e.g. `/object_info[type].input.required[param]` for sampler_name/scheduler dropdowns; `/models/{folder}` for ModelPicker; `/embeddings` for embedding autocomplete). **Enum values are never hard-coded.**

> The v1 whitelist of 8 node types and their parameter shapes lives in T0.3's deliverable. CONTEXT.md does not duplicate it; CONTEXT defines the *concepts* the table uses, not the table contents.

---

## Generation

### Job
A single submission of a workflow to ComfyUI. Has a `job id` (ComfyUI's prompt id) and goes through `queued → running → done | error | cancelled`.

### Queue
The list of pending and running jobs on a given ComfyUI server.

### Generation event
A real-time signal from ComfyUI describing job progress. May include step count, ETA, per-node status, and errors. Granularity is server-dependent; T0.1 will pin down the exact event shape.

### Output (产物)
A file produced by a finished job — almost always an image, but may be a video, mask, latent, or other artifact. Identified by `(job id, node id, slot)`. Retrievable from the server via `/view`.

### History
The chronological list of jobs (and their outputs) the user has run on this device.

Sources are layered, not single:
- **Server `/history`** is authoritative for **completion status** (`success` / `error` / `interrupted`) and for **artifact retrievability** of jobs the server still remembers.
- **Mobile-local job index / cache** is authoritative for **product-level history** — it persists `prompt_id`, the workflow snapshot used, key parameters, and a thumbnail/metadata reference so the user's history survives even after the server clears its own `/history`.
- The local index is also what enables **ghost-state recovery** after a B/C reconnect: the client knows which `prompt_id`s it has outstanding and can reconcile against `/history` to settle any stale `in_progress` rows.

---

## Connection

### LAN server
A user-controlled ComfyUI instance reachable on the same local network. **The only supported topology in v1** — no public relay, no managed service, no embedded runtime.

### Manual IP entry
The single connection-discovery mechanism in v1. User types `host:port`. Auto-discovery (mDNS / Bonjour) is intentionally out of scope; UI predefines a slot for a future "auto-discovered servers" section.

### Server label
A user-given friendly name for a saved LAN server (e.g. "我的 MacBook"). Used in the saved-server list instead of the bare IP.

### WebSocket session
The persistent connection from the mobile client to a single LAN server, used for receiving `generation events`. Tolerant to short-term disconnects (WiFi roam, screen sleep) via reconnect.

### Connection branch
One of three operating regimes the connection layer must handle, used to drive UX variants in T0.4 (regime, not transport):
- **A — foreground + healthy LAN**: WebSocket fully consumable; UX shows precise progress (per-step ETA, current-node label, immediate cancel).
- **B — foreground but LAN flutters / WS drops mid-job**: reconnect with same `clientId`, replay from `progress_state`; if events don't resume within ~5s, fall back to polling `/history/{prompt_id}`. UX shows a "still generating…" trust signal during the gap, then snaps back to step progress on recovery.
- **C — backgrounded by the OS** (~30s on iOS/Android the WS is killed): on return-to-foreground, pull `/history/{prompt_id}` directly; the result is either "already finished — show output" or "still running — no live progress until next event". UX shows a return banner ("欢迎回来,正在检查…") then reveals state.

**Implementation note** — B and C share one **`Reconnecting` state** in the connection state machine; only the entry trigger differs (`LAN_FLAKE` vs `BACKGROUND_RESUMED`). B/C are **designed as 3 independent UX variants but built on shared "信任态 UI 原语"** (heartbeat dot + "task is alive" copy + ETA tolerance band) — implementation cost ≈ 2 variants, UX rigor = 3.

**Ghost-state hazard** (Lily T0.5): after a B or C reconnect, if the server-side prompt has finished, the client must consult `/history/{prompt_id}` and `/view` to settle the job to "succeeded" — never leave a stale `in_progress` indicator.

> v1 explicitly does NOT use a foreground service or silent push to keep the WS alive. Cost is high; we'll observe user feedback first.

---

## Product concepts

### 导入 (Import)
The act of bringing an externally-authored workflow JSON into the mobile app. Typical sources: file picker, share sheet, paste text. The original JSON is stored verbatim.

### 工作流视图 (Workflow view)
The mobile screen that renders a workflow as a node graph. Defaults to read-only; opens 参数抽屉 for editable nodes.

### 参数抽屉 (Parameter drawer)
The bottom sheet (phone) or side panel (tablet) that exposes editable parameters for the currently-selected whitelist node.

### 产物画廊 (Output gallery)
The visual collection of `outputs` from a single job — usually one image, sometimes a strip.

### 历史 (History view)
The chronological list of past jobs on this device, each with a thumbnail of its outputs.

---

## Phases (process glossary, not product)

- **Phase 0** — discovery, before any product code. T0.1–T0.6.
- **Phase 1** — infra: project skeleton, ComfyUI client, workflow data model.
- **Phase 2** — MVP loop: import → run → output → history. Reqs 1, 4, 5.
- **Phase 3** — editing: requirements 2, 3 (workflow editing, node add/remove/edit). Hardest phase.
- **Phase 4** — polish & QA: UX walkthrough, full regression, performance, accessibility.

---

## Open terms (to resolve as Phase 0 lands)

- "Edit workflow" vs "edit nodes" — req 2 vs req 3 — exact boundary of "macro" vs "micro" editing in mobile MVP. Pending T0.3 wireframes.
- "节点参数控件" types (slider / picker / multi-line / image-picker / palette) — pending T0.3 v1 descriptor table.
- "Performance floor" — concrete numbers for "smooth dragging" and "progress animation" — pending T0.3 渲染层硬性要求清单.

---

> Update protocol: when terminology drift surfaces in any thread (#ComfyMobile or sub-threads), call it out in-place and update this file in the same turn. Don't batch.
