package online.iamprettyfamous.navi.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rate of climb for a phone with no barometer, and which source wins.
 *
 * Ported from apps/navi/tests/test_shell.py:1001-1028 and 1070-1074 (LiveState).
 * A Moto G Stylus has no barometer; a Pixel 8 Pro does. The GPS fallback is a
 * least-squares slope over 30 s (live.py:45-48, 307-330), not the barometer's
 * 6 s endpoint difference - GPS altitude wanders five to ten metres, so two
 * endpoints can disagree by ten while thirty points describe a flat road.
 */
class ClimbTest {
    @Test
    fun `a phone with no barometer still gets a climb rate`() {
        // Climbing 30 m over 30 s of fixes is 1 m/s.
        val climb = GpsClimb()
        for (i in 0..30) climb.add(100.0 + i, t = 1000.0 + i)
        assertEquals(1.0, climb.climbMps!!, 0.15)
        assertEquals(130.0, Climb.bestAltitude(baroAltM = null, gpsAltM = 130.0))
    }

    @Test
    fun `the barometer wins when there is one`() {
        val climb = GpsClimb()
        for (i in 0..30) climb.add(100.0 + i, t = 1000.0 + i)
        assertEquals(-2.5, Climb.best(baroMps = -2.5, gpsMps = climb.climbMps))
        assertEquals(88.0, Climb.bestAltitude(baroAltM = 88.0, gpsAltM = 130.0))
        assertEquals(climb.climbMps, Climb.best(baroMps = null, gpsMps = climb.climbMps))
    }

    @Test
    fun `noisy gps altitude does not invent a climb`() {
        // A flat road with +-8 m of wander must read about zero, which is why the
        // slope is least squares and not (last - first) / span.
        val wander = listOf(0, 8, -8, 6, -6, 4, -4, 2, -2, 0, 5, -5, 3, -3, 0)
        val climb = GpsClimb()
        wander.forEachIndexed { i, offset -> climb.add(100.0 + offset, t = 1000.0 + i) }
        assertEquals(0.0, climb.climbMps!!, 0.5)
    }

    @Test
    fun `too few fixes is none rather than a wild number`() {
        val climb = GpsClimb()
        climb.add(100.0, t = 1000.0)
        climb.add(140.0, t = 1001.0)
        assertNull(climb.climbMps)
        assertEquals(4, GpsClimb.MIN_SAMPLES)
    }

    @Test
    fun `only the last thirty seconds count`() {
        // Flat for twenty seconds, then 1 m/s: by t=1050 the flat part has left the window.
        val climb = GpsClimb()
        for (i in 0..50) climb.add(if (i < 20) 100.0 else 100.0 + (i - 20), t = 1000.0 + i)
        assertEquals(1.0, climb.climbMps!!, 1e-9)
        assertEquals(30.0, GpsClimb.WINDOW_S)
    }

    @Test
    fun `a fix with no altitude changes nothing`() {
        val climb = GpsClimb()
        for (i in 0..10) climb.add(100.0 + i, t = 1000.0 + i)
        val before = climb.climbMps
        assertNull(climb.add(null, t = 1011.0))
        assertEquals(before, climb.climbMps)
    }
}

/** live.py:36-43, 193-203 and 268-282: the per-fix rules that are not the ride log's. */
class LiveRulesTest {
    @Test
    fun `an inaccurate fix moves nothing`() {
        // Matches the ride log's own threshold on purpose: a 200 m error under a
        // bridge must not be good enough to declare her off route either.
        assertFalse(Live.accepts(accM = 120.0))
        assertTrue(Live.accepts(accM = 50.0))
        assertTrue(Live.accepts(accM = null))
        assertEquals(50.0, Live.MAX_ACC_M)
    }

    @Test
    fun `rolling is reported so autorecord can start`() {
        assertFalse(Live.rolling(0.5))
        assertTrue(Live.rolling(9.0))
        assertTrue(Live.rolling(2.5))
        assertFalse(Live.rolling(null))
    }

    @Test
    fun `a knock becomes an incident that says how hard`() {
        assertEquals("impact 2.4 g", Live.knockIncident(2.4))
        assertEquals("impact 0.0 g", Live.knockIncident(0.0))
    }
}
