package online.iamprettyfamous.navi.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Distances and bearings on the earth, as the router measures them.
 *
 * Ported from washi routing.py:894-900 (`haversine_m`, `cumulative_m`) and
 * guidance.py:386-393 (`bearing`). The router's earth radius is the mean radius,
 * 6371008.8 m; the ride log's own haversine in telemetry uses 6371000.0, and the
 * two are kept apart on purpose so each matches the numbers its Python wrote.
 */
object Geo {
    /** Mean earth radius, metres. routing.py:141. */
    const val EARTH_R = 6371008.8

    fun haversineM(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val sinLat = sin((lat2 - lat1) / 2)
        val sinLon = sin((lon2 - lon1) / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_R * asin(sqrt(h))
    }

    /**
     * Distance from the start of a polyline to each of its points. The router
     * scales these to its stored edge lengths when it has them; that belongs to
     * the router, and a plain polyline needs only this.
     */
    fun cumulativeM(points: List<LatLon>): List<Double> {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
        return cum.toList()
    }

    /** Compass degrees from [a] to [b], for orienting the map. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
