import SwiftUI
import shared

@main
struct ComfyMobileClientApp: App {

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
