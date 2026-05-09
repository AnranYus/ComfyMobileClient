package com.comfymobile.data.connection

import com.comfymobile.domain.connection.LifecycleMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fallback [LifecycleMonitor] used when no platform integration is
 * wired. Always reports foregrounded so the connection state machine
 * never speculatively classifies a drop as Branch C
 * (BACKGROUND_RESUMED) without evidence.
 *
 * Production impls (T1.4b part 3c) listen to
 * `ProcessLifecycleOwner` (Android) / UIApplication notifications
 * (iOS).
 */
class AlwaysForegroundedLifecycleMonitor(
    foregrounded: Boolean = true,
) : LifecycleMonitor {

    private val _state = MutableStateFlow(foregrounded)
    override val foregrounded: Flow<Boolean> = _state
}
