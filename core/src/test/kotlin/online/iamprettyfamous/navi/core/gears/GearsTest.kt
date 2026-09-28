package online.iamprettyfamous.navi.core.gears

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Speed over engine speed is a fixed ratio per gear. Nothing else needed.
 *
 * Ported from apps/navi/tests/test_shell.py:559-617 (TheGearEstimate), plus the
 * spec's sanity check from section 09.
 */
class GearsTest {
    @Test
    fun `each gear is recognised from its own ratio`() {
        for (machine in listOf("cb750", "ex250")) {
            Gears.RATIOS.getValue(machine).forEachIndexed { index, ratio ->
                val rpm = 5000.0
                val speed = ratio * (rpm / 60.0)
                val got = Gears.estimate(speed, rpm, machine)
                assertEquals(index + 1, got.gear, "$machine gear ${index + 1}")
                assertTrue(got.confidence > 0.9, "$machine gear ${index + 1}")
            }
        }
    }

    @Test
    fun `a four and a twin read the same numbers differently`() {
        assertNotEquals(Gears.estimate(20.0, 5000.0, "cb750").gear, Gears.estimate(20.0, 5000.0, "ex250").gear)
    }

    @Test
    fun `no engine speed means no gear rather than a guess`() {
        // With the microphone off there is no RPM, and speed alone answers a
        // different question.
        assertEquals(GearEstimate.NONE, Gears.estimate(20.0, null, "cb750"))
        assertEquals(GearEstimate.NONE, Gears.estimate(20.0, 0.0, "cb750"))
    }

    @Test
    fun `a slipping clutch reports nothing`() {
        assertEquals(GearEstimate.NONE, Gears.estimate(1.0, 4000.0, "cb750"))
        assertEquals(GearEstimate.NONE, Gears.estimate(null, 4000.0, "cb750"))
    }

    @Test
    fun `a ratio matching no gear is refused`() {
        assertEquals(GearEstimate.NONE, Gears.estimate(60.0, 1200.0, "cb750"))
    }

    @Test
    fun `the shift light fires near the redline and when lugging`() {
        assertEquals("up", Gears.shiftHint(9000.0, 3, "cb750"))
        assertEquals("down", Gears.shiftHint(1800.0, 3, "cb750"))
        assertEquals("", Gears.shiftHint(5000.0, 3, "cb750"))
    }

    @Test
    fun `the shift light never says up in top gear`() {
        val top = Gears.gearCount("cb750")
        assertEquals("", Gears.shiftHint(9400.0, top, "cb750"))
    }

    @Test
    fun `it never says down in first`() {
        assertEquals("", Gears.shiftHint(1500.0, 1, "cb750"))
    }

    @Test
    fun `no gear or no engine speed lights nothing`() {
        assertEquals("", Gears.shiftHint(null, 3, "cb750"))
        assertEquals("", Gears.shiftHint(9000.0, null, "cb750"))
    }

    @Test
    fun `what gear you should be in needs no microphone`() {
        // Her "or even just show 'what gear you should be at'".
        val low = Gears.bestGearFor(8.0, "cb750")
        val high = Gears.bestGearFor(30.0, "cb750")
        assertNotNull(low)
        assertNotNull(high)
        assertTrue(high > low)
    }

    @Test
    fun `ratios learned from samples land near the table`() {
        val machine = "cb750"
        val samples = mutableListOf<Pair<Double?, Double?>>()
        for (ratio in Gears.RATIOS.getValue(machine)) {
            for (rpm in listOf(3000.0, 4500.0, 6000.0)) {
                samples.add(ratio * (rpm / 60.0) to rpm)
            }
        }
        val learned = Gears.learn(samples, Gears.gearCount(machine))
        assertEquals(Gears.gearCount(machine), learned.size)
        learned.zip(Gears.RATIOS.getValue(machine)).forEach { (found, known) ->
            assertEquals(known, found, known * 0.05)
        }
    }

    @Test
    fun `the cb750 at 5000 rpm does what that bike actually does`() {
        // Spec section 09. An earlier table was 10x too small, which put every
        // gear below the clutch floor. First gear is exactly 27.59 mph: gears.py:63
        // truncates that to 27 and the spec rounds it to 28. Same number - do not
        // "fix" this assertion back to 27.
        val mph = (1..5).map { gear ->
            (Gears.speedFor(gear, 5000.0, "cb750")!! * 2.2369362920544).roundToLong()
        }
        assertEquals(listOf(28L, 38L, 48L, 56L, 64L), mph)
        assertEquals(27.59, Gears.speedFor(1, 5000.0, "cb750")!! * 2.2369362920544, 0.005)
    }

    @Test
    fun `speed for is the inverse of the estimate`() {
        for (gear in 1..Gears.gearCount("ex250")) {
            val speed = Gears.speedFor(gear, 6000.0, "ex250")!!
            assertEquals(gear, Gears.estimate(speed, 6000.0, "ex250").gear)
        }
        assertEquals(null, Gears.speedFor(0, 5000.0, "cb750"))
        assertEquals(null, Gears.speedFor(6, 5000.0, "cb750"))
        assertEquals(null, Gears.speedFor(null, 5000.0, "cb750"))
        assertEquals(null, Gears.speedFor(2, 0.0, "cb750"))
    }

    @Test
    fun `an unknown bike uses the cb750 table`() {
        assertEquals(Gears.RATIOS.getValue("cb750"), Gears.table("harley"))
        assertEquals(Gears.RATIOS.getValue("cb750"), Gears.table(null))
    }
}
