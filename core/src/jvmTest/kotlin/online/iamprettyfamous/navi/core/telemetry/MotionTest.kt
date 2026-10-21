package online.iamprettyfamous.navi.core.telemetry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The motion sensors, as numbers in and channels out.
 *
 * Ported from the sensor half of apps/navi/tests/test_shell.py:695-728
 * (SensorPlumbing) and 1049-1068 (LiveState's compass tests), and from the rules
 * in sensors.py that the Python only tested through Flet. The app feeds raw
 * SensorManager values here; nothing in this class knows what a SensorEvent is.
 */
class MotionTest {
    private lateinit var motion: Motion

    @BeforeTest
    fun setUp() {
        motion = Motion()
    }

    @Test
    fun `no reading means no columns rather than zeroes`() {
        assertEquals(emptyMap(), motion.latest())
    }

    @Test
    fun `an accelerometer reading fills the orientation channels`() {
        motion.onAccelerometer(0.0, 0.0, 9.80665, t = 1000.0)
        val got = motion.latest()
        assertEquals(1.0, got["g_vert"] as Double, 0.005)
        // Flat on its back: roll 0, pitch 0.
        assertEquals(0.0, got["tilt_deg"] as Double, 0.05)
        assertEquals(0.0, got["pitch_deg"] as Double, 0.05)
        assertEquals(setOf("g_vert", "g_peak", "tilt_deg", "pitch_deg"), got.keys)
    }

    @Test
    fun `orientation survives more than one g`() {
        // sensors.py:181-183: atan2 rather than asin, which would be NaN the moment
        // a pothole took the magnitude past gravity.
        motion.onAccelerometer(-15.0, 0.0, 9.80665, t = 1000.0)
        val pitch = motion.latest()["pitch_deg"] as Double
        assertFalse(pitch.isNaN())
        assertEquals(Math.toDegrees(Math.atan2(15.0, 9.80665)), pitch, 0.05)
    }

    @Test
    fun `peaks hold until reset`() {
        motion.onUserAccelerometer(0.0, 0.0, 19.6)
        motion.onUserAccelerometer(0.0, 0.0, 0.0)
        motion.onAccelerometer(0.0, 0.0, 9.80665, t = 1000.0)
        assertTrue(motion.latest()["g_bump"] as Double > 1.5, "the bump channel is the window's peak")
        motion.resetPeaks()
        assertEquals(0.0, motion.latest()["g_bump"] as Double, 1e-9)
    }

    @Test
    fun `the barometer reports altitude and a climb rate`() {
        motion.onBarometer(1013.25, t = 1000.0)
        val got = motion.latest()
        assertEquals(0.0, got["alt_baro_m"] as Double, 0.5)
        assertEquals(1013.25, got["pressure_hpa"])
        assertNull(got["climb_mps"], "one reading has no rate")
        // 12 m up in 4 s: 3 m/s. (last - first) / span over the 6 s window,
        // sensors.py:265-275 - NOT least squares; the spec was wrong about that.
        motion.onBarometer(pressureAt(4.0), t = 1002.0)
        motion.onBarometer(pressureAt(12.0), t = 1004.0)
        assertEquals(3.0, motion.climbMps!!, 0.05)
        assertEquals(12.0, motion.relativeM!!, 0.05)
    }

    @Test
    fun `the climb window forgets readings older than six seconds`() {
        motion.onBarometer(pressureAt(0.0), t = 1000.0)
        motion.onBarometer(pressureAt(100.0), t = 1001.0) // a spike, then flat
        motion.onBarometer(pressureAt(100.0), t = 1007.5)
        motion.onBarometer(pressureAt(100.0), t = 1008.0)
        assertEquals(0.0, motion.climbMps!!, 1e-6)
    }

    @Test
    fun `a missing sensor is recorded and not raised`() {
        motion.onError("barometer", "no such sensor")
        assertTrue("barometer" in motion.available().errors)
        assertEquals("no such sensor", motion.available().errors["barometer"])
    }

    @Test
    fun `the phone's upright can be reset and forgotten`() {
        // Her ask: "a 'reset' sensors ... recalibrate what is 'home' or 'straight'".
        assertNull(motion.zero(), "no accelerometer yet, so nothing was calibrated")
        motion.onAccelerometer(0.0, 1.7, 9.65, t = 1000.0)
        val zeroed = motion.zero()
        assertNotNull(zeroed)
        assertEquals(0.0, motion.latest()["tilt_deg"] as Double, 1e-9)
        assertTrue(motion.zeroed().set)
        motion.unzero()
        assertFalse(motion.zeroed().set)
        assertEquals(10.0, motion.latest()["tilt_deg"] as Double, 0.05)
    }

    @Test
    fun `the gyroscope reports degrees per second, like the channel beside it`() {
        // sensors.py:443-447: mixing rad/s and deg/s is how a lean comes out 57x too big.
        motion.onGyroscope(0.0, 0.0, 0.5)
        val got = motion.latest()
        assertEquals(Math.toDegrees(0.5), got["yaw_gyro_dps"] as Double, 0.005)
        assertEquals(Math.toDegrees(0.5), got["gyro_peak"] as Double, 0.005)
    }

    @Test
    fun `the heading falls back to the compass when stopped`() {
        // Without this the marker freezes at the last moving course.
        motion.onMagnetometer(30.0, 0.0, 0.0)
        assertEquals(0.0, motion.heading(gpsHeading = 90.0, speedMs = 0.2)!!, 0.05)
    }

    @Test
    fun `gps course wins while actually moving`() {
        motion.onMagnetometer(30.0, 0.0, 0.0)
        assertEquals(90.0, motion.heading(gpsHeading = 90.0, speedMs = 14.0))
    }

    @Test
    fun `a magnetised mount is refused rather than believed`() {
        // Earth's field is 25-65 uT; a speaker magnet reads hundreds.
        motion.onMagnetometer(900.0, 120.0, 40.0)
        assertNull(motion.compassDeg())
        assertFalse("compass_deg" in motion.latest())
        // With no trustworthy compass, a slow GPS heading is still better than nothing.
        assertEquals(90.0, motion.heading(gpsHeading = 90.0, speedMs = 0.2))
    }

    @Test
    fun `a knock is counted and handed to whoever is listening`() {
        var heard: Pair<Double, Double>? = null
        motion.onShakeListener = { t, force -> heard = t to force }
        motion.onUserAccelerometer(0.0, 0.0, 23.5)
        motion.onShake(t = 1010.0)
        assertEquals(1, motion.shakes)
        assertEquals(1010.0, heard!!.first)
        assertEquals(23.5 / 9.80665, heard!!.second, 1e-9)
    }

    @Test
    fun `a listener that throws does not take the sensor down`() {
        motion.onShakeListener = { _, _ -> error("boom") }
        motion.onShake(t = 1010.0)
        assertEquals(1, motion.shakes)
    }

    @Test
    fun `battery level is reported only once known`() {
        assertFalse("battery_pct" in motion.latest())
        motion.setBatteryPct(71)
        assertEquals(71, motion.latest()["battery_pct"])
    }

    private fun pressureAt(metres: Double): Double =
        Physics.SEA_LEVEL_HPA * Math.pow(1.0 - metres / 44330.0, 5.255)
}
