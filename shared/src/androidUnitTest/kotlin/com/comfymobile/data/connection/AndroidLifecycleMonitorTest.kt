package com.comfymobile.data.connection

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Regression test for the P0 launch crash diagnosed from the
 * `06-01 02:31:44` logcat: `LifecycleRegistry.addObserver` was being
 * invoked from `DefaultDispatcher-worker-7` because the upstream
 * `callbackFlow` ran in the [AndroidLifecycleMonitor]'s eagerly-started
 * `stateIn` scope, which is `APP_SCOPE = Dispatchers.Default`.
 *
 * The fix wraps the producer in `flowOn(mainDispatcher)`. The contract
 * we pin here:
 *   - `addObserver` runs on the injected main dispatcher's thread, NOT
 *     on the scope's `Dispatchers.Default` worker.
 *   - `removeObserver` (called from `awaitClose` when the consumer
 *     scope is cancelled) also runs on the main dispatcher.
 *
 * To keep this a pure JVM unit test (no Robolectric, no `Looper`), we
 * inject a real single-threaded executor as the "main" dispatcher and
 * assert the recorded thread by name. The class under test wires its
 * registration onto whatever `mainDispatcher` we provide — that's the
 * production fix's whole point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLifecycleMonitorTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, FAKE_MAIN_THREAD_NAME)
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    @BeforeTest
    fun setUp() {
        // No global Dispatchers.setMain hop — the production code uses
        // the explicitly-injected dispatcher and tests should test that
        // contract, not the global Main delegation.
    }

    @AfterTest
    fun tearDown() {
        mainExecutor.shutdownNow()
        mainExecutor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun addObserver_runsOnInjectedMainDispatcher_evenWhenCollectionScopeIsDefault() {
        val recordingOwner = RecordingLifecycleOwner()
        val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            AndroidLifecycleMonitor(
                scope = collectionScope,
                mainDispatcher = mainDispatcher,
                lifecycleOwnerProvider = { recordingOwner },
            )

            // Wait for the eager stateIn to start the upstream and for
            // the upstream's flowOn dispatch to land on the
            // single-threaded fake-main.
            assertTrue(
                recordingOwner.observerRegistered.await(5, TimeUnit.SECONDS),
                "addObserver was never invoked within 5s",
            )
            assertEquals(1, recordingOwner.addObserverCalls.size)

            val recorded = recordingOwner.addObserverCalls.single()
            assertTrue(
                // Kotlin coroutines may append "@coroutineN" to the
                // thread name when DEBUG_PROPERTY_NAME is set; prefix
                // match is the robust assertion.
                recorded.threadName.startsWith(FAKE_MAIN_THREAD_NAME),
                "addObserver must run on the injected mainDispatcher thread (was: ${recorded.threadName}).",
            )
        } finally {
            collectionScope.cancel()
        }
    }

    @Test
    fun removeObserver_runsOnInjectedMainDispatcher_onCollectionCancellation() {
        val recordingOwner = RecordingLifecycleOwner()
        val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        AndroidLifecycleMonitor(
            scope = collectionScope,
            mainDispatcher = mainDispatcher,
            lifecycleOwnerProvider = { recordingOwner },
        )
        assertTrue(
            recordingOwner.observerRegistered.await(5, TimeUnit.SECONDS),
            "addObserver was never invoked within 5s",
        )

        // Cancelling the upstream collection triggers awaitClose →
        // removeObserver. The cleanup must hop back to the main
        // dispatcher.
        collectionScope.cancel()

        assertTrue(
            recordingOwner.observerRemoved.await(5, TimeUnit.SECONDS),
            "removeObserver was never invoked within 5s of scope cancel",
        )
        assertEquals(1, recordingOwner.removeObserverCalls.size)
        val recorded = recordingOwner.removeObserverCalls.single()
        assertTrue(
            recorded.threadName.startsWith(FAKE_MAIN_THREAD_NAME),
            "removeObserver must run on the injected mainDispatcher thread (was: ${recorded.threadName}).",
        )
    }

    private fun assertTrue(condition: Boolean, lazyMessage: String) {
        if (!condition) fail(lazyMessage)
    }

    private companion object {
        const val FAKE_MAIN_THREAD_NAME = "lifecycle-monitor-test-fake-main"
    }
}

private data class LifecycleCall(val threadName: String)

private class RecordingLifecycleOwner : LifecycleOwner {

    val addObserverCalls = java.util.Collections.synchronizedList(mutableListOf<LifecycleCall>())
    val removeObserverCalls = java.util.Collections.synchronizedList(mutableListOf<LifecycleCall>())
    val observerRegistered = CountDownLatch(1)
    val observerRemoved = CountDownLatch(1)

    private val recordingLifecycle = object : Lifecycle() {
        override val currentState: State = State.STARTED

        override fun addObserver(observer: LifecycleObserver) {
            addObserverCalls += LifecycleCall(Thread.currentThread().name)
            observerRegistered.countDown()
        }

        override fun removeObserver(observer: LifecycleObserver) {
            removeObserverCalls += LifecycleCall(Thread.currentThread().name)
            observerRemoved.countDown()
        }
    }

    override val lifecycle: Lifecycle
        get() = recordingLifecycle
}
