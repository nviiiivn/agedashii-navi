package online.iamprettyfamous.navi.routing

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.guidance.Follower
import online.iamprettyfamous.navi.core.guidance.Guidance
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

    /** Her "Off route after" choice, in metres. */
    fun setOffRouteM(metres: Int) {
        synchronized(lock) { follower.offrouteM = metres.toDouble() }
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
            // Her speed is kept even with no route yet, so the FIRST route of a
            // ride, picked while moving, starts on the speed bands too.
            val speed = if (fix.hasSpeed()) fix.speed.toDouble() else null
            speedMs = speed
            heading = headingOf(fix, speed, previous, p)
            previous = p
            if (follower.route == null) return
            val now = System.currentTimeMillis() / 1000.0
            val status = follower.update(p, speed, now)
            val dest = nav.dest
            var rerouting = false
            if (dest != null && follower.shouldReroute() && gate.shouldAsk(true, now)) {
                gate.begin(now)
                ask(p, dest, generation, first = false)
                rerouting = true
            }
            _nav.value = _nav.value.copy(status = status)
            debugDir?.let {
                Log.d(TAG, "fix %.6f,%.6f v=%s next='%s' kind=%s in=%s off=%s offRoute=%s arrived=%s left=%s band=%s say=%s".format(
                    p.lat, p.lon, speed?.let { "%.1f".format(it) }, status.instruction, status.kind,
                    status.turnInM?.let { "%.0f".format(it) }, status.offRouteM?.let { "%.0f".format(it) },
                    status.offRoute, status.arrived, status.leftM?.let { "%.0f".format(it) }, status.band, status.say))
            }
            // The Follower decides what is due; each prompt comes once - in her
            // voice where she has one, after any situation line of this fix.
            val said = cues.update(status.ok, status.offRoute, destination, rerouting, over = false, now = now)
            val situation = said.filter { it in HELD_CUES }
            if (rerouting && situation.isNotEmpty()) {
                // Held until the re-route answers: if its first prompt is a
                // "now", the line would be cut 0.3 s in - "what the h-" - so
                // it is dropped and the turn plays alone (AGEDASHII's ruling).
                Voice.prompt(status, imperial, said - situation.toSet())
                hold(situation, status)
            } else {
                Voice.prompt(status, imperial, said)
            }
        }
    }

    /** True when the next maneuver's "now" is under [Guidance.NOW_SOON_S] away, or already here. */
    private fun nowSoon(metres: Double?, speed: Double?): Boolean {
        if (metres == null) return false
        val nowAt = Guidance.promptPoints(speed)["now"] ?: 0.0
        if (metres <= nowAt) return true
        return speed != null && speed > 0.0 && (metres - nowAt) / speed < Guidance.NOW_SOON_S
    }

    /** An off-route/re-routing line waiting on the re-route asked on its fix. */
    private class Held(val cues: List<String>, val status: Status, val token: Int)

    private var held: Held? = null
    private var heldTokens = 0
    private val main = Handler(Looper.getMainLooper())

    private fun hold(cues: List<String>, status: Status) {
        val h = Held(cues, status.copy(say = null), ++heldTokens)
        held = h
        // A router that never answers must not leave her without the line.
        main.postDelayed({ synchronized(lock) { if (held === h) sayHeld() } }, HOLD_MS)
    }

    /** Say the held line now, and forget it. Under [lock]. */
    private fun sayHeld() {
        val h = held ?: return
        held = null
        Voice.prompt(h.status, imperial, h.cues)
    }

    /**
     * Debug builds: where the route in use is written (route.txt, one "lat,lon"
     * per line after a header), and every fix's guidance is logged - so a
     * simulated ride can follow the app's own route and be checked against
     * what the banner and the voice said. Null in a build that is not debuggable.
     */
    @Volatile var debugDir: java.io.File? = null

    private fun writeRoute(line: RouteLine, dest: LatLon, reroutes: Int) {
        val dir = debugDir ?: return
        runCatching {
            dir.mkdirs()
            val tmp = java.io.File(dir, "route.txt.tmp")
            tmp.writeText(buildString {
                append("# dest ${dest.lat},${dest.lon} reroutes $reroutes points ${line.points.size} metres ${line.distanceM.toInt()}\n")
                for (m in line.maneuvers) append("# maneuver ${m.index} ${m.kind} ${m.cumM.toInt()} ${m.instruction}\n")
                for (pt in line.points) append("${pt.lat},${pt.lon}\n")
            })
            tmp.renameTo(java.io.File(dir, "route.txt"))
        }.onFailure { Log.w(TAG, "route.txt not written", it) }
    }

    private fun ask(from: LatLon, dest: LatLon, gen: Int, first: Boolean) {
        val facing = heading
        if (facing != null) Log.i(TAG, "routing from heading %.0f°".format(facing))
        Router.route(from, dest, imperial, facing) { line -> answered(line, dest, gen, first) }
    }

    // The way she is facing, or null when it cannot be trusted.
    private var heading: Double? = null
    private var previous: LatLon? = null

    /**
     * Her heading, only while she is moving: at a standstill a GPS bearing
     * means nothing, and a stale one would route her off a direction she no
     * longer has. The receiver's own bearing when it says it is good to 20°;
     * otherwise the course between this fix and the last, if she covered 8 m.
     */
    private fun headingOf(fix: Location, speed: Double?, prev: LatLon?, p: LatLon): Double? {
        if (speed == null || speed < HEADING_MIN_MS) return null
        if (fix.hasBearing() && fix.hasBearingAccuracy() && fix.bearingAccuracyDegrees <= 20f) {
            return fix.bearing.toDouble()
        }
        if (prev != null && Geo.haversineM(prev, p) >= 8.0) return Geo.bearing(prev, p)
        return null
    }

    /** Below this she is stopped or creeping, and no bearing is trusted (m/s, ~7 mph). */
    private const val HEADING_MIN_MS = 3.0

    /** The situation lines held for the re-route's answer, and the longest they wait for it. */
    private val HELD_CUES = setOf("offroute", "rerouting")
    private const val HOLD_MS = 1500L

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
                // The held line, then - once per spell - why there is no route: one playlist.
                if (!first) {
                    val lead = held?.cues.orEmpty()
                    held = null
                    if (cues.refused()) Voice.refusal(line.reason, lead) else if (lead.isNotEmpty()) Voice.prompt(nav.status ?: Status(ok = true), imperial, lead)
                }
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
            writeRoute(line, dest, reroutes)
            _nav.value = Nav(dest = dest, route = line, status = status, gap = gap, reroutes = reroutes)
            // The first update of a new route can already be inside a prompt band;
            // the Follower counts it said, so it must be said here - after "start".
            // The held off-route line plays only if the new route does not open
            // with a "now": that turn would cut it to a fragment.
            if (!first) {
                // A "now" here, or one due within NOW_SOON_S (the Follower holds a
                // near back for it), would cut the line: minion 3's Flet reading.
                if (status != null && (status.band == "now" || nowSoon(status.turnInM, speedMs))) {
                    if (held != null) Log.i(TAG, "off-route line dropped: the new route starts with a turn here")
                    held = null
                } else {
                    sayHeld()
                }
            }
            status?.let { Voice.prompt(it, imperial, cues.update(it.ok, it.offRoute, destination, false, over = false, now = now)) }
        }
    }
}
