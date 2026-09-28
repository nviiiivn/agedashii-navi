package online.iamprettyfamous.navi.core.bikes

/**
 * Which motorcycle, because the RPM maths is different for each one.
 *
 * Ported from apps/navi/agedashii_navi/bikes.py. This is not a cosmetic setting.
 * A four-stroke fires `cylinders / 2` times per revolution, so the fundamental of
 * the exhaust note maps to engine speed through a ratio that changes with the
 * engine:
 *
 *     CB750, inline four    2 firings per rev    RPM = Hz x 30
 *     EX250, parallel twin  1 firing  per rev    RPM = Hz x 60
 *
 * Get that wrong and every reading is out by a factor of two - exactly the kind
 * of error that looks plausible on a gauge.
 *
 * It also sets the search band, and that was a real bug: the first version
 * searched a fixed 20-200 Hz written for a twin, which would have capped the
 * CB750 around 6000 rpm and then reported harmonics as the fundamental. The band
 * is derived from each bike's own idle and redline with a margin either side.
 *
 * Keys are the strings the preference stores ("cb750", "ex250", "other"), so an
 * unknown or empty key falls back to [DEFAULT] rather than failing.
 */
object Bikes {
    /**
     * Margin either side of the idle-to-redline span, as a fraction. The
     * fundamental dips below idle on overrun, and a redline is a limit rather
     * than a wall.
     */
    const val BAND_MARGIN = 0.25

    /** Below this is road rumble and the phone moving in its mount, never an engine. */
    const val BAND_FLOOR_HZ = 15.0

    const val DEFAULT = "cb750"

    /** In declaration order, which is the order a picker shows them in. */
    val PROFILES: Map<String, Profile> = linkedMapOf(
        "cb750" to Profile(
            name = "Nighthawk CB750",
            detail = "1993 Honda, inline four, no baffles",
            // Inline four, four-stroke: two firing events per revolution.
            cylinders = 4, strokes = 4, idleRpm = 1000, redlineRpm = 9500,
        ),
        "ex250" to Profile(
            name = "Ninja EX250",
            detail = "2006 Kawasaki, parallel twin",
            // The EX250F revs a long way further than anything else here, which
            // is what pushes the top of its band up despite the four having
            // twice the firing rate.
            cylinders = 2, strokes = 4, idleRpm = 1300, redlineRpm = 14000,
        ),
        "other" to Profile(
            name = "Something else",
            detail = "single-cylinder four-stroke, wide band",
            cylinders = 1, strokes = 4, idleRpm = 1200, redlineRpm = 9000,
        ),
    )

    /** (id, display name) in declaration order, for a picker. */
    fun names(): List<Pair<String, String>> = PROFILES.map { (key, bike) -> key to bike.name }

    /** One profile. An unknown or empty id falls back rather than raising. */
    fun profile(key: String? = null): Profile =
        PROFILES[if (key.isNullOrEmpty()) DEFAULT else key] ?: PROFILES.getValue(DEFAULT)

    /**
     * Firing events per revolution: 2.0 for a four, 1.0 for a twin.
     *
     * `cylinders / (strokes / 2)` - a four-stroke completes one power stroke
     * every two revolutions per cylinder.
     */
    fun firingRatio(key: String? = null): Double {
        val bike = profile(key)
        return bike.cylinders / (bike.strokes / 2.0)
    }

    /** Engine speed from the exhaust fundamental, or null. */
    fun rpmFromHz(hz: Double?, key: String? = null): Double? {
        if (hz == null || hz <= 0.0) return null
        return hz * 60.0 / firingRatio(key)
    }

    /** The fundamental a given engine speed should produce. The inverse. */
    fun hzFromRpm(rpm: Double?, key: String? = null): Double? {
        if (rpm == null || rpm <= 0.0) return null
        return rpm * firingRatio(key) / 60.0
    }

    /**
     * (low, high) of the band worth searching for this bike, from its own idle
     * and redline. The floor is clamped at [BAND_FLOOR_HZ].
     */
    fun bandHz(key: String? = null): Pair<Double, Double> {
        val bike = profile(key)
        val low = hzFromRpm(bike.idleRpm.toDouble(), key) ?: 20.0
        val high = hzFromRpm(bike.redlineRpm.toDouble(), key) ?: 200.0
        return maxOf(BAND_FLOOR_HZ, low * (1.0 - BAND_MARGIN)) to high * (1.0 + BAND_MARGIN)
    }

    /** The fundamental at the redline, for scaling a gauge. */
    fun redlineHz(key: String? = null): Double = hzFromRpm(profile(key).redlineRpm.toDouble(), key)!!

    fun redlineRpm(key: String? = null): Int = profile(key).redlineRpm

    /**
     * Whether an estimate is inside what this engine can actually do, with the
     * same margin as the band. A peak-pick can land on a harmonic or a gust of
     * wind; an engine cannot turn at 400 rpm or at 20,000.
     */
    fun plausible(rpm: Double?, key: String? = null): Boolean {
        if (rpm == null) return false
        val bike = profile(key)
        return bike.idleRpm * (1.0 - BAND_MARGIN) <= rpm && rpm <= bike.redlineRpm * (1.0 + BAND_MARGIN)
    }
}

data class Profile(
    val name: String,
    val detail: String,
    val cylinders: Int,
    val strokes: Int,
    val idleRpm: Int,
    val redlineRpm: Int,
)
