package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.geo.LatLon
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a GPS fix gives on its own, with time. Spec section 07.
 *
 * Ported from apps/navi/agedashii_navi/telemetry.py (the module functions) and
 * sensors.py:118-128. A fix carries speed and heading computed by the receiver
 * from Doppler shift, not by differencing positions, so both are good at low
 * speed and good immediately. From those two:
 *
 *     longitudinal g   dv/dt / g
 *     yaw rate         d(heading)/dt, the shortest signed angle
 *     lateral g        v * yaw rate / g
 *     lean angle       atan(lateral g)
 *     grade            d(altitude) / distance
 *
 * Lean is the interesting one. A bike in a steady corner leans until the
 * resultant of gravity and centripetal acceleration passes through the tyre
 * contact patch, so atan(v * omega / g) is not an approximation of lean - it IS
 * lean, for any bike, with no calibration and no mount orientation to get wrong.
 * It cannot see lean while stopped or counter-steer transients, and it is noise
 * below walking pace, so under [MIN_LEAN_SPEED_MS] it reports nothing.
 */
object Physics {
    /** Standard gravity, for turning an acceleration into a g. */
    const val G = 9.80665

    /** Below this (about 11 mph) GPS heading is mostly noise and a lean from it a random number. */
    const val MIN_LEAN_SPEED_MS = 5.0

    /** The ride log's earth radius, telemetry.py:137. The router's is [online.iamprettyfamous.navi.core.geo.Geo.EARTH_R]. */
    const val EARTH_R = 6371000.0

    /** Standard sea-level pressure, for the barometric formula. */
    const val SEA_LEVEL_HPA = 1013.25

    /** Metres between two points. h is clamped at 1, so antipodes are pi*R and not NaN. */
    fun haversineM(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val sinLat = sin((lat2 - lat1) / 2)
        val sinLon = sin((lon2 - lon1) / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_R * asin(sqrt(min(1.0, h)))
    }

    /**
     * Signed degrees from heading [a] to heading [b], in (-180, 180]. Wrapped,
     * because 359 to 1 is a two-degree right turn and not a 358-degree left one;
     * getting this wrong puts 300 deg/s of yaw in the log every time the bike
     * points north. A full reversal is +180, never -180.
     */
    fun headingDelta(a: Double?, b: Double?): Double? {
        if (a == null || b == null) return null
        val d = (b - a + 180.0).mod(360.0) - 180.0
        return if (d == -180.0) 180.0 else d
    }

    /** Lean in degrees from speed and yaw rate, or null below [MIN_LEAN_SPEED_MS]. */
    fun leanFrom(speedMs: Double?, yawDps: Double?): Double? {
        if (speedMs == null || yawDps == null) return null
        if (speedMs < MIN_LEAN_SPEED_MS) return null
        val aLat = speedMs * Math.toRadians(yawDps)
        return Math.toDegrees(atan(aLat / G))
    }

    /** Lateral acceleration in g: v * omega / g, omega in radians per second. */
    fun latG(speedMs: Double?, yawDps: Double?): Double? {
        if (speedMs == null || yawDps == null) return null
        return speedMs * Math.toRadians(yawDps) / G
    }

    /** (now - before) / dt / scale, or null without both readings and a nonzero interval. */
    fun rate(now: Double?, before: Double?, dt: Double, scale: Double = 1.0): Double? {
        if (now == null || before == null || dt == 0.0) return null
        return (now - before) / dt / scale
    }

    /** Degrees per second between two headings, through [headingDelta]. */
    fun rateDeg(before: Double?, now: Double?, dt: Double): Double? {
        val d = headingDelta(before, now)
        if (d == null || dt == 0.0) return null
        return d / dt
    }

    /** Rise over run, in percent. Null over no distance - under 2 m a stopped bike has not moved. */
    fun grade(beforeAltM: Double?, nowAltM: Double?, runM: Double): Double? {
        if (beforeAltM == null || nowAltM == null || runM == 0.0) return null
        return (nowAltM - beforeAltM) / runM * 100.0
    }

    /**
     * Metres above sea level from pressure, or null: 44330 * (1 - (p / p0)^(1/5.255)).
     * Absolute accuracy depends on p0 being today's real sea-level pressure,
     * which nothing here has - so altitude is also reported relative to the
     * first reading, and for climbing or descending relative is all that matters.
     */
    fun altitudeFromPressure(hpa: Double?, seaLevelHpa: Double = SEA_LEVEL_HPA): Double? {
        if (hpa == null || hpa <= 0.0) return null
        return 44330.0 * (1.0 - (hpa / seaLevelHpa).pow(1.0 / 5.255))
    }
}
