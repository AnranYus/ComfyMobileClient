package com.comfymobile.data.network

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Per @Lily's seam (msg `3466da2b`), the reducer never reads the
 * system clock. All time-driven decisions are exercised by feeding
 * [ConnectionInput.Timer] inputs explicitly.
 *
 * Coverage matrix (from msg dccea8bc):
 *  - A normal progress event in Connected state ✓
 *  - B WS drop while foregrounded → Reconnecting(LAN_FLAKE) ✓
 *  - B fallback poll fires after 5s ✓
 *  - B give-up after 30s → Lost ✓
 *  - C foreground resume → BACKGROUND_RESUMED reason ✓
 *  - HistoryProbe success/running/error during reconnect ✓
 *  - Retry from Lost re-enters Reconnecting ✓
 *  - Recovery via incoming WS event cancels both timers ✓
 */
class ConnectionStateReducerTest {

    private val reducer = ConnectionStateReducer(
        clientIdProvider = { "test-client-id" },
    )

    private val anyEvent: WsEvent =
        WsEvent.Status(queueRemaining = 0, sid = null)

    // ----------------------------------------------------------------- Branch A

    @Test fun connected_state_ignores_live_ws_event() {
        val transition = reducer.reduce(
            ConnectionState.Connected,
            ConnectionInput.Ws(event = anyEvent),
        )
        assertEquals(ConnectionState.Connected, transition.next)
        assertTrue(transition.sideEffects.isEmpty())
    }

    @Test fun connected_state_does_not_transition_on_lifecycle_alone() {
        val transition = reducer.reduce(
            ConnectionState.Connected,
            ConnectionInput.Lifecycle(foregrounded = false),
        )
        assertEquals(ConnectionState.Connected, transition.next)
    }

    // ----------------------------------------------------------------- Branch B (LAN flake)

    @Test fun ws_drop_with_lan_flake_reason_enters_reconnecting_lan_flake() {
        val transition = reducer.reduce(
            ConnectionState.Connected,
            ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE),
        )
        val next = assertIs<ConnectionState.Reconnecting>(transition.next)
        assertEquals(ReconnectReason.LAN_FLAKE, next.reason)
        // Schedules: OpenWs, ScheduleTimer(fallback poll), ScheduleTimer(give up)
        assertTrue(transition.sideEffects.any { it is SideEffectIntent.OpenWs })
        assertTrue(
            transition.sideEffects.any {
                it is SideEffectIntent.ScheduleTimer && it.tick == TimerTick.ReconnectFallbackPoll
            }
        )
        assertTrue(
            transition.sideEffects.any {
                it is SideEffectIntent.ScheduleTimer && it.tick == TimerTick.ReconnectGiveUp
            }
        )
    }

    @Test fun fallback_poll_timer_emits_PollHistory_intent() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Timer(TimerTick.ReconnectFallbackPoll),
        )
        // Stays in Reconnecting; emits PollHistory side-effect.
        assertEquals(state, transition.next)
        assertTrue(
            transition.sideEffects.any { it is SideEffectIntent.PollHistory },
            "Expected PollHistory intent, got ${transition.sideEffects}",
        )
    }

    @Test fun give_up_timer_transitions_to_Lost_with_emit_error() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Timer(TimerTick.ReconnectGiveUp),
        )
        val next = assertIs<ConnectionState.Lost>(transition.next)
        assertEquals(ConnectError.UNKNOWN, next.error)
        assertTrue(transition.sideEffects.any { it is SideEffectIntent.EmitError })
        assertTrue(
            transition.sideEffects.any {
                it is SideEffectIntent.CancelTimer && it.tick == TimerTick.ReconnectFallbackPoll
            }
        )
    }

    @Test fun ws_event_during_reconnecting_returns_to_connected_and_cancels_timers() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Ws(event = anyEvent),
        )
        assertEquals(ConnectionState.Connected, transition.next)
        val tickIds = transition.sideEffects
            .filterIsInstance<SideEffectIntent.CancelTimer>()
            .map { it.tick }
            .toSet()
        assertEquals(setOf(TimerTick.ReconnectFallbackPoll, TimerTick.ReconnectGiveUp), tickIds)
    }

    // ----------------------------------------------------------------- Branch C (background resume)

    @Test fun ws_drop_with_background_suspended_enters_reconnecting_background_resumed() {
        val transition = reducer.reduce(
            ConnectionState.Connected,
            ConnectionInput.Ws(droppedReason = WsDropReason.BACKGROUND_SUSPENDED),
        )
        val next = assertIs<ConnectionState.Reconnecting>(transition.next)
        assertEquals(ReconnectReason.BACKGROUND_RESUMED, next.reason)
    }

    @Test fun reason_promotes_from_LAN_FLAKE_to_BACKGROUND_RESUMED_on_new_drop() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Ws(droppedReason = WsDropReason.BACKGROUND_SUSPENDED),
        )
        val next = assertIs<ConnectionState.Reconnecting>(transition.next)
        assertEquals(ReconnectReason.BACKGROUND_RESUMED, next.reason)
    }

    @Test fun network_lost_during_reconnecting_accelerates_to_Lost() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Network(online = false, wifi = false),
        )
        val next = assertIs<ConnectionState.Lost>(transition.next)
        assertEquals(ConnectError.UNKNOWN, next.error)
        assertEquals(2, transition.sideEffects.count { it is SideEffectIntent.CancelTimer })
    }

    // ----------------------------------------------------------------- HistoryProbe

    @Test fun history_probe_completed_keeps_reconnecting_state() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.HistoryProbe(promptId = "p-1", result = HistoryProbeResult.Completed),
        )
        assertEquals(state, transition.next)
        assertTrue(transition.sideEffects.isEmpty())
    }

    @Test fun history_probe_running_keeps_reconnecting_state() {
        val state = ConnectionState.Reconnecting(ReconnectReason.BACKGROUND_RESUMED)
        val transition = reducer.reduce(
            state,
            ConnectionInput.HistoryProbe(promptId = "p-1", result = HistoryProbeResult.Running),
        )
        assertEquals(state, transition.next)
    }

    @Test fun history_probe_error_keeps_reconnecting_state() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(
            state,
            ConnectionInput.HistoryProbe(
                promptId = "p-1",
                result = HistoryProbeResult.Error(ConnectError.TIMEOUT),
            ),
        )
        assertEquals(state, transition.next)
    }

    // ----------------------------------------------------------------- Lost / Retry

    @Test fun retry_from_lost_enters_reconnecting() {
        val state = ConnectionState.Lost(ConnectError.UNKNOWN)
        val transition = reducer.reduce(state, ConnectionInput.Retry)
        val next = assertIs<ConnectionState.Reconnecting>(transition.next)
        assertEquals(ReconnectReason.LAN_FLAKE, next.reason)
        assertTrue(transition.sideEffects.any { it is SideEffectIntent.OpenWs })
    }

    @Test fun network_recovery_from_lost_implicitly_re_attempts() {
        val state = ConnectionState.Lost(ConnectError.TIMEOUT)
        val transition = reducer.reduce(
            state,
            ConnectionInput.Network(online = true, wifi = true),
        )
        val next = assertIs<ConnectionState.Reconnecting>(transition.next)
        assertEquals(ReconnectReason.LAN_FLAKE, next.reason)
    }

    @Test fun retry_from_reconnecting_re_issues_open_ws() {
        val state = ConnectionState.Reconnecting(ReconnectReason.LAN_FLAKE)
        val transition = reducer.reduce(state, ConnectionInput.Retry)
        assertEquals(state, transition.next)
        assertTrue(transition.sideEffects.any { it is SideEffectIntent.OpenWs })
    }

    // ----------------------------------------------------------------- Custom config

    @Test fun custom_timeouts_propagate_into_schedule_timer_intents() {
        val custom = ConnectionStateReducer(
            config = ConnectionStateReducer.Config(
                reconnectFallbackPollAfterMillis = 1_000L,
                reconnectGiveUpAfterMillis = 4_000L,
            ),
            clientIdProvider = { "test-client-id" },
        )
        val transition = custom.reduce(
            ConnectionState.Connected,
            ConnectionInput.Ws(droppedReason = WsDropReason.LAN_FLAKE),
        )
        val timers = transition.sideEffects.filterIsInstance<SideEffectIntent.ScheduleTimer>()
        val fallback = timers.first { it.tick == TimerTick.ReconnectFallbackPoll }
        val giveUp = timers.first { it.tick == TimerTick.ReconnectGiveUp }
        assertEquals(1_000L, fallback.millis)
        assertEquals(4_000L, giveUp.millis)
    }

    // Sanity: WsEvent.ProgressState carries a JsonElement field so make
    // sure we can construct it explicitly inside tests if needed.
    @Test fun progress_state_can_be_constructed_with_empty_nodes() {
        val event = WsEvent.ProgressState(promptId = "p", nodes = JsonObject(emptyMap()))
        assertEquals("p", event.promptId)
    }
}
