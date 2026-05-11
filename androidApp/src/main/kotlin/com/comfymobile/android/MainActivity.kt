package com.comfymobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.comfymobile.App
import com.comfymobile.data.importer.AndroidWorkflowImportInbox

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
        AndroidWorkflowImportInbox.enqueue(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidWorkflowImportInbox.enqueue(intent)
    }
}
