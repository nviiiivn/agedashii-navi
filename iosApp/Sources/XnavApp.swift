import SwiftUI
import XnavKit

/**
 The whole iPhone app is the Kotlin side's Ride screen (MainViewController.kt);
 this only puts it on screen and hands it what only Swift can reach: MapLibre's
 map, Valhalla's router, and the files she opens in it.
 */
@main
struct XnavApp: App {
    var body: some Scene {
        WindowGroup {
            KotlinRide()
                // The Compose chrome keeps itself clear of the notch and the
                // home bar; insetting it here as well would leave dead bands
                // the map does not reach. The status bar stays.
                .ignoresSafeArea()
                // A .navipack opened from Files or a Safari download.
                .onOpenURL { FileHostImpl.shared.open($0) }
        }
    }
}

private struct KotlinRide: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(map: MapHostImpl(), router: RouterHostImpl(), files: FileHostImpl.shared)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
