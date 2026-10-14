package online.iamprettyfamous.navi.core.guidance

import online.iamprettyfamous.navi.core.Py
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Following a route, out loud, with no signal: the rules and the words.
 *
 * Ported from apps/navi/agedashii_navi/guidance.py. The live state is in
 * [Follower]; this is everything between "there is a route" and "the rider
 * knows what to do" that is not state.
 *
 * Prompts are timed in seconds of travel, not metres, so an instruction arrives
 * at the same point in the rider's decision whatever the speed - "in 200 feet"
 * at 25 mph and "in a quarter mile" at 70 are the same instruction. Below a
 * crawl the time bands collapse, so fixed distances take over.
 */
object Guidance {
    /** Consecutive fixes beyond the threshold before "off route". Three at 1 Hz is three seconds. */
    const val CONFIRM_FIXES = 3

    /** Coming back on needs the distance to fall to this fraction of the threshold. */
    const val RETURN_FRACTION = 0.6

    /** The off-route threshold's default, in metres. A preference, clamped 30-200 there. */
    const val OFFROUTE_M = 45.0

    /** Within this of the end of the line she has arrived. guidance.py:291. */
    const val ARRIVE_M = 25.0

    /** Seconds-to-turn for each prompt, furthest first, and the band's name. */
    val PROMPT_BANDS: List<Pair<Double, String>> = listOf(60.0 to "far", 20.0 to "near", 6.0 to "now")

    /** Below this speed the time bands would make every prompt "now" at a light. */
    const val CRAWL_MS = 3.0

    /** The fixed distances used below [CRAWL_MS], in metres. */
    val CRAWL_BANDS_M: Map<String, Double> = linkedMapOf("far" to 250.0, "near" to 90.0, "now" to 25.0)

    /**
     * A maneuver already announced is announced again, short of "now", only
     * once the distance has fallen below this share of where it last was. The
     * bands move with speed: picked at a light, "far" (crawl, 250 m) fired at
     * 178 m, then riding off at 15 m/s made "near" (300 m) due at 170 m - the
     * same "In 600 ft, turn right" twice, 6 s apart (minion 1's emulator
     * rides). Riding at one speed, far to near is 1/3 and crawl 90/250.
     */
    const val REPEAT_FRACTION = 0.6

    /**
     * A wider band is not spoken when the "now" point is less than this many
     * seconds away at her speed: "now" is about to say it. Right after a turn
     * the next one can come up just outside "now", and at 15 m/s "In 350 ft,
     * turn left" then "Turn left" came 1 s apart (minion 1's rides, #4).
     */
    const val NOW_SOON_S = 3.0

    /**
     * "And then": the maneuver after this one is a surprise when it is closer
     * than this many seconds at her speed - "now" (6 s) plus [NOW_SOON_S], so
     * it cannot get a warning of its own - with [THEN_MIN_M] as the floor. At
     * 30 mph about 120 m: 15% of maneuver pairs on 20 Bay routes (minion 1).
     */
    const val THEN_S = 9.0
    const val THEN_MIN_M = 60.0

    /** Never announced as "and then": the arrival is gentle, a continue or merge needs no warning. */
    val THEN_NOT: Set<String> = setOf(ManeuverKind.ARRIVE, ManeuverKind.CONTINUE, ManeuverKind.MERGE)

    /** The graph's road classes, in index order. washi routing.py:116-119. */
    val ROAD_CLASSES: List<String> = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
        "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road",
    )

    /**
     * Typical posted limits in California by road class, mph. NOT a reading of
     * any sign, and labelled as typical everywhere it is shown. The routing
     * graph's own speeds are fitted door-to-door travel - a different quantity,
     * and 15 mph low on a residential street.
     */
    val TYPICAL_MPH: Map<String, Int> = linkedMapOf(
        "motorway" to 65, "motorway_link" to 45, "trunk" to 55, "trunk_link" to 40,
        "primary" to 45, "primary_link" to 35, "secondary" to 40, "secondary_link" to 30,
        "tertiary" to 35, "tertiary_link" to 25, "unclassified" to 30,
        "residential" to 25, "living_street" to 15, "service" to 15, "road" to 25,
    )
    const val LIMIT_SOURCE = "typical for this road class, not a reading of the sign"

    /** How far over the limit before the readout warns. Wide enough not to nag. */
    const val OVER_LIMIT_MPH = 7.0

    const val MPS_TO_MPH = 2.2369362920544
    const val MPS_TO_KMH = 3.6

    fun mph(speedMs: Double?): Double? = speedMs?.let { it * MPS_TO_MPH }

    /** A speed for the big readout. Whole numbers - no decimal at a glance. */
    fun speedText(speedMs: Double?, imperial: Boolean = true): String {
        if (speedMs == null) return "—"
        return Py.fixed(speedMs * if (imperial) MPS_TO_MPH else MPS_TO_KMH, 0)
    }

    fun speedUnit(imperial: Boolean = true): String = if (imperial) "mph" else "km/h"

    /**
     * Distance the way a rider hears it: feet in fifties under a thousand, then
     * tenths of a mile; metres in tens, then kilometres. "In 400 ft", never
     * "in 412 ft".
     */
    fun distanceText(metres: Double?, imperial: Boolean = true): String {
        if (metres == null) return "—"
        if (imperial) {
            val feet = metres * 3.280839895
            if (feet < 1000) return "${Py.roundToLong(feet / 50.0) * 50} ft"
            val miles = metres / 1609.344
            return if (miles < 10) "${Py.fixed(miles, 1)} mi" else "${Py.fixed(miles, 0)} mi"
        }
        if (metres < 950) return "${Py.roundToLong(metres / 10.0) * 10} m"
        return "${Py.fixed(metres / 1000.0, 1)} km"
    }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** Clock time of arrival, which is what a rider actually wants. */
    fun etaText(seconds: Double?, now: Double, zone: ZoneId = ZoneId.systemDefault()): String {
        if (seconds == null) return "—"
        val epochSeconds = Math.floor(now + seconds).toLong()
        return CLOCK.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
    }

    /**
     * (typical mph, class) for a road class name, or (null, "") with no road.
     * An unknown class keeps its name and has no figure, as guidance.py:147 does.
     * The Python snapped the graph here itself; the snap belongs to the router,
     * which hands over the class it landed on.
     */
    fun limitMph(roadClass: String?): Pair<Int?, String> {
        if (roadClass == null) return null to ""
        return TYPICAL_MPH[roadClass] to roadClass
    }

    /** [limitMph] for the class index a graph edge stores. */
    fun limitMphForClassIndex(index: Int?): Pair<Int?, String> {
        val name = index?.let { ROAD_CLASSES.getOrNull(it) } ?: return null to ""
        return limitMph(name)
    }

    /**
     * The limit to show. Spec section 11: the posted limit wherever the road has
     * one tagged, labelled posted; the typical figure for its class otherwise,
     * labelled typical; and nothing, not a guess, when neither exists. The
     * Python had only the typical half - its graph carried no maxspeed.
     */
    fun speedLimit(postedMph: Int?, roadClass: String?): SpeedLimit {
        if (postedMph != null && postedMph > 0) return SpeedLimit(postedMph, LimitSource.POSTED, roadClass ?: "")
        val typical = roadClass?.let { TYPICAL_MPH[it] } ?: return SpeedLimit(null, LimitSource.NONE, roadClass ?: "")
        return SpeedLimit(typical, LimitSource.TYPICAL, roadClass)
    }

    /** Whether to colour the speed readout as over. False when either is unknown. */
    fun overLimit(speedMs: Double?, limit: Int?): Boolean {
        if (speedMs == null || limit == null || limit == 0) return false
        return mph(speedMs)!! > limit + OVER_LIMIT_MPH
    }

    /** band -> metres at which each prompt for the next turn fires. */
    fun promptPoints(speedMs: Double?): Map<String, Double> {
        if (speedMs == null || speedMs < CRAWL_MS) return LinkedHashMap(CRAWL_BANDS_M)
        return PROMPT_BANDS.associate { (seconds, band) -> band to speedMs * seconds }
    }

    /**
     * A glyph name chosen off the instruction's words - the router produces
     * text, not maneuver codes. Deliberately coarse: shapes a rider can tell
     * apart at a glance.
     */
    fun turnIcon(instruction: String?): String {
        val text = (instruction ?: "").lowercase()
        if ("u-turn" in text || "turn around" in text) return "u_turn"
        if ("arrive" in text || "destination" in text) return "flag"
        if ("left" in text) {
            if ("sharp" in text) return "sharp_left"
            return if ("slight" in text || "bear" in text) "slight_left" else "left"
        }
        if ("right" in text) {
            if ("sharp" in text) return "sharp_right"
            return if ("slight" in text || "bear" in text) "slight_right" else "right"
        }
        return "straight"
    }

    /**
     * One line to say out loud. "now" drops the distance: at six seconds out,
     * "in 80 feet" is noise in front of the instruction.
     */
    fun spoken(instruction: String?, street: String?, metres: Double?, imperial: Boolean = true, band: String = "near"): String {
        val what = if (instruction.isNullOrEmpty()) "continue" else instruction
        // Valhalla's words are whole sentences: nothing goes after the full stop
        // ("Continue toward CA 24/Berkeley. onto Grove Shafter Freeway", #9).
        val sentence = what.trimEnd().let { it.endsWith(".") || it.endsWith("!") || it.endsWith("?") }
        // Arrival has no "onto": "arrive at your destination onto Broadway" (guidance.py).
        val arriving = "arrive" in what.lowercase() || "destination" in what.lowercase()
        val where = if (!sentence && !arriving && !street.isNullOrEmpty() && street !in what) " onto $street" else ""
        if (band == "now") return "$what$where".trim()
        return "In ${distanceText(metres, imperial)}, $what$where".trim()
    }
}

enum class LimitSource(val label: String) {
    POSTED("posted"),
    TYPICAL("typical"),
    NONE(""),
}

/** A speed limit and where it came from. The two sources never share a style on screen. */
data class SpeedLimit(val mph: Int?, val source: LimitSource, val roadClass: String) {
    companion object {
        val NONE = SpeedLimit(null, LimitSource.NONE, "")
    }
}
