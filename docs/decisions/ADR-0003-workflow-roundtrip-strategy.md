# ADR-0003: Workflow roundtrip strategy — preserve original JSON, simplify UI

- Date: 2026-05-10
- Status: Accepted
- Decided in: Slock #ComfyMobile (msg 74164ee0, 46830eec, c7dd63ae)
- Owner (decision): @nothing (product), @Alice (architecture), @Ores (UX), @Lily (acceptance)

## Context

ComfyUI workflows can be authored on desktop and shared as JSON. Mobile users primarily *consume* such workflows. We must support import and re-export without losing fidelity, while presenting a simplified mobile editing UI suitable for light users.

## Decision

### 1. Original JSON preserved verbatim

Every imported workflow is stored as `original: JsonElement` in `WorkflowEnvelope`. We **never** strip fields we don't recognize. Mobile-side edits derive new representations alongside the original; on export, the original (potentially with parameter overrides applied) is what gets sent.

### 2. Two formats both handled

ComfyUI defines two JSON shapes:

- **API format**: flat `{node_id_string: {class_type, inputs}}`. What `/prompt` accepts.
- **UI format**: full editor save with positions, links, groups, viewport, widgets_values. Not directly accepted by `/prompt`.

Mobile imports either format. On submit:
- If imported as UI format: derive API format on the fly via `WorkflowGraph.Ui.toApi()`.
- If imported as API format: use directly.
- Always set `extra_data.extra_pnginfo.workflow` to the **current** UI-format snapshot reflecting any mobile edits (not the originally imported JSON), so generated PNGs embed the workflow that produced them. If imported as API-only, synthesize a minimal UI snapshot.

### 3. Whitelist describes what's editable; everything else is read-only but visible

Whitelist v1 (8 node families): `CheckpointLoaderSimple`, `CLIPTextEncode`, `KSampler`, `EmptyLatentImage`, `VAEDecode`, `SaveImage`, `LoraLoader`, `ControlNetLoader`.

Non-whitelist (unknown) nodes:
- Render in the graph (using `class_type` as label).
- Parameters shown read-only as collapsed JSON.
- **Topology immutable** in MVP: no delete, no edge edit.
- "Reset to imported" button always available as safety valve.

### 4. Whitelist is data-driven, not hardcoded

Defined as `NodeDescriptor` table loaded from external config. Adding nodes is a config change, not a code change. UI controls supported: 8 types (Number, Integer, Slider, Toggle, SingleLineText, MultilineText, Dropdown, ModelPicker, ImagePicker, Hidden) — sufficient for the MVP whitelist; no need to extend.

### 5. Dynamic enum sourcing

Selectable values come from the server, not baked:
- `sampler_name` / `scheduler` etc → `/object_info[classType].input.required[paramName]` enum.
- Model names → `/models/{folder}` (checkpoints, loras, controlnet…).
- Embedding names → `/embeddings`.

## Alternatives considered

- **Strip everything we don't understand** — rejected. Breaks roundtrip, frustrates users sharing workflows back to desktop.
- **100% feature parity with desktop ComfyUI editor** — rejected by @nothing. Out of scope for light-user MVP.
- **Hardcoded enum lists** — rejected. ComfyUI ecosystem evolves; hardcoding breaks at every release.

## Consequences

- Pro: Roundtrip is safe by construction; PNGs from mobile work on desktop.
- Pro: Phase 2 (import / generate / output) and Phase 3 (edit) are decoupled. MVP can ship with limited editing.
- Pro: Whitelist evolves without releases.
- Con: Mobile UI silently ignores nodes outside the whitelist for parameter editing — some users may want more.
- Con: Type-checking edge edits would require parsing all `/object_info` schemas; we don't do that in MVP.

## Related

- ADR-0004: LAN connection model.
- `docs/architecture/T0.1-comfyui-integration.md` §3, §3.5 for detailed JSON formats and descriptor schema.
