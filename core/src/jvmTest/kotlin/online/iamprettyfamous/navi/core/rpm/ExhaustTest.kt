package online.iamprettyfamous.navi.core.rpm

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.doubles
import online.iamprettyfamous.navi.core.str
import online.iamprettyfamous.navi.core.bikes.Bikes
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A synthetic exhaust note: a fundamental and two harmonics, plus wind. test_core.py:256-264. */
fun engineNote(
    rpm: Double,
    machine: String,
    rate: Int = 8000,
    n: Int = 4096,
    harmonics: List<Pair<Int, Double>> = listOf(1 to 0.5, 2 to 0.35, 3 to 0.2),
    noise: Double = 0.0,
    seed: Int = 7,
): DoubleArray {
    val hz = Bikes.hzFromRpm(rpm, machine)!!
    val random = Random(seed)
    return DoubleArray(n) { i ->
        var sum = 0.0
        for ((h, amp) in harmonics) sum += amp * sin(2 * PI * hz * h * i / rate)
        sum + if (noise != 0.0) random.nextDouble(-noise, noise) else 0.0
    }
}

/**
 * The detector, against synthetic notes. This is the whole RPM claim.
 *
 * Ported from apps/navi/tests/test_core.py:324-385 (FindingTheEngine) and the
 * audio half of test_shell.py:695-747 (SensorPlumbing). Python's `random`
 * cannot be reproduced here, so the noisy cases use Kotlin's; golden/rpm.json
 * then replays Python's exact noise to prove the answers are the same.
 */
class ExhaustTest {
    @Test
    fun `it reads the rev range of both bikes`() {
        for ((machine, speeds) in listOf(
            "cb750" to listOf(1500, 3000, 5000, 6500, 9000),
            "ex250" to listOf(1500, 4000, 8000, 11000, 13000),
        )) {
            for (rpm in speeds) {
                val got = Exhaust.analyse(engineNote(rpm.toDouble(), machine), 8000.0, machine)
                assertNotNull(got.rpm, "$machine@$rpm: ${got.rejected}")
                assertEquals(rpm.toDouble(), got.rpm, rpm * 0.06, "$machine@$rpm")
            }
        }
    }

    @Test
    fun `a loud second harmonic does not double the answer`() {
        // The octave error: a peak-pick returned 8027 for an engine at 4000.
        val trap = engineNote(4000.0, "cb750", harmonics = listOf(1 to 0.2, 2 to 0.9, 3 to 0.3))
        val got = Exhaust.analyse(trap, 8000.0, "cb750")
        assertEquals(4000.0, got.rpm!!, 250.0)
    }

    @Test
    fun `the top of the range is not read as a third of itself`() {
        // The sub-harmonic trap: this used to report 2988 for 9000.
        val got = Exhaust.analyse(engineNote(9000.0, "cb750"), 8000.0, "cb750")
        assertEquals(9000.0, got.rpm!!, 400.0)
    }

    @Test
    fun `wind is refused rather than answered`() {
        // Without the contrast gate, broadband noise returned a confident 3691.
        // The Python's own noise (random.seed(1), uniform +-0.6), replayed exactly.
        val wind = Golden.load("rpm.json").arr("analyse").map { it.jsonObject }.single { it.str("name") == "pure wind" }
        val got = Exhaust.analyse(wind.getValue("samples").doubles(), 8000.0, "cb750")
        assertNull(got.rpm)
        assertTrue("no clear fundamental" in got.rejected, got.rejected)
    }

    @Test
    fun `KNOWN WEAKNESS - about one window of pure wind in twenty still reads as an engine`() {
        // Found while porting, and it is the Python's, not the port's: measured
        // in the Python on 2026-09-27, 10 of 200 windows of uniform +-0.6 noise
        // (random.seed 0-199) produced an rpm, and seed 0 reads 3691 at contrast
        // 9.49 - the very figure audio.py:196 says this gate was added to stop.
        // The Python test passes only because it happens to use seed 1. The port
        // matches the Python value for value (see the golden test), so this is
        // inherited. Raising MIN_CONTRAST was put to her on 2026-09-27 and she
        // chose to keep 8.0 - she would rather switch the microphone off than
        // lose RPM at idle. So this is now a known, chosen trade-off, and it
        // stays labelled so the next reader learns it here, not from a ride.
        //
        // A sweep, not one seed: one seed passing is exactly how this hid. The
        // ceiling of 15 of 200 sits above the measured 10 (Python) and 11
        // (Kotlin) for noise variation, and far below what a broken gate reads.
        var answered = 0
        for (seed in 0 until 200) {
            val random = Random(seed)
            val noise = DoubleArray(4096) { random.nextDouble(-0.6, 0.6) }
            if (Exhaust.analyse(noise, 8000.0, "cb750").rpm != null) answered++
        }
        println("pure wind answered in $answered of 200 windows (Python: 10 of 200)")
        assertTrue(answered <= 15, "pure wind answered in $answered of 200 windows")
    }

    @Test
    fun `an engine survives heavy wind`() {
        for (seed in listOf(7, 8, 9)) {
            val got = Exhaust.analyse(engineNote(5000.0, "cb750", noise = 0.9, seed = seed), 8000.0, "cb750")
            assertNotNull(got.rpm, "seed $seed: ${got.rejected}")
            assertEquals(5000.0, got.rpm, 400.0)
        }
    }

    @Test
    fun `an implausible jump is thrown away`() {
        val got = Exhaust.analyse(engineNote(8000.0, "cb750"), 8000.0, "cb750", previousRpm = 1200.0)
        assertNull(got.rpm)
        assertTrue("jump" in got.rejected)
    }

    @Test
    fun `a previous reading of zero is no reading`() {
        // audio.py:206 - `if previous_rpm:` treats 0 as absent.
        val got = Exhaust.analyse(engineNote(8000.0, "cb750"), 8000.0, "cb750", previousRpm = 0.0)
        assertNotNull(got.rpm)
    }

    @Test
    fun `the band meter shows where the energy is`() {
        val got = Exhaust.analyse(engineNote(2000.0, "cb750"), 8000.0, "cb750")
        val bands = got.bands.toMap()
        assertEquals(3, bands.size)
        // 2000 rpm on the four is 67 Hz, near the bottom of a 25-396 Hz band.
        assertTrue(bands.getValue("low") >= bands.getValue("high"))
        assertEquals(1.0, bands.values.max(), 1e-6)
    }

    @Test
    fun `it is fast enough to run once a second`() {
        val note = engineNote(4000.0, "cb750")
        Exhaust.analyse(note, 8000.0, "cb750") // first call pays for class loading
        val started = System.nanoTime()
        Exhaust.analyse(note, 8000.0, "cb750")
        assertTrue((System.nanoTime() - started) / 1e9 < 0.5)
    }

    @Test
    fun `silence has no spectrum to read`() {
        val got = Exhaust.analyse(DoubleArray(16), 8000.0, "cb750")
        assertEquals(Analysis(), got)
    }

    // ------------------------------------------------------------ PCM + level

    @Test
    fun `pcm decoding and loudness`() {
        // Full negative and full positive scale: RMS 1.0, which is 0 dBFS.
        val floats = Exhaust.pcm16ToFloats(byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F))
        assertEquals(2, floats.size)
        assertEquals(0.0, Exhaust.loudnessDbfs(floats)!!, 0.1)
    }

    @Test
    fun `silence is ninety down not negative infinity`() {
        assertEquals(-90.0, Exhaust.loudnessDbfs(FloatArray(64)))
        assertNull(Exhaust.loudnessDbfs(FloatArray(0)))
    }

    @Test
    fun `an odd trailing byte is dropped rather than raising`() {
        assertEquals(1, Exhaust.pcm16ToFloats(byteArrayOf(1, 2, 3)).size)
        assertEquals(0, Exhaust.pcm16ToFloats(byteArrayOf(1)).size)
        assertEquals(0, Exhaust.pcm16ToFloats(ByteArray(0)).size)
    }

    @Test
    fun `the meter spans a quiet room to a loud pipe`() {
        // audio.py:139-148: -60 dBFS is empty, -6 is full.
        assertEquals(0.0, Exhaust.normalise(-70.0))
        assertEquals(0.0, Exhaust.normalise(-60.0))
        assertEquals(1.0, Exhaust.normalise(-6.0)!!, 1e-12)
        assertEquals(1.0, Exhaust.normalise(0.0))
        assertNull(Exhaust.normalise(null))
    }

    // ------------------------------------------------------------- the Python

    @Test
    fun `every answer is the Python's answer`() {
        for (element in Golden.load("rpm.json").arr("analyse")) {
            val case = element.jsonObject
            val name = case.str("name")
            val got = Exhaust.analyse(
                case.getValue("samples").doubles(),
                case.getValue("rate").jsonPrimitive.double,
                case.str("machine"),
                previousRpm = case["previous_rpm"].doubleOrNull(),
            )
            assertEquals(case["hz"].doubleOrNull(), got.hz, "$name hz")
            assertEquals(case["rpm"].doubleOrNull(), got.rpm, "$name rpm")
            assertEquals(case.str("rejected"), got.rejected, "$name rejected")
            assertClose(case["contrast"].doubleOrNull(), got.contrast, 1e-5, 0.0, "$name contrast")
            assertClose(case["engine_db"].doubleOrNull(), got.engineDb, 1e-9, 1e-9, "$name engine_db")
            val bands = case.getValue("bands").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.double }
            assertEquals(bands.map { it.first }, got.bands.map { it.first }, "$name band labels")
            bands.zip(got.bands).forEach { (want, have) -> assertClose(want.second, have.second, 1e-9, 1e-12, "$name band ${want.first}") }
        }
    }
}
