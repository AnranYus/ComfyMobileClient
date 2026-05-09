package com.comfymobile.data.connection

import com.comfymobile.domain.connection.LifecycleMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * iOS implementation of [LifecycleMonitor] backed by
 * UIApplication notifications.
 *
 *   foreground = UIApplication did become active
 *   background = UIApplication did enter background
 *
 * The DI module supplies a long-lived [scope]; the notification
 * observer is registered exactly once.
 */
class IosLifecycleMonitor(
    scope: CoroutineScope,
) : LifecycleMonitor {

    private val rawForegrounded: Flow<Boolean> = callbackFlow {
        // Default to foregrounded; the first OS event will correct
        // this if we're actually starting in background (rare).
        trySend(true)

        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val foregroundObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = queue,
        ) { _ ->
            trySend(true)
        }
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = queue,
        ) { _ ->
            trySend(false)
        }

        awaitClose {
            center.removeObserver(foregroundObserver)
            center.removeObserver(backgroundObserver)
        }
    }.distinctUntilChanged()

    override val foregrounded: Flow<Boolean> = rawForegrounded.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = true,
    )
}
