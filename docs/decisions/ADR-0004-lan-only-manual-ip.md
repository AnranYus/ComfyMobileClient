# ADR-0004: LAN-only connection model with manual IP entry

- Date: 2026-05-10
- Status: Accepted
- Decided in: Slock #ComfyMobile (msgs 74164ee0, 040717a0, 646b18e9, e0737b12, 05d8b5fe)
- Owner (decision): @nothing (product), @Alice (architecture), @Ores (UX)

## Context

Mobile users will run ComfyUI on their own hardware (desktop / NAS / homelab). Public-relay or cloud-account models are out of scope for v1. We considered automatic discovery (mDNS/Bonjour) and manual entry.

## Decision

### Connection scope: LAN-only, manual IP

- User enters `host:port` (default port 8188).
- Form validates host (RFC1123 / IPv4 / IPv6 literal) and port (1–65535).
- On submit: `GET /system_stats` with 3s timeout serves as health check.
- WebSocket subscribes via `/ws?clientId=<persistent_uuid>`.

### No auto-discovery in v1

mDNS/Bonjour is **not** implemented for v1. KMP has no off-the-shelf cross-platform mDNS library; auto-discovery would require platform `expect/actual` (`NsdManager` on Android, `NSNetServiceBrowser` on iOS) — engineering cost we don't accept until basic flows ship.

UI reserves a placeholder section for "auto-discovered servers" so a future iteration can add mDNS without restructuring.

### Connect-flow UX compensation

Without auto-discovery, three UX accommodations make manual IP feel polished:

1. **First-launch helper**: tells the user where to find the IP — copy: "On your computer, start ComfyUI; it prints `Listening at http://192.168.x.x:8188`." Plus a screenshot example.
2. **Friendly server label**: after first successful connect, prompt for a name ("My MacBook"). History list shows labels, not raw IPs.
3. **Connection history**: persist successful connections; auto-connect when only one entry; otherwise show picker.

### WiFi-only enforcement

- Refuse connection when active network isn't Wi-Fi.
- Android: `ConnectivityManager.getActiveNetwork()` + `NetworkCapabilities.hasTransport(TRANSPORT_WIFI)`.
- iOS: `NWPathMonitor` with `.usesInterfaceType(.wifi)`.

### Three connection branches (handoff to T0.4 UX)

- **A — Stable WS**: foreground + healthy LAN. Fine-grained progress (`progress`/`progress_state` events), live ETA, current-node label, responsive cancel.
- **B — Foreground LAN flake**: WS drops mid-generation; reconnect via same `clientId`. Recovery target: silent trust-state UI ("still generating…"), restore step bar when events resume. Fall back to `GET /history/{prompt_id}` poll if reconnect doesn't restore events within ~5s.
- **C — Background suspension**: WS drops after ~30s suspension. On foreground return, banner ("Welcome back — checking your generation…"); resolve via `GET /history/{prompt_id}`; reveal completed result or resume live progress.

B and C share a single `Reconnecting(reason: ReconnectReason)` state; only entry trigger and banner copy differ.

### Ghost-state hazard

After B/C resolve, if the server reports completion while we still hold "in progress" locally, we **must** transition to success (via `/history` + `/view`) — never let a finished prompt stay in `in_progress`. Acceptance test in T0.5.

## Alternatives considered

- **mDNS auto-discovery (Option B in design discussion)**: rejected for v1, deferred to post-MVP iteration.
- **Public relay / NAT traversal**: rejected — out of scope.
- **Cloud account**: rejected — out of scope.
- **Foreground service (Android) / silent push (iOS) to keep WS alive in background**: rejected for v1; observe user feedback first before committing to platform-specific battery-impact features.

## Consequences

- Pro: Simple, predictable; ships fast; fewer failure modes.
- Pro: B/C state model unifies recovery code paths.
- Con: Manual IP entry is friction for non-technical users — mitigated by helper copy and friendly labels.
- Con: No background progress tracking; user must accept "we'll catch up when you return."

## Related

- `docs/architecture/T0.1-comfyui-integration.md` §5, §6 for engineering details.
