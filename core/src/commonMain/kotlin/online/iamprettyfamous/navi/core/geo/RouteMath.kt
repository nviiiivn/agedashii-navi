package online.iamprettyfamous.navi.core.geo

import online.iamprettyfamous.navi.core.toRadians
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** One turn-by-turn instruction. washi routing.py:990. */
data class Maneuver(
    val instruction: String,
    val street: String,
    val distanceM: Double,
    val at: LatLon,
    /** The polyline point it happens at. */
    val index: Int,
    /** Distance from the start of the route to it. */
    val cumM: Double,
    /**
     * The clip key, guidance.ManeuverKind's name for it (from Valhalla's
     * maneuver type), or null where none of her clips fits - then it is spoken.
     */
    val kind: String? = null,
)

/**
 * A route, or the reason there isn't one. [source] is load-bearing: "offline"
 * means the phone's own graph, "server" means it came over the network, and the
 * screen always says which.
 */
data class RouteLine(
    val ok: Boolean = false,
    val source: String = "offline",
    val sourceDetail: String = source,
    val reason: String = "",
    val points: List<LatLon> = emptyList(),
    val cumM: List<Double> = emptyList(),
    val maneuvers: List<Maneuver> = emptyList(),
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
)

/**
 * Where a fix is along a route. [index] names the vertex being ridden towards,
 * [alongM] is the distance from the start, and [offRouteM] is how far the fix is
 * from the line - the honest number, which no amount of snapping should hide.
 */
data class Progress(val index: Int?, val alongM: Double, val offRouteM: Double?)

data class NextManeuver(val maneuver: Maneuver?, val metres: Double?, val offRouteM: Double?) {
    companion object {
        val NONE = NextManeuver(null, null, null)
    }
}

data class Remaining(val metres: Double?, val seconds: Double?)

/**
 * The live half of routing: where a fix is, what comes next, what is left.
 *
 * Ported from washi routing.py:1247-1351 - the SEGMENT-projection version.
 *
 * Measured to the line, not to its corners. This used to scan the vertices and
 * return the distance to the nearest one. Route points are raw OSM shape points,
 * never densified, and on a straight road they sit 183-449 m apart - measured on
 * her real Oakland-to-Chabot routes. A rider exactly on the line halfway between
 * two of them read as up to 224 m "off route" against a 45 m threshold, so
 * off-route latched, every prompt was suppressed, a reroute fired and cleared
 * it, and the cycle repeated: 17-19 false reroutes per ride in simulation. Her
 * words: "it worked for several turns then just stopped ... it didn't look like
 * it was frozen". Projecting onto each segment also makes [Progress.alongM]
 * continuous, which fixes "in X ft" and when the 60/20/6 s prompts fire.
 */
object RouteMath {
    /** Metres per degree of latitude, for the flat local frame. */
    const val M_PER_DEG = 111_320.0

    fun progress(route: RouteLine, position: LatLon): Progress = progress(route.points, route.cumM, position)

    fun progress(points: List<LatLon>, cumM: List<Double>, position: LatLon): Progress {
        if (points.isEmpty()) return Progress(null, 0.0, null)
        if (points.size == 1) return Progress(0, cumM.firstOrNull() ?: 0.0, Geo.haversineM(position, points[0]))

        // Flat local metres about the fix: good to centimetres at route scale,
        // and it keeps the scan in cheap arithmetic.
        val scale = cos(toRadians(position.lat))
        fun x(q: LatLon) = (q.lon - position.lon) * scale * M_PER_DEG
        fun y(q: LatLon) = (q.lat - position.lat) * M_PER_DEG

        var bestI = 0
        var bestD2 = Double.POSITIVE_INFINITY
        var bestT = 0.0
        var ax = x(points[0])
        var ay = y(points[0])
        for (i in 0 until points.size - 1) {
            val bx = x(points[i + 1])
            val by = y(points[i + 1])
            val vx = bx - ax
            val vy = by - ay
            val seg2 = vx * vx + vy * vy
            // The fraction along this segment of the closest approach, clamped so
            // a fix past either end measures to that end rather than to an
            // imaginary extension of the road. A duplicated point has no length.
            val t = if (seg2 <= 0.0) 0.0 else (-(ax * vx + ay * vy) / seg2).coerceIn(0.0, 1.0)
            val px = ax + vx * t
            val py = ay + vy * t
            val d2 = px * px + py * py
            if (d2 < bestD2) {
                bestD2 = d2
                bestI = i
                bestT = t
            }
            ax = bx
            ay = by
        }

        val off = sqrt(bestD2)
        // Interpolated across the winning segment, so it advances with the bike.
        val start = if (bestI < cumM.size) cumM[bestI] else 0.0
        val end = if (bestI + 1 < cumM.size) cumM[bestI + 1] else start
        val along = start + (end - start) * bestT
        // Still a vertex, the one being ridden towards, so a maneuver lookup does
        // not point back at a corner already taken.
        val index = if (bestT > 0.5 && bestI + 1 < points.size) bestI + 1 else bestI
        return Progress(index, along, off)
    }

    /**
     * The next maneuver, the metres to it, and the off-route distance. The
     * "head ..." instruction is never next - it describes what she is already
     * doing - so this starts at the first real turn and ends on "arrive".
     * Nothing at all for a route with fewer than two maneuvers; off-route for
     * guidance must therefore come from [progress], never from here.
     */
    fun nextManeuver(route: RouteLine, position: LatLon): NextManeuver {
        if (!route.ok || route.maneuvers.size < 2) return NextManeuver.NONE
        return nextManeuver(route, progress(route, position))
    }

    fun nextManeuver(route: RouteLine, progress: Progress): NextManeuver {
        if (!route.ok || route.maneuvers.size < 2) return NextManeuver.NONE
        val along = progress.alongM
        for (m in route.maneuvers.drop(1)) {
            if (m.cumM >= along) return NextManeuver(m, max(0.0, m.cumM - along), progress.offRouteM)
        }
        val last = route.maneuvers.last()
        return NextManeuver(last, max(0.0, last.cumM - along), progress.offRouteM)
    }

    /** (metres, seconds) still to go, the time scaled from the route's own average. */
    fun remaining(route: RouteLine, position: LatLon): Remaining {
        if (!route.ok || route.cumM.isEmpty()) return Remaining(null, null)
        return remaining(route, progress(route, position))
    }

    fun remaining(route: RouteLine, progress: Progress): Remaining {
        if (!route.ok || route.cumM.isEmpty()) return Remaining(null, null)
        val left = max(0.0, route.distanceM - progress.alongM)
        if (route.distanceM <= 0.0) return Remaining(left, 0.0)
        return Remaining(left, route.durationS * (left / route.distanceM))
    }
}
