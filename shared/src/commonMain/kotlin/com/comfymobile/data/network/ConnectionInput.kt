package com.comfymobile.data.network

/**
 * Pure input ADT for [ConnectionStateReducer]. Every external signal
 * the connection state machine cares about — network reachability,
 * app lifecycle, WS frames, history-probe results, timer ticks — is
 * normalised to one of these data classes so the reducer is
 * deterministic, side-effect-free, and trivially testable.
 *
 * Time is intentionally not read from the system clock inside the
 * reducer (per @Lily's seam — `#ComfyMobile:af119226 msg 3466da2b`).
 * All "5 seconds elapsed" / "30 seconds elapsed" decisions are
 * driven by [Timer] inputs the runtime injects after observing the
 * platform clock.
 */
sealed interface ConnectionInput {

    /** Network-layer reachability snapshot from `NetworkMonitor`. */
    data class Network(val online: Boolean, val wifi: Boolean) : ConnectionInput

    /** App lifecycle transition (Android onStart/onStop, iOS UIScene
     *  active/inactive). */
    data class Lifecycle(val foregrounded: Boolean) : ConnectionInput

    /** A WebSocket frame arrived (event != null) OR the WS dropped
     *  (droppedReason != null). Exactly one is set. */
    data class Ws(
        val event: WsEvent? = null,
        val droppedReason: WsDropReason? = null,
    ) : ConnectionInput

    /** Result of a `/history/{prompt_id}` probe issued during
     *  reconnect. */
    data class HistoryProbe(
        val promptId: String,
        val result: HistoryProbeResult,
    ) : ConnectionInput

    /** Time-driven trigger; the runtime decides when to fire each
     *  tick using its own clock. */
    data class Timer(val tick: TimerTick) : ConnectionInput

    /** A connection-attempt outcome from the classifier — allows the
     *  reducer to absorb classified errors without doing the
     *  classification itself. */
    data class ConnectAttempt(
        val classified: ConnectError,
    ) : ConnectionInput

    /** User explicitly retried a failed connection. */
    data object Retry : ConnectionInput
}

/**
 * Why a WebSocket was dropped. Distinguishes "transient blip" from
 * "OS suspended us in background" so the reducer can pick
 * [ReconnectReason] correctly without consulting lifecycle state on
 * its own.
 */
enum class WsDropReason {
    /** Generic close / IO error while we were foregrounded. */
    LAN_FLAKE,

    /** WS closed while we were backgrounded (OS suspension). */
    BACKGROUND_SUSPENDED,
}

/** Outcome of a `/history/{prompt_id}` poll. */
sealed interface HistoryProbeResult {
    /** The prompt finished while we were offline. */
    data object Completed : HistoryProbeResult

    /** The prompt is still running. */
    data object Running : HistoryProbeResult

    /** Probe itself failed (network / 500). */
    data class Error(val error: ConnectError) : HistoryProbeResult
}

/**
 * Discrete time-driven triggers consumed by the reducer.
 *
 * These names are chosen to match the design contract in
 * `docs/ux/T0.4-...md`:
 * - 5s into reconnecting B → start /history fallback polling
 * - 30s into reconnecting B → give up, transition to Lost
 */
enum class TimerTick {
    ReconnectFallbackPoll,
    ReconnectGiveUp,
}
