package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.bikes.Bikes
import online.iamprettyfamous.navi.core.theme.Role
import kotlin.math.abs
import kotlin.math.sqrt

/** A drawn direction. NONE is the shift light's resting dash. */
enum class Arrow { UP, DOWN, LEVEL, NONE }

/** A role at an opacity, for the few fills that sit translucent over the map. */
data class Paint(val role: Role, val alpha: Float = 1f)

/**
 * Which role every painted part of every gauge wears - in ONE table, so any of
 * it is a one-line change.
 *
 * Her rule, theme.py: WARN, BAD and GOOD are STATES and never decoration, or
 * off-route stops being findable by colour alone. gauges.py broke it in four
 * places, each a decoration wearing a state's colour; [CHANGED] lists them with
 * the Python's role and ours, and DashGoldenTest allows exactly those and no
 * other difference. Kept as the Python had them, because they ARE states: the
 * g dot past 0.8 g, a meter in its top fifth, braking, the rev-range gradient,
 * the shift light, lean warming with the angle, and speed over the limit.
 */
object DashColours {
    /**
     * The glass slab under floating gauges: the theme's bg at this opacity over
     * the map. 0.72 is where tiles read as texture rather than detail; the
     * contrast of every readout on it is asserted by DashContrastTest.
     */
    const val GLASS_ALPHA = 0.72f
    const val GLASS_BORDER_ALPHA = 0.5f

    /** Sky: the one interactive colour, faint. Never an alarm, so allowed. */
    val HORIZON_SKY = Paint(Role.ACCENT, 0.22f)

    /** Ground. The Python painted it WARN, so a warning colour was on screen at all times. */
    val HORIZON_GROUND = Paint(Role.TEXT3, 0.28f)
    val HORIZON_LINE = Role.TEXT
    val HORIZON_MARK = Role.TEXT

    /** The phone's own roll, drawn only when it disagrees with the lean. */
    val ROLL_NEEDLE = Role.TEXT3

    val G_DOT = Role.ACCENT
    val G_DOT_HARD = Role.BAD
    val METER = Role.ACCENT
    val METER_HIGH = Role.WARN

    /** One data colour for every band: position already says which band. */
    val BAND = Role.ACCENT
    val CLIMB_UP = Role.TEXT
    val CLIMB_DOWN = Role.TEXT
    val CLIMB_LEVEL = Role.TEXT2

    /** Drive is data; braking IS the alarm, so it keeps BAD. */
    val DRIVE = Role.ACCENT
    val BRAKE = Role.BAD
    val CORNER = Role.ACCENT

    /** The rpm bar's gradient: rev zones are states - the red end is the redline. */
    val REV_ZONES = listOf(Role.GOOD, Role.WARN, Role.BAD)
    val SHIFT_UP = Role.BAD
    val SHIFT_DOWN = Role.WARN
    val SHIFT_NONE = Role.TEXT3
    val OVER_LIMIT = Role.BAD

    /** Lean colour by angle, warmest last - the OsmAnd moto idea. */
    val LEAN_BANDS: List<Pair<Double, Role>> = listOf(0.0 to Role.TEXT, 18.0 to Role.WARN, 32.0 to Role.BAD)

    /** gauges.py part -> (its role -> ours), for every deliberate change. */
    val CHANGED: Map<String, Map<Role, Role>> = mapOf(
        "horizon_ground" to mapOf(Role.WARN to HORIZON_GROUND.role),
        "bands" to mapOf(Role.GOOD to BAND, Role.WARN to BAND),
        "climb_up" to mapOf(Role.GOOD to CLIMB_UP),
        "climb_down" to mapOf(Role.WARN to CLIMB_DOWN),
        "g_long" to mapOf(Role.GOOD to DRIVE),
    )

    /**
     * The role TEXT is drawn in, given the role the gauge asks for. Red night's
     * text2 is 3.13:1 on the glass, under the 4.5 floor, and she chose
     * (2026-09-27) to draw labels, units and dashes in `text` there: one bright
     * red, with only size separating a label from its number. text2's hex is
     * unchanged, and graphics - the level arrow - still use it.
     */
    fun textRole(theme: String, role: Role): Role =
        if (theme == "redshift" && role == Role.TEXT2) Role.TEXT else role
}

/**
 * The gauges' arithmetic and words, with no drawing in it: every clamp, fraction,
 * angle and text of apps/navi/agedashii_navi/gauges.py (md5 86eb5cf3), proved
 * against the Python by DashGoldenTest. The app only draws what these return.
 *
 * Every input may be null and every gauge handles it: an empty gauge is the
 * honest picture of a phone without the sensor, and a dash is never a zero.
 */
object Gauges {
    const val DASH = "—"

    /** Lean that puts the horizon at the edge of the ball. */
    const val LEAN_FULL_DEG = 45.0

    /** Pitch that moves the horizon band a full half-ball. */
    const val PITCH_FULL_DEG = 30.0

    /** Cornering or braking load that reaches the rim of the g dot. */
    const val G_FULL = 1.0

    /** Degrees lean and phone roll must disagree by before the phone's needle is drawn. */
    const val ROLL_HINT_DEG = 12.0

    /** One conversion everywhere (gauges.py used two; the difference is invisible). */
    const val FT_PER_M = 3.28084
    const val FT_PER_MIN_PER_MPS = 196.850394

    private fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    /** `f"{x:.0f}"`: a whole number, ties to even as the Python rounded them. */
    fun whole(x: Double): String = Py.fixed(x, 0)

    /**
     * Where gauges.py is WRONG, not merely different. It turned a missing reading
     * into a measurement - `x ?: 0.0` - so an empty gauge looked exactly like a
     * zero one: a phone with no barometer said "level", one with no accelerometer
     * put the g dot dead centre, which reads as "perfectly balanced". An empty
     * gauge and a zero reading must never look alike; a gauge that cannot know
     * draws nothing (AGEDASHII's ruling, 2026-09-28). DashGoldenTest breaks the
     * golden at exactly these parts, for a null input, and nowhere else.
     */
    val PYTHON_WRONG: Map<String, String> = linkedMapOf(
        "horizon" to "gauges.py is wrong here: with no lean it drew a LEVEL horizon; we draw no horizon at all",
        "horizon_line" to "gauges.py is wrong here: with no lean it drew the gunsight line level; we draw no line",
        "g_ball" to "gauges.py is wrong here: a missing axis became 0 g, so the dot sat on the centre or an axis and the " +
            "caption's total leaned on the zero; we draw no dot and a dash unless both axes are known",
        "climb" to "gauges.py is wrong here: with no climb rate it drew the level arrow and the word \"level\"; " +
            "we draw the resting dash and a dash for the word",
    )

    /** [rotationDeg] is null when the lean is unknown, and then nothing is drawn: see [PYTHON_WRONG]. */
    data class Horizon(val rotationDeg: Double?, val shift: Double, val needleDeg: Double?, val caption: String)

    /**
     * The artificial horizon. Lean - the bike's, from speed and yaw - rotates it,
     * NEGATIVELY: leaning right drops the right of the horizon, as through a
     * visor. Pitch raises it: [Horizon.shift] is a fraction of the double-height
     * band, a quarter of it being half the ball. The phone's roll is a thin needle
     * drawn only when it disagrees with the lean: in a steady corner a mounted
     * phone reads near zero roll, so showing roll as lean would be a lie exactly
     * when lean matters.
     */
    fun horizon(leanDeg: Double?, pitchDeg: Double?, rollDeg: Double?): Horizon {
        val lean = leanDeg?.let { -clamp(it, -89.0, 89.0) }
        // Still the Python's zero for a missing pitch, beside a KNOWN lean: pitch
        // waits on calibration, and hiding a known lean for it costs more than a
        // band a few hundredths off centre. Only reachable before calibration.
        val pitch = if (pitchDeg == null) 0.0 else clamp(pitchDeg, -89.0, 89.0)
        val shift = clamp(pitch / PITCH_FULL_DEG, -1.0, 1.0) * 0.25
        val needle = if (rollDeg != null && leanDeg != null && abs(rollDeg - leanDeg) >= ROLL_HINT_DEG) {
            -clamp(rollDeg, -89.0, 89.0)
        } else {
            null
        }
        return Horizon(lean, shift, needle, "lean " + if (leanDeg == null) DASH else whole(abs(leanDeg)) + "°")
    }

    /** [x] and [y] are null unless both axes are known, and then no dot is drawn: see [PYTHON_WRONG]. */
    data class GBall(val x: Double?, val y: Double?, val total: Double?, val dot: Role, val caption: String)

    /**
     * The g-g diagram: cornering across, braking and drive up and down. Braking
     * is negative g_long and plots at the BOTTOM, where a rider feels it.
     */
    fun gBall(gLat: Double?, gLong: Double?): GBall {
        if (gLat == null || gLong == null) return GBall(null, null, null, DashColours.G_DOT, DASH)
        val total = sqrt(gLat * gLat + gLong * gLong)
        return GBall(
            x = clamp(gLat / G_FULL, -1.0, 1.0),
            y = clamp(-gLong / G_FULL, -1.0, 1.0),
            total = total,
            dot = if (total > 0.8) DashColours.G_DOT_HARD else DashColours.G_DOT,
            caption = Py.fixed(total, 2) + " g",
        )
    }

    data class Meter(val fill: Double, val bar: Role, val tick: Double?, val value: String, val label: String)

    /** A vertical bar with a peak tick. Null draws EMPTY: "no signal" must not look like "flat out". */
    fun meter(fraction: Double?, peak: Double?, label: String, value: String): Meter {
        val fill = if (fraction == null) 0.0 else clamp(fraction, 0.0, 1.0)
        return Meter(
            fill = fill,
            bar = if (fill > 0.8) DashColours.METER_HIGH else DashColours.METER,
            tick = peak?.let { clamp(it, 0.0, 1.0) },
            value = value.ifEmpty { DASH },
            label = label,
        )
    }

    data class Climb(val arrow: Arrow, val role: Role, val height: String, val detail: String)

    /**
     * Height, and which way it is going. A 0.3 m/s dead band, because a
     * barometer wanders a few centimetres a second standing still and a
     * flickering arrow is worse than none.
     */
    fun climb(altM: Double?, climbMps: Double?, imperial: Boolean): Climb {
        val (arrow, role, word) = when {
            // No rate is not level: see PYTHON_WRONG.
            climbMps == null -> Triple(Arrow.NONE, DashColours.CLIMB_LEVEL, DASH)
            climbMps > 0.3 -> Triple(Arrow.UP, DashColours.CLIMB_UP, "climbing")
            climbMps < -0.3 -> Triple(Arrow.DOWN, DashColours.CLIMB_DOWN, "descending")
            else -> Triple(Arrow.LEVEL, DashColours.CLIMB_LEVEL, "level")
        }
        val height = when {
            altM == null -> DASH
            imperial -> whole(altM * FT_PER_M) + " ft"
            else -> whole(altM) + " m"
        }
        val rateText = when {
            climbMps == null -> ""
            imperial -> whole(climbMps * FT_PER_MIN_PER_MPS) + " ft/min"
            else -> Py.fixed(climbMps, 1) + " m/s"
        }
        return Climb(arrow, role, height, rateText.ifEmpty { word })
    }

    /** dBFS to 0..1 on audio.py's meter scale, -60 empty to -6 full. */
    fun loudnessNorm(dbfs: Double?): Double? = dbfs?.let { clamp((it + 60.0) / 54.0, 0.0, 1.0) }

    /** RPM to 0..1 of THIS engine's redline: a twin turns 14,000 and a four 9,500. */
    fun rpmFraction(rpm: Double?, machine: String?): Double? =
        rpm?.let { clamp(it / Bikes.redlineRpm(machine).toDouble(), 0.0, 1.0) }

    data class Band(val label: String, val fill: Double, val role: Role)

    /** Her multi-peak meter: one thin bar per band; no bands draws three empty ones. */
    fun bands(bands: List<Pair<String, Double?>>?): List<Band> {
        val shown = if (bands.isNullOrEmpty()) listOf("low" to 0.0, "mid" to 0.0, "high" to 0.0) else bands
        return shown.map { (name, value) -> Band(name.take(3), clamp(value ?: 0.0, 0.0, 1.0), DashColours.BAND) }
    }

    /** Lean readout colour, warming with the angle; faint when there is none. */
    fun leanRole(leanDeg: Double?): Role {
        if (leanDeg == null) return Role.TEXT2
        var role = Role.TEXT
        for ((floor, r) in DashColours.LEAN_BANDS) if (abs(leanDeg) >= floor) role = r
        return role
    }

    data class GearBadge(val text: String, val textRole: Role, val arrow: Arrow, val arrowRole: Role)

    /**
     * The gear and the shift light. The arrow is coloured rather than added: a
     * light that appears and disappears moves the layout under her eye just as
     * she reads it. Gear 0 is not a gear and draws faint, as `if gear` did.
     */
    fun gearBadge(gear: Int?, hint: String): GearBadge {
        val (arrow, role) = when (hint) {
            "up" -> Arrow.UP to DashColours.SHIFT_UP
            "down" -> Arrow.DOWN to DashColours.SHIFT_DOWN
            else -> Arrow.NONE to DashColours.SHIFT_NONE
        }
        return GearBadge(gear?.toString() ?: DASH, if (gear != null && gear != 0) Role.TEXT else Role.TEXT3, arrow, role)
    }

    /** One centre-out bar, as fractions of its width: empty left, fill, empty right. */
    data class CentreBar(val left: Double, val fill: Double, val right: Double, val role: Role)

    data class GForceBars(val long: CentreBar, val lat: CentreBar)

    /** Braking/drive and cornering, growing from the centre: the sign is the reading. */
    fun gForceBars(gLat: Double?, gLong: Double?): GForceBars {
        fun bar(value: Double?, positive: Role, negative: Role): CentreBar {
            val amount = clamp((value ?: 0.0) / G_FULL, -1.0, 1.0)
            return CentreBar(
                left = (1.0 - maxOf(0.0, -amount)) / 2.0,
                fill = abs(amount) / 2.0,
                right = (1.0 - maxOf(0.0, amount)) / 2.0,
                role = if (amount >= 0) positive else negative,
            )
        }
        return GForceBars(bar(gLong, DashColours.DRIVE, DashColours.BRAKE), bar(gLat, DashColours.CORNER, DashColours.CORNER))
    }

    data class RpmLine(val fill: Double, val text: String, val textRole: Role)

    /** Engine speed as a bar that reddens to the redline: length reads faster than a needle's angle. */
    fun rpmLine(rpm: Double?, machine: String?, hint: String): RpmLine = RpmLine(
        fill = rpmFraction(rpm, machine) ?: 0.0,
        text = if (rpm == null) DASH else whole(rpm) + " rpm",
        textRole = if (hint == "up") DashColours.OVER_LIMIT else Role.TEXT2,
    )

    /** [rotationDeg] is null when the lean is unknown, and then no line is drawn: see [PYTHON_WRONG]. */
    data class HorizonLine(val rotationDeg: Double?, val shift: Double, val role: Role)

    /** The attitude indicator reduced to its one load-bearing line. */
    fun horizonLine(leanDeg: Double?, pitchDeg: Double?): HorizonLine = HorizonLine(
        rotationDeg = leanDeg?.let { -clamp(it, -75.0, 75.0) },
        shift = clamp((pitchDeg ?: 0.0) / PITCH_FULL_DEG, -1.0, 1.0) * 0.35,
        role = leanRole(leanDeg),
    )

    /** Altitude as the ribbon and corners show it: a number and its unit. */
    fun altitude(altM: Double?, imperial: Boolean): Pair<String, String> =
        (if (altM == null) DASH else whole(if (imperial) altM * FT_PER_M else altM)) to (if (imperial) "ft" else "m")

    /** "28°", or a dash. */
    fun leanText(leanDeg: Double?): String = if (leanDeg == null) DASH else whole(abs(leanDeg)) + "°"
}
