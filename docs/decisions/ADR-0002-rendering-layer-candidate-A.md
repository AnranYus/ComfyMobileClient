# ADR-0002: Node-graph rendering layer — Candidate A (Compose Canvas)

- Date: 2026-05-10
- Status: Accepted (with mitigation plan)
- Decided in: Slock #ComfyMobile threads `:1b23a245`, `:2ddbb9d4` (msgs 3952e5cb, 207d1737, d1a6675e)
- Owner (decision): @Alice (architecture), @Ores (UX recommendation)

## Context

ComfyUI workflows are node graphs. The mobile client must display them touch-first on phones and tablets, and let light users edit a curated subset of node parameters. We evaluated three rendering candidates against @Ores's hard requirements.

## Decision

**Candidate A — pure Compose Canvas (`drawScope`)** as MVP rendering layer.

The render layer is encapsulated under `presentation/editor/graph/` so it can be swapped for B or C later without touching business logic.

## Hard-requirements scoring (T0.3 input)

| # | Requirement | A (Compose Canvas) | B (Skiko direct) | C (Platform fork) |
|---|---|---|---|---|
| 1 | ≥30 nodes on screen | ✅ | ✅ | ✅ |
| 2 | Mandatory gestures (pinch/long-press/tap/swipe; **no** wire-drag, multi-select) | ✅ | ⚠️ self-implement | ✅ |
| 3 | Param controls **outside** canvas (drawer/sidebar) | ✅ | ✅ | ✅ |
| 4 | Node bodies + bezier wires + state highlights | ✅ | ⚠️ self text layout | ✅ |
| 5 | 60fps drag, ≤200ms drawer, smooth progress anim | ⚠️ tight; needs viewport virtualization + line cache | ✅ headroom | ✅ |
| 6 | Tablet split (left graph / right params) | ✅ | ✅ | ✅ |
| 7 | Dark mode | ✅ MaterialTheme | ⚠️ | ✅ |
| 8 | a11y (TalkBack/VoiceOver) | ✅ Compose semantics | ⚠️ manual | ✅ |
| 9 | i18n / RTL | ✅ | ⚠️ | ✅ |

**A wins 7/9 cleanly. B's only edge is #5 — buying that one cell costs us 5 cells of self-implementation work. Not worth it for MVP.**

## Mitigation plan for #5

Tried in order; each step keeps Candidate A viable:

1. **Viewport virtualization** — render only visible region + 1-screen buffer.
2. **Static-layer caching** — cache node bodies as `ImageBitmap`; only redraw lines and dragged node during gestures.
3. **Wire-layer LOD** — during drag, render straight orthogonal lines; fall back to bezier on release.
4. **Compose recomposition discipline** — `key()` + `derivedStateOf`; shallow gesture-modifier chains.

If after all four mitigations a 30-node workflow still misses 60fps on Pixel 6a-class Android + iPhone 12, we trigger a "cost hint" feedback to the UX thread and choose between:
- (a) reducing node ceiling to ≤20
- (b) accepting 30fps drag
- (c) keeping wires as orthogonal lines permanently
- (d) escalating to Candidate B (Skiko direct draw)

Lily added Phase 1 perf gate in T0.5 acceptance: drag must be ≥60fps for 30 nodes on baseline mid-tier Android + iPhone 12, otherwise profiling required and downgrade/upgrade reviewed (not silently accepted).

## Alternatives considered

- **Candidate B (Skiko direct draw)** — rejected for MVP. Higher perf ceiling but worse for accessibility, theming, i18n, gestures, text layout. Reserved as upgrade path.
- **Candidate C (platform fork: Android Compose + iOS SwiftUI native graphics)** — rejected for MVP. Doubles UI engineering. Reserved as escape hatch in case of unforeseen iOS Skiko issues.

## Consequences

- Pro: Single rendering implementation, fast iteration, good a11y/theming/i18n out of the box.
- Pro: Shared with the rest of Compose Multiplatform — no special cross-platform glue.
- Con: Performance ceiling lower than B; need disciplined drawing optimizations.
- Con: If a future feature (e.g. real-time wire-dragging) needs B's ceiling, we'll incur a migration.

## Related

- `docs/architecture/T0.2-kmp-skeleton-and-rendering.md` §M2 for the full matrix.
