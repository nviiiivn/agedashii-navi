package online.iamprettyfamous.navi.core.guidance

import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.Maneuver
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.geo.RouteMath
import java.time.ZoneId

/**
 * Everything the HUD draws after a fix, decided in one place.
 * guidance.py:298-314. [say] is the one prompt due now, or null.
 */
data class Status(
    val ok: Boolean,
    val reason: String = "",
    val maneuver: Maneuver? = null,
    val instruction: String = "",
    val street: String = "",
    val icon: String = "",
    val turnInM: Double? = null,
    val offRouteM: Double? = null,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    val leftM: Double? = null,
    val leftS: Double? = null,
    val eta: String = "",
    val source: String = "",
    val say: String? = null,
)

/**
 * Live state for following one route. Feed it fixes, ask it questions.
 *
 * Ported from guidance.py:226-383. Holds no view, so a test rides a whole route
 * through it with a list of coordinates.
 *
 * Off-route needs [Guidance.CONFIRM_FIXES] consecutive fixes beyond the
 * threshold, and coming back needs the distance to fall to
 * [Guidance.RETURN_FRACTION] of it - the same reason a thermostat has a
 * deadband. Each prompt for each maneuver fires once, tracked by (index, band).
 */
class Follower(
    route: RouteLine? = null,
    var offrouteM: Double = Guidance.OFFROUTE_M,
    var imperial: Boolean = true,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    var route: RouteLine? = route
        private set
    var offRoute: Boolean = false
        private set
    var offSince: Double? = null
        private set
    var arrived: Boolean = false
        private set
    var reroutes: Int = 0
        private set
    var last: Status? = null
        private set

    private var badRun = 0
    private val said = HashSet<Pair<Int, String>>()

    /**
     * Follow a new route, forgetting everything about the old - including what
     * was said, or a re-routed rider is never told about the turn she is now
     * approaching because a maneuver with the same index was already announced
     * on the route that got replaced.
     */
    fun setRoute(route: RouteLine?): RouteLine? {
        this.route = route
        offRoute = false
        offSince = null
        arrived = false
        badRun = 0
        said.clear()
        last = null
        return route
    }

    fun noteReroute() {
        reroutes += 1
    }

    /** Take a fix at time [now] (unix seconds). Returns the status; also decides what to say. */
    fun update(position: LatLon, speedMs: Double? = null, now: Double): Status {
        val route = this.route
        if (route == null || !route.ok) {
            return Status(ok = false, reason = "no route").also { last = it }
        }
        val progress = RouteMath.progress(route, position)
        val next = RouteMath.nextManeuver(route, progress)
        // Off-route comes from progress, NOT from the next maneuver: that is
        // null for a route with fewer than two maneuvers - a straight run with
        // no turns - and taking it from there meant such a route could never
        // report being off at all.
        val left = RouteMath.remaining(route, progress)
        trackOffRoute(progress.offRouteM, now)

        // Arrival is distance to the end of the line, not to the last maneuver.
        if (left.metres != null && left.metres < Guidance.ARRIVE_M) arrived = true

        val man = next.maneuver
        val say = if (!offRoute && man != null && !arrived) maybeSay(man, next.metres, speedMs) else null
        val instruction = man?.instruction ?: ""
        return Status(
            ok = true,
            reason = "",
            maneuver = man,
            instruction = instruction,
            street = man?.street ?: "",
            icon = Guidance.turnIcon(instruction),
            turnInM = next.metres,
            offRouteM = progress.offRouteM,
            offRoute = offRoute,
            arrived = arrived,
            leftM = left.metres,
            leftS = left.seconds,
            eta = Guidance.etaText(left.seconds, now, zone),
            source = route.source,
            say = say,
        ).also { last = it }
    }

    fun status(): Status? = last

    /**
     * Whether to ask for a new route now: only once off-route is confirmed, and
     * never after arrival - parking twenty metres from the pin is not lost.
     * Rate-limiting is [RerouteGate]'s job.
     */
    fun shouldReroute(): Boolean = offRoute && !arrived

    private fun trackOffRoute(off: Double?, now: Double) {
        if (off == null) return
        if (!offRoute) {
            if (off > offrouteM) {
                badRun += 1
                if (badRun >= Guidance.CONFIRM_FIXES) {
                    offRoute = true
                    offSince = now
                }
            } else {
                badRun = 0
            }
            return
        }
        // Already off. Coming back has to clear the deadband.
        if (off <= offrouteM * Guidance.RETURN_FRACTION) {
            offRoute = false
            offSince = null
            badRun = 0
        }
    }

    /**
     * The one prompt due for this maneuver, or null.
     *
     * Walks the bands NEAREST first and fires the first that is due and unsaid.
     * Inside "now" every wider band is also technically due, so walking
     * outwards-in would announce "in a quarter mile, turn left" at the corner
     * itself - a real bug once. Firing a band also marks every wider one said,
     * so a band crossed without being announced cannot fire later.
     */
    private fun maybeSay(man: Maneuver, metres: Double?, speedMs: Double?): String? {
        if (metres == null) return null
        val index = man.index
        val points = Guidance.promptPoints(speedMs)
        for ((_, band) in Guidance.PROMPT_BANDS.asReversed()) {
            if (metres > (points[band] ?: 0.0)) continue
            // Announced already, and every nearer band was ruled out above.
            if ((index to band) in said) return null
            for ((_, other) in Guidance.PROMPT_BANDS) {
                said.add(index to other)
                if (other == band) break
            }
            return Guidance.spoken(man.instruction, man.street, metres, imperial, band)
        }
        return null
    }
}
