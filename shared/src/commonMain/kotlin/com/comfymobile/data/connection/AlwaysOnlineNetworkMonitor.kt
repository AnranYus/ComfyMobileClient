package com.comfymobile.data.connection

import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.connection.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fallback [NetworkMonitor] used when no platform integration is
 * wired (tests, early Phase 1 startup, or builds without DI).
 *
 * Always reports online + Wi-Fi. Production code in T1.4b part 3c
 * substitutes the platform impls (`AndroidNetworkMonitor` /
 * `IosNetworkMonitor`) via DI.
 */
class AlwaysOnlineNetworkMonitor(
    online: Boolean = true,
    wifi: Boolean = true,
) : NetworkMonitor {

    private val _state = MutableStateFlow(NetworkState(online = online, wifi = wifi))
    override val state: Flow<NetworkState> = _state
}
