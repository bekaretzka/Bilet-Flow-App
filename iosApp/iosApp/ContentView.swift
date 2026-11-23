import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        TicketCameraController.installBridge()
        #if DEBUG
        return MainViewControllerKt.MainViewController(useMockEvents: true)
        #else
        return MainViewControllerKt.MainViewController(useMockEvents: false)
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
