import SwiftUI
import Foundation
import UIKit
import shared

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey : Any] = [:]
    ) -> Bool {
        IosWorkflowImportInboxKt.enqueueIosWorkflowImportUrl(url: url)
        return true
    }
}

@main
struct ComfyMobileClientApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    /// Process-lifetime bootstrap mirror of
    /// `ComfyMobileApplication.onCreate` on Android: starts Koin,
    /// the connection state machine, the platform-monitor bootstrap,
    /// and async-loads the node descriptor registry. Per @Lily
    /// PR #18 thread (`62385887`), this MUST run once for the process
    /// — running it from SwiftUI's `init` keeps it bound to the
    /// process lifetime, not a SwiftUI scene.
    init() {
        IosBootstrapKt.bootKoinIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
