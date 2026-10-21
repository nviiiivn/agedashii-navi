package online.iamprettyfamous.navi

import androidx.compose.ui.window.ComposeUIViewController
import online.iamprettyfamous.navi.host.MapHost
import online.iamprettyfamous.navi.host.RouterHost
import platform.UIKit.UIViewController

/**
 * The whole app, for the Swift side to put on screen: it hands over the map and
 * the router (Hosts.kt), and gets back the Ride screen. Swift calls this once,
 * as `MainViewControllerKt.MainViewController(map:router:)`.
 */
fun MainViewController(map: MapHost, router: RouterHost): UIViewController {
    val screen = RideScreen(map, router)
    screen.start()
    return ComposeUIViewController { screen.Ride() }
}
