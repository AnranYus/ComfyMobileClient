package com.comfymobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.comfymobile.App

/**
 * Single-Activity host. Compose tree lives in
 * [com.comfymobile.App]; process-level lifecycle (Koin start, state
 * machine, monitor bootstrap, descriptor registry) is in
 * [ComfyMobileApplication.onCreate] so that rotation does NOT
 * cancel the state machine (per @Lily PR #18 thread `62385887`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
