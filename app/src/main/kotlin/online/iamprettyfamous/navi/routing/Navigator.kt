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
import online.iamprettyfamous.navi.core.voice.VoiceLines
import online.iamprettyfamous.navi.voice.Voice

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
    // Her last speed, so a new route's first update picks the same prompt band
    // the next fix will. Without it the first update used the crawl bands and
    // the next fix the speed bands, and the same "In 600 ft, turn right" was
    // said twice, 7 s apart, after every route and re-route (emulator ride).
    private var speedMs: Double? = null
    private var imperial = true
    // Bumped by every new destination and by clear(), so an answer that
    // arrives for a destination she has since replaced is dropped.
    private var generation = 0
    // Re-routes to THIS destination; the Follower's own count spans them all.
    private var reroutes = 0
    // Her situation lines (start, off route, re-routing), each on its edge. The
    // token is new with every destination she picks, even the same place again,
    // so a new start is a start and a re-route is not. It moves to [destination]
    // only when that destination's route is UP: set on the pick, the old route's
    // next fix said "start" - and a place that could not be routed at all got
    // "start" too (Napa, emulator-5556, ship candidate 99b8285).
    private val cues = VoiceLines.Cues()
    private var destination: Any? = null
    private var asked: Any? = null

    /** Route from where she is to [dest]. With no fix yet, the route starts at the first one. */
    fun go(dest: LatLon, imperial: Boolean) {
        synchronized(lock) {
            this.imperial = imperial
            follower.imperial = imperial
            generation++
            asked = Any()
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
            destination = null
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
            speedMs = speed
            val status = follower.update(p, speed, now)
            val dest = nav.dest
            var rerouting = false
            if (dest != null && follower.shouldReroute() && gate.shouldAsk(true, now)) {
                gate.begin(now)
                ask(p, dest, generation, first = false)
                rerouting = true
            }
            _nav.value = _nav.value.copy(status = status)
            // The Follower decides what is due; each prompt comes once - in her
            // voice where she has one, after any situation line of this fix.
            val said = cues.update(status.ok, status.offRoute, destination, rerouting, over = false, now = now)
            Voice.prompt(status, imperial, said)
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
                // Said once per off-route spell; the gate's retries stay quiet.
                if (!first && cues.refused()) Voice.play(VoiceLines.refusal(line.reason), "refused: ${line.reason}")
                return
            }
            follower.setRoute(line)
            if (first) destination = asked
            if (first) reroutes = 0 else {
                reroutes++
                follower.noteReroute()
            }
            // Measured against the last point of the SHAPE: Valhalla echoes the
            // asked-for location back even when the route ends elsewhere.
            val endStreet = line.maneuvers.lastOrNull { it.street.isNotBlank() }?.street
            val gap = Arrival.gap(dest, line.points.last(), endStreet)
            val status = here?.let { follower.update(it, speedMs, now) }
            Log.i(TAG, "following: ${line.maneuvers.size} turns, reroutes=$reroutes, gap=${gap?.metres?.toInt()} m")
            _nav.value = Nav(dest = dest, route = line, status = status, gap = gap, reroutes = reroutes)
            // The first update of a new route can already be inside a prompt band;
            // the Follower counts it said, so it must be said here - after "start".
            status?.let { Voice.prompt(it, imperial, cues.update(it.ok, it.offRoute, destination, false, over = false, now = now)) }
        }
    }
}
