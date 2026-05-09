# ComfyMobileClient

Mobile client for [ComfyUI](https://github.com/comfyanonymous/ComfyUI) — bringing a touch-first image-generation experience to phones and tablets.

## Status

**Phase 0 — alignment & design (closing for review).** No runnable code yet. The repo currently contains:

- Project glossary (`docs/CONTEXT.md`)
- Architecture decisions (`docs/decisions/`)
- Architecture / integration docs (`docs/architecture/`)
- UX / wireframes / control catalog (`docs/ux/`)
- QA / acceptance checklist (`docs/qa/`)
- Skeleton placeholders for the Phase 1 KMP module structure

See [`BOOTSTRAP.md`](BOOTSTRAP.md) for what the first Phase 1 commit will add.

## Project goals

- Bring ComfyUI to mobile with a touch-first interaction experience (mobile-first, tablet-supported).
- Enable light/consumer users to: import existing workflows → tweak key parameters → generate → browse outputs and history.
- **Out of scope for v1:** building workflows from scratch; full topology editing of unknown nodes.

## Key decisions (Phase 0)

| Decision | Choice |
|---|---|
| Platform | Kotlin Multiplatform (Android + iOS) |
| UI | Compose Multiplatform |
| Backend connection | LAN-only, manual `host:port` (no auto-discovery in v1) |
| Workflow roundtrip | Original JSON preserved verbatim; mobile shows simplified view |
| Editable nodes | Whitelist (data-driven node descriptor table); unknown nodes render but are immutable |
| Node-graph rendering | Compose Canvas (Candidate A) — see ADR-0002 |

See `docs/decisions/` for full rationale.

## Architecture

```
ComfyMobileClient/
├── shared/         # KMP module — 99% of code lives here
│   └── src/
│       ├── commonMain/  # business / data / presentation
│       ├── androidMain/ # platform actuals (NetworkMonitor, etc.)
│       └── iosMain/     # platform actuals
├── androidApp/     # Thin Android shell
└── iosApp/         # Thin iOS shell
```

Detailed module layout in `docs/architecture/T0.2-kmp-skeleton-and-rendering.md`.

## Backend compatibility

- Designed and researched against ComfyUI master (HTTP REST + WebSocket). End-to-end integration tests are part of Phase 1 — no runnable client yet.
- Endpoints planned: `/system_stats`, `/object_info`, `/models/*`, `/embeddings`, `/prompt`, `/queue`, `/interrupt`, `/history`, `/view`, `/upload/image`, `/ws`.

See `docs/architecture/T0.1-comfyui-integration.md` for the full reference.

## Team

Built collaboratively in [Slock](https://slock.dev) channel `#ComfyMobile`:

- @Alice — architecture & implementation
- @Ores — UI/UX
- @Lily — review & verification
- @Priestess — task coordination
- @nothing — product owner

## License

TBD.
