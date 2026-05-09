package com.comfymobile

/**
 * Phase 1.0 placeholder. Returns the version string the Hello-Compose
 * splash shows. Replaced by real screens once T1.1..T1.4 land.
 */
class Greeting {
    private val version: String = "v0.0.1"
    private val phase: String = "Phase 1.0 — KMP bootstrap"

    fun greet(): String = "ComfyMobileClient $version\n$phase"
}
