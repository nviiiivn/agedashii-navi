package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.gears.Gears
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import online.iamprettyfamous.navi.core.rpm.ExhaustListener
import online.iamprettyfamous.navi.core.telemetry.GpsClimb
import online.iamprettyfamous.navi.core.telemetry.Motion
import online.iamprettyfamous.navi.core.telemetry.Ride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The contract with the ride service: ride.py `_hud_state()`, with its two fixes. */
class DashStateTest {
    private fun build(
        row: Map<String, Any?>? = null,
        motion: Motion? = null,
        climb: GpsClimb? = null,
        ear: ExhaustListener? = null,
        speed: Double? = null,
        alt: Double? = null,
    ) = DashState.build(row, motion, climb, ear, speed, alt, "cb750", true, SpeedLimit.NONE)

    @Test
    fun `nothing known is nothing shown, in every layout`() {
        val bare = build()
        assertEquals(DashState(machine = "cb750"), bare)
        for (layout in Layout.entries) {
            val hud = Hud.plan(bare, layout) ?: continue
            // Every number is a dash; only units and labels remain.
            val numbers = hud.texts().filter { t -> t.any { it.isDigit() } }
            assertEquals(emptyList(), numbers, "$layout invented $numbers")
        }
    }

    @Test
    fun `the horizon gets the CALIBRATED tilt and pitch, not the raw ones`() {
        val motion = Motion()
        motion.onAccelerometer(0.0, 1.7, 9.65, t = 1000.0) // phone mounted ~10 degrees over
        assertEquals(10.0, build(motion = motion).tiltDeg!!, 0.05)
        motion.zero() // her "set level here"
        val state = build(motion = motion)
        assertEquals(0.0, state.tiltDeg!!, 1e-9)
        assertEquals(0.0, state.pitchDeg!!, 1e-9)
    }

    @Test
    fun `gunsight's roll needle draws when the phone disagrees with the lean`() {
        // The Python's never did: it read tilt_deg from a state that only had roll_deg.
        val motion = Motion()
        motion.onAccelerometer(0.0, 0.0, 9.80665, t = 1000.0) // phone level
        val state = build(row = mapOf("lean_deg" to 30.0), motion = motion)
        val ball = Hud.plan(state, Layout.GUNSIGHT)!!.groups.flatMap { it.items }.filterIsInstance<HorizonItem>().single()
        assertEquals(0.0, ball.horizon.needleDeg!!, 1e-9)
        assertEquals(-30.0, ball.horizon.rotationDeg)
    }

    @Test
    fun `lean and g come from the ride row, recording or not`() {
        val ride = Ride(path = null)
        ride.start(1000.0)
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 20.0, heading = 0.0)
        ride.addFix(lat = 37.801, lon = -122.27, t = 1001.0, speedMs = 19.0, heading = 10.0)
        val state = build(row = ride.samples.last(), speed = 19.0)
        assertEquals(ride.samples.last()["lean_deg"], state.leanDeg)
        assertEquals(ride.samples.last()["g_long"], state.gLong)
        assertTrue(state.gLong!! < 0)
    }

    @Test
    fun `the barometer wins for height and climb, the GPS stands in without one`() {
        val climb = GpsClimb()
        for (i in 0..30) climb.add(100.0 + i, t = 1000.0 + i)
        val noBaro = build(climb = climb, alt = 130.0)
        assertEquals(130.0, noBaro.altM)
        assertEquals(climb.climbMps, noBaro.climbMps)
        val motion = Motion()
        motion.onBarometer(1013.25, t = 1000.0)
        motion.onBarometer(1013.25 * Math.pow(1.0 - 4.0 / 44330.0, 5.255), t = 1002.0)
        val withBaro = build(motion = motion, climb = climb, alt = 130.0)
        assertEquals(motion.altBaroM, withBaro.altM)
        assertEquals(motion.climbMps, withBaro.climbMps)
    }

    @Test
    fun `gear and shift are derived together, so they cannot disagree with the rpm`() {
        val ear = ExhaustListener(machine = "cb750")
        ear.listening = true
        val hz = 5000.0 * 2.0 / 60.0
        val note = FloatArray(4096) { i ->
            (0.5 * Math.sin(2 * Math.PI * hz * i / 8000) + 0.35 * Math.sin(2 * Math.PI * 2 * hz * i / 8000) + 0.2 * Math.sin(2 * Math.PI * 3 * hz * i / 8000)).toFloat()
        }
        ear.onSamples(note, t = 1000.0)
        val speed = Gears.speedFor(3, ear.rpmEst!!, "cb750")!!
        val state = build(ear = ear, speed = speed)
        assertEquals(3, state.gear)
        assertEquals(Gears.shiftHint(state.rpm, 3, "cb750"), state.shift)
        assertEquals(ear.engineDb, state.levelDb)
        assertEquals(ear.enginePeak, state.levelPeak)
    }

    @Test
    fun `a posted limit and a typical one reach the dash labelled apart`() {
        val posted = DashState(speedMs = 20.0, limit = Guidance.speedLimit(35, "residential"))
        val typical = DashState(speedMs = 20.0, limit = Guidance.speedLimit(null, "residential"))
        val p = Hud.plan(posted, Layout.MINIMAL)!!.groups.single().items.single() as SpeedBlock
        val t = Hud.plan(typical, Layout.MINIMAL)!!.groups.single().items.single() as SpeedBlock
        assertEquals("limit 35", p.limit)
        assertEquals("limit 25", t.limit)
        assertTrue(p.limitSource != t.limitSource)
        assertNull(Hud.plan(posted, Layout.OFF))
    }
}
