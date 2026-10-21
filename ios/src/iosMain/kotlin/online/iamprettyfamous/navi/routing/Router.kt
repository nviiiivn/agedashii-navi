package online.iamprettyfamous.navi.routing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.nowSeconds
import online.iamprettyfamous.navi.core.routing.RouteRequest
import online.iamprettyfamous.navi.core.routing.TileCheck
import online.iamprettyfamous.navi.core.routing.TileVersionGuard
import online.iamprettyfamous.navi.core.routing.ValhallaRoute
import online.iamprettyfamous.navi.data.DataFiles
import online.iamprettyfamous.navi.host.RouterHost
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/**
 * Offline routing: Valhalla on the phone (through the Swift app's RouterHost),
 * reading one tile archive from the bundle. Every call runs on its own serial
 * "router" queue - loading the archive and routing both take real time - and
 * answers come back on the main thread, where Navigator lives.
 *
 * The archive is checked before Valhalla ever sees it: TileVersionGuard refuses
 * tiles built by any Valhalla but the one the binding carries, because
 * Valhalla itself only warns and then routes on misread data.
 */
object Router {
    /** Whether routing works, and if not, why - for the screen. */
    data class State(val ready: Boolean = false, val why: String = "not loaded", val lastMs: Long? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // Serial: a null attribute is DISPATCH_QUEUE_SERIAL.
    private val queue = dispatch_queue_create("router", null)
    private var host: RouterHost? = null
    private var open = false

    /** Load the archive (once). Safe to call repeatedly. */
    fun load(host: RouterHost) {
        this.host = host
        dispatch_async(queue) {
            if (open) return@dispatch_async
            val tar = DataFiles.router
            if (tar == null) {
                _state.value = State(why = "this build carries no routing tiles (${DataFiles.ROUTER})")
                return@dispatch_async
            }
            when (val check = TileVersionGuard.check(tar)) {
                is TileCheck.Refused -> {
                    _state.value = State(why = check.reason)
                    return@dispatch_async
                }
                is TileCheck.Ok -> println("[router] tiles ok: ${check.tiles} tiles, Valhalla ${check.version}")
            }
            val why = host.open(tar)
            if (why == null) {
                open = true
                _state.value = State(ready = true, why = "")
            } else {
                _state.value = State(why = "the router would not start: $why")
            }
        }
    }

    /** Route from [from] to [to]; [done] runs on the main thread with the route or the reason there is none. */
    fun route(from: LatLon, to: LatLon, imperial: Boolean, heading: Double? = null, done: (RouteLine) -> Unit) {
        dispatch_async(queue) {
            val h = host
            val line = if (h == null || !open) {
                RouteLine(ok = false, reason = _state.value.why)
            } else {
                val t0 = nowSeconds()
                val answer = h.route(RouteRequest.motorcycle(from, to, imperial, heading))
                val line = if (answer.startsWith(RouterHost.ERROR)) {
                    // Valhalla's own text ("No path could be found for input")
                    // reached her screen once on Android; say it plainly.
                    val why = answer.removePrefix(RouterHost.ERROR).trim()
                    RouteLine(ok = false, reason = if ("No path could be found" in why) "no road on this phone's map reaches that point" else "the router failed: $why")
                } else {
                    runCatching { ValhallaRoute.parse(answer) }.getOrElse { RouteLine(ok = false, reason = "the router's answer was unreadable: ${it.message}") }
                }
                val ms = ((nowSeconds() - t0) * 1000).toLong()
                _state.value = _state.value.copy(lastMs = ms)
                println("[router] route ok=${line.ok} ${line.maneuvers.size} turns, ${line.points.size} points, $ms ms ${line.reason}")
                line
            }
            dispatch_async(dispatch_get_main_queue()) { done(line) }
        }
    }
}
