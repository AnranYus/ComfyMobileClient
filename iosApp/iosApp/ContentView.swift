import SwiftUI
import Foundation
import UIKit
import shared

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
            .onDrop(of: ["public.json", "public.png", "public.image"], isTargeted: nil) { providers in
                enqueueFirstImportProvider(providers)
            }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

private func enqueueFirstImportProvider(_ providers: [NSItemProvider]) -> Bool {
    let acceptedTypes = ["public.json", "public.png", "public.image"]
    guard let provider = providers.first else { return false }
    guard let type = acceptedTypes.first(where: { provider.hasItemConformingToTypeIdentifier($0) }) else {
        return false
    }
    provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
        guard let url else { return }
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.copyItem(at: url, to: destination)
            IosWorkflowImportInboxKt.enqueueIosWorkflowImportUrl(url: destination)
        } catch {
            // The shared import UI will surface "no shared payload" if
            // the copy fails and the user tries to import the drop.
        }
    }
    return true
}
