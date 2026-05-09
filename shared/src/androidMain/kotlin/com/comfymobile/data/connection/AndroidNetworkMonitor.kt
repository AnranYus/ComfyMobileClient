package com.comfymobile.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.comfymobile.domain.connection.NetworkMonitor
import com.comfymobile.domain.connection.NetworkState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Android implementation of [NetworkMonitor] backed by
 * [ConnectivityManager.NetworkCallback].
 *
 * Emits one [NetworkState] per state change. The DI module (T1.4b
 * part 3d) supplies the [Context] and a long-lived [scope] (typically
 * the application coroutine scope); the resulting StateFlow is
 * shared across subscribers via [SharingStarted.Eagerly] so the
 * platform callback registers exactly once.
 */
class AndroidNetworkMonitor(
    context: Context,
    scope: CoroutineScope,
) : NetworkMonitor {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Cold callback flow that observes default-network changes;
     * folded into a hot StateFlow so the registration cost only
     * happens once.
     */
    private val rawState: Flow<NetworkState> = callbackFlow {
        // Seed with the current state so subscribers don't wait for
        // the first OS event after registration.
        trySend(currentSnapshot())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitCurrent()
            override fun onLost(network: Network) = emitCurrent()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emitCurrent()
            override fun onUnavailable() = emitCurrent()

            private fun emitCurrent() {
                trySend(currentSnapshot())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)

        awaitClose {
            cm.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    override val state: Flow<NetworkState> = rawState.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = currentSnapshot(),
    )

    private fun currentSnapshot(): NetworkState {
        val active = cm.activeNetwork
        if (active == null) return NetworkState(online = false, wifi = false)
        val caps = cm.getNetworkCapabilities(active)
            ?: return NetworkState(online = false, wifi = false)
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return NetworkState(online = online, wifi = wifi)
    }
}
