package com.comfymobile.data.network

/**
 * What [ConnectionStateReducer] asks the runtime to do next, expressed
 * as pure data so the reducer stays side-effect-free and unit-testable.
 *
 * The runtime (`ConnectionEffectRunner`, T1.1 part 2) translates each
 * intent into the actual coroutine action — opening a WS, scheduling
 * a poll, classifying an error — and feeds the outcome back as a
 * [ConnectionInput].
 */
sealed interface SideEffectIntent {

    /** Open a WS connection with the given persisted client id. */
    data class OpenWs(val clientId: String) : SideEffectIntent

    /** Poll `/history/{prompt_id}` to recover offline state. */
    data class PollHistory(val promptId: String) : SideEffectIntent

    /** Schedule a timer tick after [millis]; fires as
     *  [ConnectionInput.Timer] with the matching tick id. */
    data class ScheduleTimer(val tick: TimerTick, val millis: Long) : SideEffectIntent

    /** Cancel a previously scheduled timer (e.g. on successful
     *  reconnect). */
    data class CancelTimer(val tick: TimerTick) : SideEffectIntent

    /** Emit a classifier-ready error attempt downstream so the UI
     *  can pick error copy. */
    data class EmitError(val error: ConnectError) : SideEffectIntent
}

/**
 * Reducer output: next state plus a list of side effects to run.
 * Empty side-effects list is fine; many transitions don't need any.
 */
data class ConnectionTransition(
    val next: ConnectionState,
    val sideEffects: List<SideEffectIntent> = emptyList(),
)
