package com.comfymobile.data.connect

/**
 * Test seam for the `/system_stats` liveness + signature probe used by
 * [ConnectAttemptCoordinator].
 *
 * Production binding (DI) calls
 * `ComfyHttpClient(baseUrl).getSystemStats()`. Tests pass a fake
 * suspending lambda that runs entirely on the `runTest` scheduler so
 * coordinator-level tests can synchronise with `runCurrent()` /
 * `advanceTimeBy()` instead of needing virtual-time `withTimeout`
 * around Ktor `MockEngine` (whose dispatcher is opaque to the test
 * scheduler).
 *
 * Per @Lily PR #18 review (comment `4413882022`): coordinator-level
 * tests must be fully test-scheduler controlled; Ktor's MockEngine
 * does its work on a dispatcher whose progress is invisible to
 * `runTest`'s virtual time, which made the previous tests flaky no
 * matter which side-effect they awaited.
 *
 * ### Contract
 *
 * - Returns normally → probe succeeded; the server speaks ComfyUI.
 * - Throws [com.comfymobile.data.network.ComfyHttpException] →
 *   classified failure (HTTP non-2xx, malformed body, missing field).
 *   The coordinator maps this to a [com.comfymobile.data.network.ConnectError]
 *   via [com.comfymobile.data.network.ConnectErrorClassifier].
 * - Throws any other [Throwable] → transport-level failure (network
 *   down, connection refused, TLS failure, …); classified as
 *   `NetworkFailure`.
 * - Throws [kotlinx.coroutines.CancellationException] → coordinator
 *   rethrows; structured concurrency must propagate so a cancelled VM
 *   scope reaches the in-flight probe.
 *
 * Ktor-layer behaviour (status code classification, body parsing, the
 * specific shape of `ComfyHttpException` variants) stays covered by
 * the dedicated `ComfyHttpClientTest`.
 */
fun interface SystemStatsProbe {
    suspend operator fun invoke(baseUrl: String)
}
