package online.iamprettyfamous.navi.core.daylight

import java.time.Instant
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * How bright it is outside, computed rather than measured.
 *
 * Ported from apps/navi/agedashii_navi/daylight.py. Her ask: "the brighter it is
 * the brighter i need it. the dark it is the less it needs to be bright. at night
 * it's even preferable to be all red". The app knows where it is and what time
 * it is, and the sun's position is a solved problem, so the phase of the day is
 * calculated - no sensor, no network, no permission. Better than a light sensor
 * for this anyway: a sensor reads an overpass as night and hunts under trees;
 * the sun's altitude changes over minutes, so the phase is stable by construction.
 *
 *     day      sun above 6 deg    full brightness, the sunlight theme
 *     golden   6 down to 0        bright, glare off the screen
 *     dusk     0 down to -6       civil twilight; eyes adapting
 *     night    below -6           red on black, screen well down
 *
 * Red at night is not a style choice: rods are nearly blind to deep red, so a
 * red screen is readable without spending twenty minutes of dark adaptation.
 * The algorithm is NOAA's solar position calculation, trimmed.
 */
object Daylight {
    /** (name, lowest sun altitude in degrees), highest phase first. */
    val PHASES: List<Pair<String, Double>> = listOf("day" to 6.0, "golden" to 0.0, "dusk" to -6.0, "night" to -90.0)

    /** Screen brightness per phase, 0.0 to 1.0. */
    val BRIGHTNESS: Map<String, Double> = linkedMapOf("day" to 1.0, "golden" to 0.8, "dusk" to 0.5, "night" to 0.22)

    /** The theme each phase asks for. */
    val THEMES: Map<String, String> = linkedMapOf("day" to "sunlight", "golden" to "day", "dusk" to "night", "night" to "redshift")

    private fun julianDay(whenS: Double) = whenS / 86400.0 + 2440587.5

    /** The sun's altitude in degrees at unix time [whenS]; negative after dark; null with no position. */
    fun solarAltitude(lat: Double?, lon: Double?, whenS: Double): Double? {
        if (lat == null || lon == null) return null
        val centuries = (julianDay(whenS) - 2451545.0) / 36525.0

        val meanLong = (280.46646 + centuries * (36000.76983 + centuries * 0.0003032)).mod(360.0)
        val meanAnom = 357.52911 + centuries * (35999.05029 - 0.0001537 * centuries)
        val eccentricity = 0.016708634 - centuries * (0.000042037 + 0.0000001267 * centuries)

        val anomRad = Math.toRadians(meanAnom)
        val centre = (sin(anomRad) * (1.914602 - centuries * (0.004817 + 0.000014 * centuries))
            + sin(2 * anomRad) * (0.019993 - 0.000101 * centuries)
            + sin(3 * anomRad) * 0.000289)
        val apparent = (meanLong + centre - 0.00569 - 0.00478
            * sin(Math.toRadians(125.04 - 1934.136 * centuries)))

        var obliquity = 23.0 + (26.0 + (21.448 - centuries * (46.815 + centuries * (0.00059 - centuries * 0.001813))) / 60.0) / 60.0
        obliquity += 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * centuries))

        val declination = Math.toDegrees(asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(apparent))))

        // The equation of time, in minutes: the gap between clock noon and the
        // sun's own noon, up to a quarter of an hour either way across a year.
        val varY = tan(Math.toRadians(obliquity / 2.0)).pow(2)
        val eqTime = 4.0 * Math.toDegrees(
            varY * sin(2 * Math.toRadians(meanLong))
                - 2 * eccentricity * sin(anomRad)
                + 4 * eccentricity * varY * sin(anomRad) * cos(2 * Math.toRadians(meanLong))
                - 0.5 * varY * varY * sin(4 * Math.toRadians(meanLong))
                - 1.25 * eccentricity * eccentricity * sin(2 * anomRad),
        )

        val minutesUtc = whenS.mod(86400.0) / 60.0
        val trueSolar = (minutesUtc + eqTime + 4.0 * lon).mod(1440.0)
        var hourAngle = trueSolar / 4.0
        hourAngle = if (hourAngle >= 0) hourAngle - 180.0 else hourAngle + 180.0

        val latRad = Math.toRadians(lat)
        val decRad = Math.toRadians(declination)
        val cosZenith = (sin(latRad) * sin(decRad)
            + cos(latRad) * cos(decRad) * cos(Math.toRadians(hourAngle)))
        return 90.0 - Math.toDegrees(acos(max(-1.0, min(1.0, cosZenith))))
    }

    /** The phase for a sun altitude. */
    fun phaseFor(altitude: Double): String {
        for ((name, floor) in PHASES) if (altitude >= floor) return name
        return "night"
    }

    /**
     * Which phase it is. With no position - before the first fix - it falls
     * back to the local clock: cruder, never wrong by more than an hour or two,
     * and only used for the seconds before GPS answers.
     */
    fun phase(lat: Double?, lon: Double?, whenS: Double, altitude: Double? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val sun = altitude ?: solarAltitude(lat, lon, whenS)
        if (sun != null) return phaseFor(sun)
        val hour = Instant.ofEpochSecond(floor(whenS).toLong()).atZone(zone).hour
        return when {
            hour in 9 until 16 -> "day"
            hour in 7 until 9 || hour in 16 until 19 -> "golden"
            hour in 19 until 21 || hour in 5 until 7 -> "dusk"
            else -> "night"
        }
    }

    fun brightnessFor(name: String?): Double = BRIGHTNESS[name] ?: 1.0

    fun themeFor(name: String?): String = THEMES[name] ?: "night"

    /**
     * (next sunrise, next sunset) as unix times, or nulls - the NEXT of each,
     * searching forward from [whenS], not a calendar day's pair. Walking a UTC day
     * at a Californian longitude found that day's sunset before its sunrise,
     * because local midnight falls mid-UTC-day - a real bug. Found by stepping
     * forward and watching the altitude cross zero, so it can never disagree with
     * [solarAltitude], which is what drives the phases.
     */
    fun sunTimes(lat: Double?, lon: Double?, whenS: Double, stepMinutes: Int = 5): Pair<Double?, Double?> {
        if (lat == null || lon == null) return null to null
        var rise: Double? = null
        var fall: Double? = null
        var previous = solarAltitude(lat, lon, whenS)
        for (index in 1 until (24 * 60) / stepMinutes + 2) {
            val moment = whenS + index * stepMinutes * 60.0
            val current = solarAltitude(lat, lon, moment)
            if (previous != null && current != null) {
                if (previous < 0 && 0 <= current && rise == null) {
                    rise = moment
                } else if (previous >= 0 && 0 > current && fall == null) {
                    fall = moment
                }
            }
            if (rise != null && fall != null) break
            previous = current
        }
        return rise to fall
    }
}
