package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.geo.LatLon
import kotlin.math.atan
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ported from apps/navi/tests/test_core.py:51-74 (HeadingMaths), plus the rest
 * of the physics in spec section 07.
 *
 * `headingDelta` across north is the one that would have shipped broken: 359 to
 * 1 degrees is a two-degree right turn. Unwrapped it is -358, which at 1 Hz is
 * -358 deg/s of yaw, which through `leanFrom` is an 89-degree lean every time
 * the bike points north.
 */
class PhysicsTest {
    @Test
    fun `delta wraps across north`() {
        assertEquals(2.0, Physics.headingDelta(359.0, 1.0)!!, 1e-9)
        assertEquals(-2.0, Physics.headingDelta(1.0, 359.0)!!, 1e-9)
        assertEquals(10.0, Physics.headingDelta(10.0, 20.0)!!, 1e-9)
        assertNull(Physics.headingDelta(null, 20.0))
        assertNull(Physics.headingDelta(20.0, null))
    }

    @Test
    fun `a reversal is reported as positive one eighty`() {
        // Not -180: the sign of a full reversal is arbitrary, and returning the
        // negative makes abs() the only safe way to read the channel.
        assertEquals(180.0, Physics.headingDelta(0.0, 180.0))
        assertEquals(180.0, Physics.headingDelta(180.0, 0.0))
    }

    @Test
    fun `lean is the angle the load implies`() {
        // 20 m/s through a 10 deg/s turn: a_lat = 3.49 m/s^2, atan(3.49 / 9.807) = 19.6 degrees.
        val lean = Physics.leanFrom(20.0, 10.0)!!
        assertEquals(Math.toDegrees(atan(20.0 * Math.toRadians(10.0) / Physics.G)), lean, 1e-6)
        assertEquals(19.58, lean, 0.05)
    }

    @Test
    fun `lean is none below walking pace`() {
        // Spec section 07: below 5 m/s lean reports NOTHING rather than a meaningless number.
        assertNull(Physics.leanFrom(2.0, 40.0))
        assertNull(Physics.leanFrom(4.999, 40.0))
        assertNull(Physics.leanFrom(null, 10.0))
        assertNull(Physics.leanFrom(20.0, null))
        assertEquals(5.0, Physics.MIN_LEAN_SPEED_MS)
    }

    @Test
    fun `lateral g is speed times yaw rate over g`() {
        assertEquals(20.0 * (10.0 * PI / 180.0) / 9.80665, Physics.latG(20.0, 10.0)!!, 1e-12)
        assertNull(Physics.latG(null, 10.0))
        assertEquals(9.80665, Physics.G)
    }

    @Test
    fun `rates need two readings and a real interval`() {
        assertEquals(2.0 / Physics.G, Physics.rate(12.0, 10.0, 1.0, Physics.G)!!, 1e-12)
        assertNull(Physics.rate(12.0, null, 1.0))
        assertNull(Physics.rate(12.0, 10.0, 0.0))
        assertEquals(4.0, Physics.rateDeg(358.0, 2.0, 1.0)!!, 1e-9)
        assertNull(Physics.rateDeg(null, 2.0, 1.0))
        assertEquals(5.0, Physics.grade(100.0, 105.0, 100.0)!!, 1e-12)
        assertNull(Physics.grade(100.0, 105.0, 0.0))
        assertNull(Physics.grade(null, 105.0, 100.0))
    }

    @Test
    fun `the ride log measures the earth the way it always has`() {
        // telemetry.py:132-137 uses 6371000.0 and clamps h at 1.0; one
        // thousandth of a degree of latitude is 111.19 m on it.
        assertEquals(111.19492664455873, Physics.haversineM(LatLon(37.8, -122.27), LatLon(37.801, -122.27)), 1e-9)
        assertEquals(0.0, Physics.haversineM(LatLon(37.8, -122.27), LatLon(37.8, -122.27)))
        // Antipodes: h rounds past 1.0 and would be NaN without the clamp.
        assertEquals(PI * 6371000.0, Physics.haversineM(LatLon(0.0, 0.0), LatLon(0.0, 180.0)), 1e-6)
    }

    @Test
    fun `barometric altitude is the international formula`() {
        // Spec section 07: 44330 * (1 - (p / 1013.25)^0.1903).
        assertEquals(0.0, Physics.altitudeFromPressure(1013.25)!!, 1e-9)
        assertEquals(1000.0, Physics.altitudeFromPressure(898.7)!!, 30.0)
        assertNull(Physics.altitudeFromPressure(null))
        assertNull(Physics.altitudeFromPressure(0.0))
        assertNull(Physics.altitudeFromPressure(-3.0))
    }
}
