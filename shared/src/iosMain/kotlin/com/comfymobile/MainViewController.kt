package com.comfymobile

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point exposed to SwiftUI / UIKit for hosting the shared
 * Compose UI inside iOS. The actual `iosApp.xcodeproj` is generated
 * in T1.0b on a macOS GitHub Actions runner.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
