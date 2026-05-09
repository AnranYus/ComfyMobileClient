package com.comfymobile.data.network

/**
 * Three-branch connection-state model used by both the network layer
 * and the connection UI shell. See ADR-0004 §3 for the full rationale
 * and `docs/architecture/T0.1-comfyui-integration.md` §6 for the
 * branch definitions.
 *
 * Branch A → [Connected]
 * Branch B (LAN flake while in foreground) and Branch C (background
 * suspension) share the [Reconnecting] state, distinguished only by
 * [ReconnectReason]. Terminal failures land in [Lost].
 */
sealed interface ConnectionState {

    /** Live and healthy WS session, events flowing. */
    data object Connected : ConnectionState

    /**
     * WS dropped, we are attempting to recover. UI uses [reason] to
     * pick the right banner / silent indicator (B = silent trust
     * state, C = explicit "welcome back, checking…" banner).
     */
    data class Reconnecting(val reason: ReconnectReason) : ConnectionState

    /** Terminal: we gave up reconnecting; UX surfaces an error
     *  state with [error] and (optionally) a retry CTA. */
    data class Lost(val error: ConnectError) : ConnectionState
}

/** Why we're reconnecting. UX behaviour differs per reason. */
enum class ReconnectReason {
    /** Branch B: foreground LAN flake (Wi-Fi blip). Silent trust UI. */
    LAN_FLAKE,

    /** Branch C: app was backgrounded long enough that the OS
     *  suspended the WS. Explicit banner. */
    BACKGROUND_RESUMED,
}
