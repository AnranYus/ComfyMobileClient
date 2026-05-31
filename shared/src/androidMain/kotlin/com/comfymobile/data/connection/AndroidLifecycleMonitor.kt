package com.comfymobile.data.connection

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.comfymobile.domain.connection.LifecycleMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

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
    // Injected so unit tests can substitute a TestDispatcher /
    // Unconfined and assert that addObserver/removeObserver happen on
    // the platform-required main thread. Typed as the broad
    // [CoroutineDispatcher] (rather than [MainCoroutineDispatcher]) so
    // tests can pass `UnconfinedTestDispatcher()` directly. In
    // production this is always `Dispatchers.Main.immediate`; APP_SCOPE
    // itself stays on Dispatchers.Default for everything else.
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    // Injected for unit tests so they don't need a working
    // ProcessLifecycleOwner (which depends on Robolectric or an
    // instrumentation env). Production always uses the real owner.
    private val lifecycleOwnerProvider: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
) : LifecycleMonitor {

    private val rawForegrounded: Flow<Boolean> = callbackFlow {
        val owner: LifecycleOwner = lifecycleOwnerProvider()
        // Seed with the current state. currentState is a volatile read
        // and is safe off the main thread; addObserver below is not.
        trySend(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { trySend(true) }
            override fun onStop(owner: LifecycleOwner) { trySend(false) }
        }
        // Both addObserver and removeObserver assert the main thread
        // (LifecycleRegistry.enforceMainThreadIfNeeded). The flowOn
        // below pins this producer block — including the awaitClose
        // cleanup that calls removeObserver — onto Main.immediate, so
        // even when the downstream collector (APP_SCOPE on
        // Dispatchers.Default) starts collection eagerly, the lifecycle
        // calls always land on the right thread.
        owner.lifecycle.addObserver(observer)

        awaitClose {
            owner.lifecycle.removeObserver(observer)
        }
    }.flowOn(mainDispatcher).distinctUntilChanged()

    // initialValue is a stable false rather than reading
    // `ProcessLifecycleOwner.get().lifecycle.currentState` here.
    // currentState is volatile-safe to read off-thread, but relying on
    // "the first DI resolution happens on Application.onCreate's main
    // thread" was an implicit guarantee that PR review (@Priestess
    // msg `387fd099`) flagged as fragile. The real seed is sent by the
    // callbackFlow's first `trySend` once we have hopped to
    // Main.immediate (which on cold start happens within microseconds),
    // and `distinctUntilChanged` collapses the brief false-to-foreground
    // transition for app-already-foreground starts.
    override val foregrounded: Flow<Boolean> = rawForegrounded.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )
}
