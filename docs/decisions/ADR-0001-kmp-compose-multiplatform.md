# ADR-0001: Kotlin Multiplatform with Compose Multiplatform

- Date: 2026-05-10
- Status: Accepted
- Decided in: Slock #ComfyMobile (msg 74164ee0, 46830eec, 1b23a245)
- Owner (decision): @nothing (product), @Alice (architecture)

## Context

We need a cross-platform mobile client for ComfyUI that runs on iOS and Android with maximum code sharing. The app is mobile-first with tablet support, targeted at consumer/light users.

## Decision

- **Platform**: Kotlin Multiplatform (KMP) — targets `androidTarget()`, `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`.
- **UI framework**: Compose Multiplatform (shared UI), with iOS rendering via Skia/Skiko.
- **Module layout**: One `:shared` KMP module containing 99% of code (business / data / presentation), thin `:androidApp` and `iosApp` shells.

## Alternatives considered

- **React Native / Flutter** — rejected: @nothing specified KMP.
- **Native iOS + Native Android** — rejected: doubles UI engineering work; KMP delivers shared business logic and shared UI in one stack.
- **Compose Multiplatform with platform-forked UI (Android Compose + iOS SwiftUI)** — kept as escape hatch (Candidate C in ADR-0002), but not the default.

## Consequences

- Pro: Single Kotlin codebase for business + UI; faster iteration; easier to keep mobile workflow model in sync with desktop ComfyUI.
- Pro: Compose Multiplatform's iOS support is mature in 2026; node-graph rendering can use shared `Canvas { }` API.
- Con: Compose iOS still requires occasional platform `expect/actual` for OS-specific behaviors (network monitoring, file paths, image picker).
- Con: Build complexity higher than single-platform.

## Related

- ADR-0002: Node-graph rendering layer choice.
- `docs/architecture/T0.2-kmp-skeleton-and-rendering.md` for full module layout and dependency catalog.
