# `:shared` — Kotlin Multiplatform module

This module holds 99% of the app's code (business logic, data, presentation). Targets:

- `androidTarget()`
- `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`

## Planned source-set layout

```
shared/src/
├── commonMain/kotlin/com/comfymobile/
│   ├── core/             # DI (Koin), Result, coroutine scopes
│   ├── domain/
│   │   ├── workflow/     # WorkflowEnvelope, WorkflowGraph (Ui/Api), ApiNode
│   │   ├── node/         # NodeDescriptor, ParamDescriptor, ControlType, ValueSource
│   │   ├── server/       # ServerInfo, ConnectionState (3-branch model)
│   │   └── generation/   # Generation state machine, ghost-state recovery
│   ├── data/
│   │   ├── network/      # ComfyHttpClient (Ktor REST), ComfyWebSocket (Ktor WS), DTOs
│   │   ├── persistence/  # SQLDelight stores: ServerHistory, Workflow, GenerationCache
│   │   └── descriptor/   # NodeDescriptorRegistry + builtin v1 descriptors
│   ├── presentation/     # ViewModels + State per screen (Compose UI)
│   └── platform/         # expect interfaces (NetworkMonitor, PlatformPaths)
├── androidMain/kotlin/com/comfymobile/platform/
│   └── (actual: ConnectivityManager-backed NetworkMonitor, etc.)
└── iosMain/kotlin/com/comfymobile/platform/
    └── (actual: NWPathMonitor-backed NetworkMonitor, etc.)
```

## Planned dependencies

See `gradle/libs.versions.toml` (to be added in Phase 1.0). Highlights:

- Ktor 3 (client core, content-negotiation, websockets, OkHttp engine, Darwin engine)
- kotlinx.serialization (JSON; preserves unknown fields via `JsonElement`)
- SQLDelight 2.x (cross-platform SQLite)
- Koin 4 (DI)
- Multiplatform Settings (small persistent prefs)
- Coil 3 (Multiplatform image loading)
- Napier (cross-platform logging)

## Status

Skeleton-only. Phase 1.0 will add the actual `build.gradle.kts` and `commonMain` placeholder files.
