package com.comfymobile.data.connection

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.comfymobile.domain.connection.LifecycleMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Android implementation of [LifecycleMonitor] backed by
 * [ProcessLifecycleOwner]. Emits true while the app is in foreground
 * (between `onStart` and `onStop` of the process-level lifecycle),
 * false otherwise.
 *
 * The DI module (T1.4b part 3d) supplies a long-lived [scope]
 * (typically the application coroutine scope) so the underlying
 * lifecycle observer registers exactly once and is shared across
 * subscribers.
 */
class AndroidLifecycleMonitor(
    scope: CoroutineScope,
) : LifecycleMonitor {

    private val rawForegrounded: Flow<Boolean> = callbackFlow {
        val owner: LifecycleOwner = ProcessLifecycleOwner.get()
        // Seed with the current state.
        trySend(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { trySend(true) }
            override fun onStop(owner: LifecycleOwner) { trySend(false) }
        }
        owner.lifecycle.addObserver(observer)

        awaitClose {
            owner.lifecycle.removeObserver(observer)
        }
    }.distinctUntilChanged()

    override val foregrounded: Flow<Boolean> = rawForegrounded.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED),
    )
}
