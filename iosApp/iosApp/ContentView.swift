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
            stagingUrl: vars["BILETFLOW_STAGING_URL"] ?? "",
            webHost: vars["BILETFLOW_WEB_HOST"] ?? "")
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            // Universal Links (https://<web host>/events/...) and a cold start by link both arrive here.
            .onOpenURL { url in
                MainViewControllerKt.deliverIncomingLink(url: url.absoluteString)
            }
    }
}
