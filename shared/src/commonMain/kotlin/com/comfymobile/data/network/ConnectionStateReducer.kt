package com.comfymobile.data.network

/**
 * Pure state-machine for the 3-branch connection model. No
 * coroutines, no clocks, no IO — every transition is deterministic
 * given (state, input) and yields the next state plus a list of
 * [SideEffectIntent] for the runtime to execute.
 *
 * Reference contracts:
 *  - branches A / B / C: `docs/architecture/T0.1-comfyui-integration.md` §6
 *  - shared `Reconnecting(reason)` state: ADR-0004
 *  - test fixtures Lily plans to drive: msg `3466da2b` (5s fallback
 *    poll, 30s give-up, foreground resume, ghost-state settle)
 *
 * @param config tweakable timeouts so tests can run with deterministic
 *               values without affecting production timing.
 */
class ConnectionStateReducer(
    private val config: Config = Config.Default,
    private val clientIdProvider: () -> String,
) {
    data class Config(
        val reconnectFallbackPollAfterMillis: Long,
        val reconnectGiveUpAfterMillis: Long,
    ) {
        companion object {
            val Default: Config = Config(
                reconnectFallbackPollAfterMillis = 5_000L,
                reconnectGiveUpAfterMillis = 30_000L,
            )
        }
    }

    fun reduce(state: ConnectionState, input: ConnectionInput): ConnectionTransition =
        when (state) {
            ConnectionState.Connected -> reduceFromConnected(input)
            is ConnectionState.Reconnecting -> reduceFromReconnecting(state, input)
            is ConnectionState.Lost -> reduceFromLost(state, input)
        }

    // ----------------------------------------------------------------- Connected

    private fun reduceFromConnected(input: ConnectionInput): ConnectionTransition =
        when (input) {
            is ConnectionInput.Ws -> {
                if (input.droppedReason != null) {
                    val reason = input.droppedReason.toReconnectReason()
                    enterReconnecting(reason)
                } else {
                    // Live WS event in Connected state — no transition.
                    ConnectionTransition(ConnectionState.Connected)
                }
            }
            is ConnectionInput.Lifecycle -> {
                // Going to background does NOT pre-emptively transition
                // out of Connected; we wait for the WS drop signal,
                // because some sessions stay alive briefly in
                // background. The drop will arrive as
                // ConnectionInput.Ws(droppedReason = BACKGROUND_SUSPENDED).
                ConnectionTransition(ConnectionState.Connected)
            }
            is ConnectionInput.Network -> ConnectionTransition(ConnectionState.Connected)
            is ConnectionInput.HistoryProbe -> ConnectionTransition(ConnectionState.Connected)
            is ConnectionInput.Timer -> ConnectionTransition(ConnectionState.Connected)
            is ConnectionInput.ConnectAttempt -> ConnectionTransition(ConnectionState.Connected)
            ConnectionInput.Retry -> ConnectionTransition(ConnectionState.Connected)
        }

    // ----------------------------------------------------------------- Reconnecting

    private fun reduceFromReconnecting(
        state: ConnectionState.Reconnecting,
        input: ConnectionInput,
    ): ConnectionTransition = when (input) {
        is ConnectionInput.Ws -> {
            if (input.event != null) {
                // Any event → we're back on the wire. Cancel pending
                // timers and return to Connected.
                ConnectionTransition(
                    next = ConnectionState.Connected,
                    sideEffects = listOf(
                        SideEffectIntent.CancelTimer(TimerTick.ReconnectFallbackPoll),
                        SideEffectIntent.CancelTimer(TimerTick.ReconnectGiveUp),
                    ),
                )
            } else if (input.droppedReason != null) {
                // Already reconnecting; if the new drop reason is
                // "more terminal" (BACKGROUND_SUSPENDED while we were
                // LAN_FLAKE) we promote the reason but do not cancel
                // timers — the state is already in retry mode.
                val newReason = input.droppedReason.toReconnectReason()
                if (newReason == state.reason) {
                    ConnectionTransition(state)
                } else {
                    ConnectionTransition(ConnectionState.Reconnecting(newReason))
                }
            } else {
                ConnectionTransition(state)
            }
        }
        is ConnectionInput.Timer -> when (input.tick) {
            TimerTick.ReconnectFallbackPoll -> {
                // 5s elapsed without WS recovery → start polling
                // /history for any in-flight prompt id. The reducer
                // does not know which prompts are in flight; the
                // runtime layer holds that map and emits one
                // PollHistory side-effect per id when it sees this
                // intent. We schedule the give-up timer if not
                // already set.
                ConnectionTransition(
                    next = state,
                    sideEffects = listOf(
                        SideEffectIntent.PollHistory(promptId = "*"),
                    ),
                )
            }
            TimerTick.ReconnectGiveUp -> {
                ConnectionTransition(
                    next = ConnectionState.Lost(error = ConnectError.UNKNOWN),
                    sideEffects = listOf(
                        SideEffectIntent.CancelTimer(TimerTick.ReconnectFallbackPoll),
                        SideEffectIntent.EmitError(ConnectError.UNKNOWN),
                    ),
                )
            }
        }
        is ConnectionInput.HistoryProbe -> when (input.result) {
            HistoryProbeResult.Completed -> {
                // The server confirms the prompt is done while we
                // were offline. We stay in Reconnecting until the WS
                // is back; the prompt-state defense (ghost-state) is
                // delegated to the generation layer (T1.3) which
                // consumes this same input on its own state machine.
                ConnectionTransition(state)
            }
            HistoryProbeResult.Running -> ConnectionTransition(state)
            is HistoryProbeResult.Error -> ConnectionTransition(state)
        }
        is ConnectionInput.Network -> {
            if (!input.online || !input.wifi) {
                // Lost network entirely — accelerate to Lost without
                // waiting the full 30s.
                ConnectionTransition(
                    next = ConnectionState.Lost(error = ConnectError.UNKNOWN),
                    sideEffects = listOf(
                        SideEffectIntent.CancelTimer(TimerTick.ReconnectFallbackPoll),
                        SideEffectIntent.CancelTimer(TimerTick.ReconnectGiveUp),
                    ),
                )
            } else {
                ConnectionTransition(state)
            }
        }
        is ConnectionInput.Lifecycle -> {
            if (input.foregrounded && state.reason == ReconnectReason.BACKGROUND_RESUMED) {
                // Already reconnecting due to background resume; new
                // foreground signal is informational, no transition.
                ConnectionTransition(state)
            } else if (!input.foregrounded) {
                // Going back to background while reconnecting — keep
                // reason as-is, no transition.
                ConnectionTransition(state)
            } else {
                ConnectionTransition(state)
            }
        }
        ConnectionInput.Retry -> ConnectionTransition(
            next = state,
            sideEffects = listOf(SideEffectIntent.OpenWs(clientId = clientIdProvider())),
        )
        is ConnectionInput.ConnectAttempt -> ConnectionTransition(state)
    }

    // ----------------------------------------------------------------- Lost

    private fun reduceFromLost(
        state: ConnectionState.Lost,
        input: ConnectionInput,
    ): ConnectionTransition = when (input) {
        ConnectionInput.Retry -> enterReconnecting(ReconnectReason.LAN_FLAKE)
        is ConnectionInput.Network -> if (input.online && input.wifi) {
            // Network came back; we'll attempt reconnect implicitly.
            enterReconnecting(ReconnectReason.LAN_FLAKE)
        } else {
            ConnectionTransition(state)
        }
        else -> ConnectionTransition(state)
    }

    // ----------------------------------------------------------------- helpers

    private fun enterReconnecting(reason: ReconnectReason): ConnectionTransition =
        ConnectionTransition(
            next = ConnectionState.Reconnecting(reason),
            sideEffects = listOf(
                SideEffectIntent.OpenWs(clientId = clientIdProvider()),
                SideEffectIntent.ScheduleTimer(
                    tick = TimerTick.ReconnectFallbackPoll,
                    millis = config.reconnectFallbackPollAfterMillis,
                ),
                SideEffectIntent.ScheduleTimer(
                    tick = TimerTick.ReconnectGiveUp,
                    millis = config.reconnectGiveUpAfterMillis,
                ),
            ),
        )

    private fun WsDropReason.toReconnectReason(): ReconnectReason = when (this) {
        WsDropReason.LAN_FLAKE -> ReconnectReason.LAN_FLAKE
        WsDropReason.BACKGROUND_SUSPENDED -> ReconnectReason.BACKGROUND_RESUMED
    }
}
