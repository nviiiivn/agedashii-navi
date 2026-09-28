package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.Py

/**
 * Rate of climb from GPS altitude, for a phone with no barometer.
 *
 * Ported from live.py:307-330. A least-squares slope rather than
 * (last - first) / span, because GPS altitude is noisy enough that two
 * endpoints disagree by ten metres while the thirty points between them
 * describe a flat road. Much longer than the barometer's six seconds for the
 * same reason: a short window measures the noise, not the hill.
 */
class GpsClimb(val windowS: Double = WINDOW_S, val minSamples: Int = MIN_SAMPLES) {
    private val history = ArrayDeque<Pair<Double, Double>>()

    var climbMps: Double? = null
        private set

    /** One fix's altitude at time [t]. A fix without one changes nothing. */
    fun add(altM: Double?, t: Double): Double? {
        if (altM == null) return null
        history.addLast(t to altM)
        while (history.isNotEmpty() && t - history.first().first > windowS) history.removeFirst()
        if (history.size < minSamples) {
            climbMps = null
            return null
        }
        val n = history.size
        val meanT = history.sumOf { it.first } / n
        val meanA = history.sumOf { it.second } / n
        val numerator = history.sumOf { (it.first - meanT) * (it.second - meanA) }
        val denominator = history.sumOf { (it.first - meanT) * (it.first - meanT) }
        climbMps = if (denominator != 0.0) numerator / denominator else null
        return climbMps
    }

    fun reset() {
        history.clear()
        climbMps = null
    }

    companion object {
        /** live.py:48 CLIMB_GPS_WINDOW_S. */
        const val WINDOW_S = 30.0

        /** Fewer fixes than this is null rather than a wild number. */
        const val MIN_SAMPLES = 4
    }
}

/** Which source wins. live.py:333-350. */
object Climb {
    /** The barometer wherever there is one; the GPS slope on a phone without, so the gauge shows a rate, not a dash. */
    fun best(baroMps: Double?, gpsMps: Double?): Double? = baroMps ?: gpsMps

    /** Barometric height if present, else the GPS fix's. */
    fun bestAltitude(baroAltM: Double?, gpsAltM: Double?): Double? = baroAltM ?: gpsAltM
}

/** The per-fix rules that belong to the live session rather than the ride log. live.py. */
object Live {
    /**
     * A fix worse than this is not used for anything. Matches the ride log's own
     * rejection on purpose: a 200 m error under a bridge must not be good enough
     * to declare her off route either.
     */
    const val MAX_ACC_M = 50.0

    /** "The bike is rolling", for autorecord. About 5.6 mph - faster than a GPS wandering while parked. */
    const val ROLLING_MS = 2.5

    fun accepts(accM: Double?): Boolean = accM == null || accM <= MAX_ACC_M

    fun rolling(speedMs: Double?): Boolean = speedMs != null && speedMs >= ROLLING_MS

    /** What a knock while recording is logged as: the peak with gravity removed, the honest measure of the hit. */
    fun knockIncident(forceG: Double): String = "impact ${Py.fixed(forceG, 1)} g"
}
