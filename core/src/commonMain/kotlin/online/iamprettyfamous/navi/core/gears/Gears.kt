package online.iamprettyfamous.navi.core.gears

import online.iamprettyfamous.navi.core.bikes.Bikes
import kotlin.math.abs
import kotlin.math.ln

/**
 * Which gear she is in, from road speed and engine speed.
 *
 * Ported from apps/navi/agedashii_navi/gears.py. In any gear the wheel and the
 * crank are locked together through a fixed ratio, so
 *
 *     ratio = road speed / engine speed
 *
 * is a constant for that gear and nothing else. The nearest one wins.
 *
 * What makes it wrong: the clutch (below [MIN_SPEED_MS] nothing is reported),
 * the RPM estimate (with the mic off there is no gear, and speed alone answers a
 * different question), and tyre wear or chain stretch, which [GearEstimate]'s
 * confidence exposes instead of hiding.
 */
object Gears {
    /** Below this the clutch is probably not fully out. 3 m/s is about 7 mph. */
    const val MIN_SPEED_MS = 3.0

    /**
     * How far a measured ratio may sit from a gear's and still match, as a
     * fraction. Gears are 15-30% apart, so 12% accepts wear without reaching the
     * next gear.
     */
    const val MATCH_TOLERANCE = 0.12

    /** Fraction of the redline at which the shift-up light comes on. */
    const val SHIFT_UP_FRACTION = 0.85

    /** Engine speed below which the shift-down light comes on. Lugging is worse than revving. */
    const val SHIFT_DOWN_RPM = 2200.0

    /**
     * Metres of road per engine revolution, per gear, lowest first. Published
     * overall ratios (primary x gear x final) over each bike's rolling
     * circumference - CB750 130/90-16 about 2.02 m, EX250 110/90-17 about 1.88 m.
     * Not yet measured on her bikes; [learn] exists for that.
     *
     * Sanity: the CB750 at 5000 rpm gives 27.59 / 38.4 / 48.1 / 56.5 / 64.3 mph.
     * The first version of this table was ten times too small.
     */
    val RATIOS: Map<String, List<Double>> = linkedMapOf(
        "cb750" to listOf(0.148, 0.206, 0.258, 0.303, 0.345),
        "ex250" to listOf(0.108, 0.150, 0.186, 0.217, 0.243, 0.264),
        "other" to listOf(0.140, 0.200, 0.255, 0.300, 0.340),
    )

    /** The table for a bike; an unknown or absent one uses the CB750's. */
    fun table(machine: String? = null): List<Double> =
        RATIOS[if (machine.isNullOrEmpty()) "cb750" else machine] ?: RATIOS.getValue("cb750")

    fun gearCount(machine: String? = null): Int = table(machine).size

    /**
     * Metres of road per engine revolution, or null. Guarded against a zero or
     * absent engine speed, which is what "the microphone is off" looks like.
     */
    fun ratio(speedMs: Double?, rpm: Double?): Double? {
        if (speedMs == null || rpm == null || speedMs <= 0.0 || rpm <= 0.0) return null
        return speedMs / (rpm / 60.0)
    }

    /**
     * The gear and how well it fit, or [GearEstimate.NONE].
     *
     * Confidence is 1.0 for an exact match and falls to 0.0 at the edge of
     * [MATCH_TOLERANCE]. Returns NONE - not a guess - when the clutch may be in,
     * when there is no engine speed, or when nothing fits.
     */
    fun estimate(speedMs: Double?, rpm: Double?, machine: String? = null): GearEstimate {
        if (speedMs == null || speedMs < MIN_SPEED_MS) return GearEstimate.NONE
        val measured = ratio(speedMs, rpm) ?: return GearEstimate.NONE
        var best: Int? = null
        var bestError: Double? = null
        table(machine).forEachIndexed { index, value ->
            val error = abs(measured - value) / value
            if (bestError == null || error < bestError!!) {
                best = index + 1
                bestError = error
            }
        }
        val error = bestError ?: return GearEstimate.NONE
        if (error > MATCH_TOLERANCE) return GearEstimate.NONE
        return GearEstimate(best, maxOf(0.0, 1.0 - error / MATCH_TOLERANCE))
    }

    /**
     * "up", "down" or "" - what the shift light should say. Neither fires
     * without a gear or an engine speed, "up" never fires in top gear and
     * "down" never in first.
     */
    fun shiftHint(rpm: Double?, gear: Int?, machine: String? = null, redlineRpm: Double? = null): String {
        if (rpm == null || gear == null) return ""
        // gears.py:126 - `redline_rpm or bikes.redline_rpm(machine)`, so 0 falls back too.
        val redline = if (redlineRpm != null && redlineRpm != 0.0) redlineRpm else Bikes.redlineRpm(machine).toDouble()
        if (rpm >= redline * SHIFT_UP_FRACTION && gear < gearCount(machine)) return "up"
        if (rpm <= SHIFT_DOWN_RPM && gear > 1) return "down"
        return ""
    }

    /**
     * A bike's real ratios from observed (speed m/s, rpm) pairs: split the
     * sorted ratios at the largest gaps - the gaps ARE the gear changes - and
     * return each group's median, which is robust to a mid-shift sample.
     */
    fun learn(samples: List<Pair<Double?, Double?>>, gears: Int? = null): List<Double> {
        val values = samples.mapNotNull { (speed, rpm) -> ratio(speed, rpm) }.sorted()
        if (values.isEmpty()) return emptyList()
        val groupsWanted = if (gears == null || gears == 0) 5 else gears
        // Largest gap first; a tie goes to the later index, as Python's
        // `sorted(..., reverse=True)` over (gap, index) tuples does.
        val gaps = (0 until values.size - 1)
            .map { i -> (values[i + 1] - values[i]) to i }
            .sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenByDescending { it.second })
        val cuts = gaps.take(maxOf(0, groupsWanted - 1)).map { it.second }.sorted()
        val groups = mutableListOf<Double>()
        var start = 0
        for (cut in cuts + (values.size - 1)) {
            val group = values.subList(start, minOf(cut + 1, values.size).coerceAtLeast(start))
            if (group.isNotEmpty()) groups.add(group[group.size / 2])
            start = cut + 1
        }
        return groups
    }

    /** How fast a given gear and engine speed implies, in m/s. The inverse. */
    fun speedFor(gear: Int?, rpm: Double?, machine: String? = null): Double? {
        val table = table(machine)
        if (gear == null || gear < 1 || gear > table.size || rpm == null || rpm == 0.0) return null
        return table[gear - 1] * (rpm / 60.0)
    }

    /**
     * Which gear puts the engine nearest [targetRpm] at this road speed. Needs
     * no microphone - it is a question about the gearbox, not a measurement.
     * Compared in log space so being a factor under counts the same as the same
     * factor over.
     */
    fun bestGearFor(speedMs: Double?, machine: String? = null, targetRpm: Double = 4000.0): Int? {
        if (speedMs == null || speedMs == 0.0 || speedMs < MIN_SPEED_MS) return null
        var best: Int? = null
        var bestError: Double? = null
        table(machine).forEachIndexed { index, value ->
            val rpm = (speedMs / value) * 60.0
            val error = abs(ln(rpm / targetRpm))
            if (bestError == null || error < bestError!!) {
                best = index + 1
                bestError = error
            }
        }
        return best
    }
}

/** A 1-based gear and how well it fit, from 0.0 to 1.0. */
data class GearEstimate(val gear: Int?, val confidence: Double) {
    companion object {
        val NONE = GearEstimate(null, 0.0)
    }
}
