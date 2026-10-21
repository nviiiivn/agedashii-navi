package online.iamprettyfamous.navi.core.bikes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ratio that turns an exhaust note into engine speed.
 *
 * Ported from apps/navi/tests/test_core.py:267-321 (TheEngineMaths). Her bikes
 * are a four and a twin, which fire at different rates, so the same note means
 * two different engine speeds.
 */
class BikesTest {
    @Test
    fun `every bike ships uncalibrated, on the firing fundamental`() {
        for ((key, bike) in Bikes.PROFILES) {
            assertEquals(1.0, bike.harmonic, key)
            assertFalse(bike.calibrated, "$key: no recording of it has been made")
        }
    }

    @Test
    fun `a bike whose exhaust sings at the second harmonic reads half the rpm for the same Hz`() {
        val cb = Bikes.profile("cb750")
        val second = cb.copy(harmonic = 2.0, calibratedFrom = "a recording")
        assertEquals(Bikes.rpmFromHz(100.0, cb)!! / 2.0, Bikes.rpmFromHz(100.0, second)!!, 1e-9)
        assertEquals(Bikes.hzFromRpm(4000.0, cb)!! * 2.0, Bikes.hzFromRpm(4000.0, second)!!, 1e-9)
        // The search band follows the harmonic, or the picker would look in the wrong place.
        assertEquals(Bikes.bandHz(cb).second * 2.0, Bikes.bandHz(second).second, 1e-9)
        assertTrue(second.calibrated)
        // The defaults change nothing: the Python's figures, by key or by profile.
        assertEquals(Bikes.rpmFromHz(100.0, "cb750"), Bikes.rpmFromHz(100.0, cb))
    }

    @Test
    fun `a four fires twice per revolution and a twin once`() {
        assertEquals(2.0, Bikes.firingRatio("cb750"))
        assertEquals(1.0, Bikes.firingRatio("ex250"))
    }

    @Test
    fun `the same note is two different engine speeds`() {
        // 100 Hz is 3000 rpm on the four and 6000 on the twin.
        assertEquals(3000.0, Bikes.rpmFromHz(100.0, "cb750")!!, 1e-9)
        assertEquals(6000.0, Bikes.rpmFromHz(100.0, "ex250")!!, 1e-9)
    }

    @Test
    fun `hz and rpm are inverses`() {
        for (machine in Bikes.PROFILES.keys) {
            val hz = Bikes.hzFromRpm(5000.0, machine)
            assertEquals(5000.0, Bikes.rpmFromHz(hz, machine)!!, 1e-6, machine)
        }
    }

    @Test
    fun `the band reaches the redline of every bike`() {
        // The bug this exists to prevent: a fixed 20-200 Hz band was written for
        // a twin and would have capped the CB750 at about 6000 rpm.
        for (machine in Bikes.PROFILES.keys) {
            val (lo, hi) = Bikes.bandHz(machine)
            assertTrue(hi > Bikes.redlineHz(machine), machine)
            val idleHz = Bikes.hzFromRpm(Bikes.profile(machine).idleRpm.toDouble(), machine)!!
            // Never above idle, except where idle is under the 15 Hz floor - a
            // single at 1200 rpm has a 10 Hz fundamental, which is below what a
            // phone microphone reproduces. A real limit of the method.
            assertTrue(lo <= maxOf(15.0, idleHz), machine)
        }
    }

    @Test
    fun `a single cannot be heard at idle and that is admitted`() {
        val idleHz = Bikes.hzFromRpm(Bikes.profile("other").idleRpm.toDouble(), "other")!!
        assertTrue(idleHz < Bikes.bandHz("other").first)
    }

    @Test
    fun `the cb750 band reaches past three hundred hertz`() {
        assertTrue(Bikes.bandHz("cb750").second > 316.0)
    }

    @Test
    fun `an impossible engine speed is refused`() {
        assertFalse(Bikes.plausible(300.0, "cb750"))
        assertFalse(Bikes.plausible(20000.0, "cb750"))
        assertFalse(Bikes.plausible(null, "cb750"))
        assertTrue(Bikes.plausible(4000.0, "cb750"))
        // The twin revs a long way further than the four.
        assertTrue(Bikes.plausible(13000.0, "ex250"))
        assertFalse(Bikes.plausible(13000.0, "cb750"))
    }

    @Test
    fun `an unknown bike falls back rather than raising`() {
        assertEquals(Bikes.profile(Bikes.DEFAULT), Bikes.profile("harley"))
        assertEquals(Bikes.profile(Bikes.DEFAULT), Bikes.profile(null))
        assertEquals(Bikes.profile(Bikes.DEFAULT), Bikes.profile(""))
    }

    @Test
    fun `no frequency is no engine speed rather than zero`() {
        // bikes.py:94 and 101 - `if not hz or hz <= 0: return None`.
        assertEquals(null, Bikes.rpmFromHz(null))
        assertEquals(null, Bikes.rpmFromHz(0.0))
        assertEquals(null, Bikes.rpmFromHz(-5.0))
        assertEquals(null, Bikes.hzFromRpm(0.0))
    }

    @Test
    fun `the published profiles are exactly the spec's`() {
        // Spec section 08, bike table; bikes.py:37-66.
        val cb = Bikes.profile("cb750")
        assertEquals(listOf(4, 4, 1000, 9500), listOf(cb.cylinders, cb.strokes, cb.idleRpm, cb.redlineRpm))
        val ex = Bikes.profile("ex250")
        assertEquals(listOf(2, 4, 1300, 14000), listOf(ex.cylinders, ex.strokes, ex.idleRpm, ex.redlineRpm))
        val other = Bikes.profile("other")
        assertEquals(listOf(1, 4, 1200, 9000), listOf(other.cylinders, other.strokes, other.idleRpm, other.redlineRpm))
        assertEquals(listOf("cb750", "ex250", "other"), Bikes.names().map { it.first })
    }
}
