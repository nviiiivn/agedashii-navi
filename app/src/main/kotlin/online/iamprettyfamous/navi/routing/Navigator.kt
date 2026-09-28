package online.iamprettyfamous.navi.routing

import android.location.Location
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.guidance.Follower
import online.iamprettyfamous.navi.core.guidance.RerouteGate
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.routing.Arrival
import online.iamprettyfamous.navi.core.routing.ArrivalGap

/**
 * Following one route: the destination, the route to it, and the core's
 * Follower fed every accepted fix. Telemetry calls [onFix] from the ride thread,
 * so guidance keeps running with the screen off; routes come back on the
 * router thread. Everything that touches the Follower holds [lock].
 *
 * A new destination does not drop the route she has until its own route
 * exists, and a failed re-route keeps the old one: a stale route is worse than
 * a fresh one and far better than none (RerouteGate).
 */
object Navigator {
    private const val TAG = "NaviGuide"

    data class Nav(
        /** Where the route in use goes. */
        val dest: LatLon? = null,
        val route: RouteLine? = null,
        val status: Status? = null,
        /** Set when the route ends away from the pin: she is told, never left to find out. */
        val gap: ArrivalGap? = null,
        /** A destination being routed to for the first time, and nothing yet. */
        val asking: LatLon? = null,
        /** Why the last new destination got no route, and which one it was. */
        val failed: String = "",
        val failedFor: LatLon? = null,
        /** The last re-route failed: why, while the old route stays up. */
        val rerouteNote: String = "",
        val reroutes: Int = 0,
    )

    private val _nav = MutableStateFlow(Nav())
    val nav: StateFlow<Nav> = _nav

    private val lock = Any()
    private val follower = Follower()
    private val gate = RerouteGate()
    private var here: LatLon? = null
    private var imperial = true
    // Bumped by every new destination and by clear(), so an answer that
    // arrives for a destination she has since replaced is dropped.
    private var generation = 0
    // Re-routes to THIS destination; the Follower's own count spans them all.
    private var reroutes = 0

    /** Route from where she is to [dest]. With no fix yet, the route starts at the first one. */
    fun go(dest: LatLon, imperial: Boolean) {
        synchronized(lock) {
            this.imperial = imperial
            follower.imperial = imperial
            generation++
            _nav.value = _nav.value.copy(asking = dest, failed = "", failedFor = null)
            here?.let { ask(it, dest, generation, first = true) }
        }
    }

    /** She cancelled a destination still being routed: its answer, when it comes, is dropped. */
    fun forget(dest: LatLon) {
        synchronized(lock) {
            val nav = _nav.value
            if (nav.asking != dest && nav.failedFor != dest) return
            generation++
            _nav.value = nav.copy(asking = null, failed = "", failedFor = null)
        }
    }

    /** Stop navigating: no destination, no route, no banner. */
    fun clear() {
        synchronized(lock) {
            generation++
            follower.setRoute(null)
            _nav.value = Nav()
        }
    }

    /** Ride thread, every accepted fix. */
    fun onFix(fix: Location) {
        synchronized(lock) {
            val p = LatLon(fix.latitude, fix.longitude)
            val first = here == null
            here = p
            val nav = _nav.value
            // A destination chosen before the first fix is routed on it.
            if (first && nav.asking != null) ask(p, nav.asking, generation, first = true)
            if (follower.route == null) return
            val now = System.currentTimeMillis() / 1000.0
            val speed = if (fix.hasSpeed()) fix.speed.toDouble() else null
            val status = follower.update(p, speed, now)
            val dest = nav.dest
            if (dest != null && follower.shouldReroute() && gate.shouldAsk(true, now)) {
                gate.begin(now)
                ask(p, dest, generation, first = false)
            }
            _nav.value = _nav.value.copy(status = status)
        }
    }

    private fun ask(from: LatLon, dest: LatLon, gen: Int, first: Boolean) {
        Router.route(from, dest, imperial) { line -> answered(line, dest, gen, first) }
    }

    private fun answered(line: RouteLine, dest: LatLon, gen: Int, first: Boolean) {
        synchronized(lock) {
            val now = System.currentTimeMillis() / 1000.0
            if (!first) gate.end(now)
            if (gen != generation) return
            val nav = _nav.value
            if (!line.ok) {
                Log.i(TAG, "no route (${if (first) "new" else "re-route"}): ${line.reason}")
                _nav.value = if (first) {
                    nav.copy(asking = null, failed = line.reason.ifEmpty { "no route" }, failedFor = dest)
                } else {
                    nav.copy(rerouteNote = "Re-route failed: ${line.reason.ifEmpty { "no route" }} - still on the old route")
                }
                return
            }
            follower.setRoute(line)
            if (first) reroutes = 0 else {
                reroutes++
                follower.noteReroute()
            }
            // Measured against the last point of the SHAPE: Valhalla echoes the
            // asked-for location back even when the route ends elsewhere.
            val endStreet = line.maneuvers.lastOrNull { it.street.isNotBlank() }?.street
            val gap = Arrival.gap(dest, line.points.last(), endStreet)
            val status = here?.let { follower.update(it, null, now) }
            Log.i(TAG, "following: ${line.maneuvers.size} turns, reroutes=$reroutes, gap=${gap?.metres?.toInt()} m")
            _nav.value = Nav(dest = dest, route = line, status = status, gap = gap, reroutes = reroutes)
        }
    }
}
