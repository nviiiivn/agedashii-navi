package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.dash.DashState.Field
import online.iamprettyfamous.navi.core.guidance.LimitSource
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sensors on demand (minion 1's Telemetry) runs only the sensors behind the
 * channels the visible layout reads. An item that reads a field it did not
 * declare would FREEZE with its sensor off - a needle stuck on a stale number,
 * the worst kind of wrong. So every declaration is proved from both sides.
 */
class HudChannelsTest {
    /** Every field set, and set so that it shows: over the limit, shift due, the phone disagreeing with the lean. */
    private val full = DashState(
        imperial = true, machine = "cb750", speedMs = 29.0, limit = SpeedLimit(55, LimitSource.POSTED, "primary"),
        leanDeg = 24.0, tiltDeg = 5.0, pitchDeg = 4.0, gLat = 0.45, gLong = -0.3, altM = 152.0,
        climbSinceStartM = 40.0, climbMps = 1.2, rpm = 6200.0, gear = 4, shift = "up",
        bands = listOf("low" to 0.6, "mid" to 0.35, "high" to 0.15), levelDb = -18.0, levelPeak = 0.7,
    )

    /** [full] without one field. A `when`, so a new Field does not compile until it is here. */
    private fun without(f: Field): DashState = when (f) {
        Field.SPEED -> full.copy(speedMs = null)
        Field.LIMIT -> full.copy(limit = SpeedLimit.NONE)
        Field.LEAN -> full.copy(leanDeg = null)
        Field.TILT -> full.copy(tiltDeg = null)
        Field.PITCH -> full.copy(pitchDeg = null)
        Field.G_LAT -> full.copy(gLat = null)
        Field.G_LONG -> full.copy(gLong = null)
        Field.ALT -> full.copy(altM = null)
        Field.CLIMB_SINCE_START -> full.copy(climbSinceStartM = null)
        Field.CLIMB -> full.copy(climbMps = null)
        Field.RPM -> full.copy(rpm = null)
        Field.GEAR -> full.copy(gear = null)
        Field.SHIFT -> full.copy(shift = "")
        Field.BANDS -> full.copy(bands = null)
        Field.LEVEL -> full.copy(levelDb = null)
        Field.LEVEL_PEAK -> full.copy(levelPeak = null)
    }

    private val plans = Layout.entries.filter { it != Layout.OFF }.flatMap { layout ->
        listOf(false, true).flatMap { wide -> Anchor.entries.map { anchor -> Triple(layout, wide, anchor) } }
    }

    private fun items(state: DashState, p: Triple<Layout, Boolean, Anchor>) =
        Hud.plan(state, p.first, p.second, p.third)!!.groups.flatMap { it.items }

    @Test
    fun `every DashState reading has a Field - settings aside`() {
        val settings = setOf("imperial", "machine")
        val readings = DashState::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.filter { it !in settings }
        assertEquals(Field.entries.size, readings.size, "a DashState field has no Field: $readings")
    }

    @Test
    fun `no item changes when a field it did not declare goes missing`() {
        for (p in plans) {
            val before = items(full, p)
            for (f in Field.entries) {
                val after = items(without(f), p)
                before.zip(after).forEach { (b, a) ->
                    if (f !in b.reads) assertEquals(b, a, "$p: ${b::class.simpleName} reads $f without declaring it")
                }
            }
        }
    }

    @Test
    fun `every field an item declares really moves it`() {
        for (p in plans) {
            val before = items(full, p)
            for (f in Field.entries) {
                val after = items(without(f), p)
                before.zip(after).forEach { (b, a) ->
                    if (f in b.reads) assertNotEquals(b, a, "$p: ${b::class.simpleName} declares $f but ignores it")
                }
            }
        }
    }

    @Test
    fun `channels are what the layout's items read, and nothing with the dash off`() {
        for (layout in Layout.entries) {
            val want = plans.filter { it.first == layout }.flatMap { items(full, it) }
                .flatMap { it.reads }.flatMap { it.channels }.toSet()
            assertEquals(want, Hud.channels(layout, dashOn = true), "$layout")
            assertEquals(emptySet(), Hud.channels(layout, dashOn = false), "$layout")
        }
        assertEquals(emptySet(), Hud.channels(Layout.OFF, dashOn = true))
    }

    @Test
    fun `minion 1's questions`() {
        // Strip draws the climb rate: keep the barometer on for it.
        assertTrue("climb_mps" in Hud.channels(Layout.STRIP, true))
        // Only the attitude indicator needs the accelerometer's tilt and pitch.
        val attitude = Layout.entries.filter { "pitch_deg" in Hud.channels(it, true) }.toSet()
        assertEquals(setOf(Layout.GUNSIGHT, Layout.STRIP), attitude)
        // Minimal is speed and limit: GPS alone, no sensor at all.
        assertEquals(setOf("speed_ms"), Hud.channels(Layout.MINIMAL, true))
    }
}
