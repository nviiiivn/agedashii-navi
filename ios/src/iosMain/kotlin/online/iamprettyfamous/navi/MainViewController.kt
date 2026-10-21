package online.iamprettyfamous.navi

import androidx.compose.ui.window.ComposeUIViewController
import online.iamprettyfamous.navi.host.FileHost
import online.iamprettyfamous.navi.host.MapHost
import online.iamprettyfamous.navi.host.RouterHost
import platform.UIKit.UIViewController

/**
 * The whole app, for the Swift side to put on screen: it hands over the map and
 * the router and its files (Hosts.kt), and gets back the Ride screen. Swift
 * calls this once, as `MainViewControllerKt.MainViewController(map:router:files:)`.
 */
fun MainViewController(map: MapHost, router: RouterHost, files: FileHost): UIViewController {
    val screen = RideScreen(map, router, files)
    current = screen
    screen.start()
    return ComposeUIViewController { screen.Ride() }
}

private var current: RideScreen? = null

/**
 * A map pack for the running app: the one she picked in Settings, or a
 * .navipack opened with "Open in xnav.k". Swift calls this as
 * `MainViewControllerKt.OpenPack(path:)` and keeps the file open until
 * FileHost.releasePack.
 */
fun OpenPack(path: String) {
    current?.importPack(path) ?: println("[pack] no screen yet for $path")
}
