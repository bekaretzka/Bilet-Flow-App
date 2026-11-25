import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        TicketCameraController.installBridge()
        let vars = ProcessInfo.processInfo.environment
        #if DEBUG
        let defaultEnvironment = "mock"
        #else
        let defaultEnvironment = "staging"
        #endif
        return MainViewControllerKt.MainViewController(
            environment: vars["BILETFLOW_ENV"] ?? defaultEnvironment,
            localUrl: vars["BILETFLOW_LOCAL_URL"] ?? "http://127.0.0.1:8000",
            stagingUrl: vars["BILETFLOW_STAGING_URL"] ?? "")
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
