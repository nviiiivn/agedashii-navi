package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.LimitSource
import online.iamprettyfamous.navi.core.dash.DashState.Field
import online.iamprettyfamous.navi.core.theme.Role

/**
 * The arrangements of the gauges over the map, chosen in Settings. They exist
 * because a third of the screen covered in readouts was the first thing she
 * objected to: "its like literally 1/3 of the screen is just information".
 *
 * OFF is not a layout; it is the gauges switch, honoured here so an old stored
 * "off" still draws nothing. gauges.py LAYOUTS, in order.
 */
enum class Layout(val key: String, val title: String, val detail: String) {
    RIBBON("ribbon", "Ribbon", "one thin row along the bottom edge"),
    CORNERS("corners", "Corners", "anchored in the corners, middle of the map clear"),
    GUNSIGHT("gunsight", "Gunsight", "a full attitude indicator in the centre"),
    COLUMN("column", "Column", "a narrow strip down the left edge"),
    STRIP("strip", "Strip", "the full band under the map - everything at once"),
    MINIMAL("minimal", "Minimal", "speed and limit, nothing else"),
    OFF("off", "Off", "no gauges");

    companion object {
        /** The default she picked. */
        val DEFAULT = RIBBON

        fun of(key: String?): Layout = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Which edge a layout hugs: portrait bottom or top, landscape right or left - her choice, per orientation. */
enum class Anchor {
    BOTTOM, TOP, LEFT, RIGHT;

    companion object {
        fun of(key: String?): Anchor = entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: BOTTOM
    }
}

/** The four type sizes, and nothing between them. */
enum class Size { HUGE, BIG, MID, SMALL }

/**
 * One thing drawn in a HUD region. [texts] is every word it shows, for tests
 * and for accessibility. [reads] is every DashState field it draws from - a
 * constructor argument with no default, so a new gauge cannot be written
 * without saying what it reads; Hud.channels turns them into the sensors that
 * must run, and HudChannelsTest proves the declarations are true.
 */
sealed interface Item {
    val reads: Set<Field>
    fun texts(): List<String>
}

/** A number and its unit, the unit small on the baseline. */
data class Num(val value: String, val unit: String, val size: Size, val role: Role, override val reads: Set<Field>) : Item {
    override fun texts() = listOf(value) + if (unit.isNotEmpty()) listOf(unit) else emptyList()
}

/** Speed and the limit that belongs with it. [limitSource] styles posted and typical apart. */
data class SpeedBlock(val speed: Num, val limit: String, val limitSource: LimitSource) : Item {
    override val reads get() = speed.reads + Field.LIMIT
    override fun texts() = speed.texts() + limit
}

data class GForceItem(val bars: Gauges.GForceBars, val widthDp: Int) : Item {
    override val reads get() = setOf(Field.G_LAT, Field.G_LONG)
    override fun texts() = listOf("BRK", "ACC")
}

data class RpmItem(val line: Gauges.RpmLine, val widthDp: Int) : Item {
    override val reads get() = setOf(Field.RPM, Field.SHIFT)
    override fun texts() = listOf(line.text)
}

data class GearItem(val badge: Gauges.GearBadge, val size: Size) : Item {
    override val reads get() = setOf(Field.GEAR, Field.SHIFT)
    override fun texts() = listOf(badge.text)
}

data class HorizonItem(val horizon: Gauges.Horizon, val sizeDp: Int) : Item {
    override val reads get() = setOf(Field.LEAN, Field.PITCH, Field.TILT)
    override fun texts() = listOf(horizon.caption)
}

data class GBallItem(val ball: Gauges.GBall, val sizeDp: Int) : Item {
    override val reads get() = setOf(Field.G_LAT, Field.G_LONG)
    override fun texts() = listOf(ball.caption)
}

data class ClimbItem(val climb: Gauges.Climb) : Item {
    override val reads get() = setOf(Field.ALT, Field.CLIMB)
    override fun texts() = listOf(climb.height, climb.detail, "altitude")
}

data class MeterItem(val meter: Gauges.Meter, override val reads: Set<Field>) : Item {
    override fun texts() = listOf(meter.value, meter.label)
}

data class BandsItem(val bands: List<Gauges.Band>, val label: String) : Item {
    override val reads get() = setOf(Field.BANDS)
    override fun texts() = bands.map { it.label } + label
}

/** Where a group sits over the map. EDGE follows [Hud.anchor]; STRIP takes its own height under the map. */
enum class Region { TOP_START, TOP_END, CENTER, BOTTOM_START, BOTTOM_END, EDGE, STRIP }

/** Items drawn together; [vertical] stacks them, else they sit in a row. */
data class Group(val region: Region, val items: List<Item>, val vertical: Boolean)

/** One layout, planned: everything the app draws, and nothing it has to decide. */
data class Hud(val layout: Layout, val anchor: Anchor, val wide: Boolean, val groups: List<Group>) {
    fun texts(): List<String> = groups.flatMap { g -> g.items.flatMap { it.texts() } }

    companion object {
        /**
         * The plan for [layout], or null for OFF. gauges.py `overlay()`. Every layout
         * floats over the map except STRIP, the one view of everything at once.
         * RIBBON in landscape ([wide]) becomes a column down her chosen side: a row
         * across a short, wide screen eats the road she is riding into.
         */
        fun plan(state: DashState, layout: Layout, wide: Boolean = false, anchor: Anchor = Anchor.BOTTOM): Hud? {
            val lean = state.leanDeg
            val hint = state.shift
            val limitMph = state.limit.mph?.takeIf { it != 0 }
            fun speed(size: Size) = SpeedBlock(
                Num(
                    Guidance.speedText(state.speedMs, state.imperial), Guidance.speedUnit(state.imperial), size,
                    if (Guidance.overLimit(state.speedMs, state.limit.mph)) DashColours.OVER_LIMIT else Role.TEXT,
                    setOf(Field.SPEED, Field.LIMIT),
                ),
                limit = limitMph?.let { "limit $it" } ?: Gauges.DASH,
                limitSource = state.limit.source,
            )
            val leanNum = Num(Gauges.leanText(lean), "lean", Size.MID, Gauges.leanRole(lean), setOf(Field.LEAN))
            val (altValue, altUnit) = Gauges.elevation(state.altM, state.imperial)
            val alt = Num(altValue, altUnit, Size.MID, Role.TEXT, setOf(Field.ALT))
            val (sinceValue, sinceUnit) = Gauges.sinceStart(state.climbSinceStartM, state.imperial)
            val sinceStart = Num(sinceValue, sinceUnit, Size.MID, Role.TEXT, setOf(Field.CLIMB_SINCE_START))
            fun rpm(width: Int) = RpmItem(Gauges.rpmLine(state.rpm, state.machine, hint), width)
            val gear = GearItem(Gauges.gearBadge(state.gear, hint), Size.MID)
            fun gForce(width: Int) = GForceItem(Gauges.gForceBars(state.gLat, state.gLong), width)

            val groups = when (layout) {
                Layout.OFF -> return null
                Layout.MINIMAL -> listOf(
                    Group(
                        when (anchor) {
                            Anchor.TOP -> Region.TOP_START
                            Anchor.RIGHT -> Region.BOTTOM_END
                            else -> Region.BOTTOM_START
                        },
                        listOf(speed(Size.HUGE)),
                        vertical = true,
                    ),
                )
                Layout.RIBBON -> listOf(
                    Group(
                        Region.EDGE,
                        listOf(
                            speed(Size.BIG).speed,
                            Num(limitMph?.toString() ?: Gauges.DASH, "lim", Size.MID, Role.TEXT, setOf(Field.LIMIT)),
                            leanNum, gForce(72), rpm(90), gear, alt, sinceStart,
                        ),
                        vertical = wide,
                    ),
                )
                Layout.CORNERS -> listOf(
                    Group(Region.TOP_START, listOf(leanNum), false),
                    Group(Region.TOP_END, listOf(alt), false),
                    Group(Region.BOTTOM_START, listOf(speed(Size.HUGE)), true),
                    Group(Region.BOTTOM_END, listOf(rpm(120), gear), true),
                )
                // The real instrument, not a line: her "attitude indicator also
                // known as gyro horizon and artificial horizon".
                Layout.GUNSIGHT -> listOf(
                    Group(Region.CENTER, listOf(HorizonItem(Gauges.horizon(lean, state.pitchDeg, state.tiltDeg), 148)), true),
                    Group(Region.BOTTOM_START, listOf(speed(Size.BIG)), true),
                    Group(Region.BOTTOM_END, listOf(rpm(90), gear), true),
                )
                Layout.COLUMN -> listOf(
                    Group(Region.EDGE, listOf(speed(Size.BIG), leanNum, gForce(80), rpm(80), gear), true),
                )
                Layout.STRIP -> listOf(
                    Group(
                        Region.STRIP,
                        listOf(
                            HorizonItem(Gauges.horizon(lean, state.pitchDeg, state.tiltDeg), 92),
                            GBallItem(Gauges.gBall(state.gLat, state.gLong), 92),
                            ClimbItem(Gauges.climb(state.altM, state.climbMps, state.imperial)),
                            MeterItem(Gauges.meter(Gauges.rpmFraction(state.rpm, state.machine), null, "rpm est", state.rpm?.let { Gauges.whole(it) } ?: Gauges.DASH), setOf(Field.RPM)),
                            BandsItem(Gauges.bands(state.bands), "engine"),
                            MeterItem(Gauges.meter(Gauges.loudnessNorm(state.levelDb), state.levelPeak, "level", state.levelDb?.let { Gauges.whole(it) + " dB" } ?: Gauges.DASH), setOf(Field.LEVEL, Field.LEVEL_PEAK)),
                        ),
                        vertical = false,
                    ),
                )
            }
            val edge = when (layout) {
                Layout.RIBBON -> if (wide) (if (anchor == Anchor.RIGHT) Anchor.RIGHT else Anchor.LEFT) else (if (anchor == Anchor.TOP) Anchor.TOP else Anchor.BOTTOM)
                Layout.COLUMN -> if (anchor == Anchor.RIGHT) Anchor.RIGHT else Anchor.LEFT
                else -> anchor
            }
            return Hud(layout, edge, wide, groups)
        }

        /**
         * The ride channels [layout] reads, in either orientation and at any
         * anchor - for Telemetry's sensors on demand (minion 1): not recording,
         * only the sensors behind these run. Empty when the dash is off.
         */
        fun channels(layout: Layout, dashOn: Boolean): Set<String> {
            if (!dashOn) return emptySet()
            return listOf(false, true)
                .flatMap { wide -> Anchor.entries.mapNotNull { plan(DashState(), layout, wide, it) } }
                .flatMap { it.groups }.flatMap { it.items }.flatMap { it.reads }.flatMap { it.channels }
                .distinct().sorted().toSet()
        }
    }
}
