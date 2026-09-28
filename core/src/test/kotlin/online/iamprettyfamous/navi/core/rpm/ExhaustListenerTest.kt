package online.iamprettyfamous.navi.core.rpm

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live half of audio.py (`on_stream`, `latest`, `set_machine`, `stop`),
 * which the Python only ever tested through Flet. Every assertion here is a
 * rule written in audio.py:241-397, cited per test.
 */
class ExhaustListenerTest {
    private lateinit var ear: ExhaustListener

    @BeforeTest
    fun setUp() {
        ear = ExhaustListener(machine = "cb750")
    }

    private fun note(rpm: Double, n: Int = Exhaust.WINDOW_SAMPLES, amp: Double = 1.0): FloatArray {
        val wave = engineNote(rpm, "cb750", n = n)
        return FloatArray(n) { (wave[it] * amp).toFloat() }
    }

    @Test
    fun `no reading means no columns rather than zeroes`() {
        // test_shell.py:700-702.
        assertEquals(emptyMap(), ear.latest())
        ear.listening = true
        assertEquals(emptyMap(), ear.latest())
    }

    @Test
    fun `audio reports nothing while not listening`() {
        // test_shell.py:745-747.
        ear.onSamples(note(4000.0), t = 1000.0)
        assertNotNull(ear.loudnessDb)
        assertEquals(emptyMap(), ear.latest())
    }

    @Test
    fun `loudness updates on every chunk and the spectrum waits for a full window`() {
        // audio.py:249-263.
        ear.listening = true
        ear.onSamples(note(4000.0, n = 1024), t = 1000.0)
        assertNotNull(ear.loudnessDb)
        assertNull(ear.rpmEst, "half a window is not enough to pick from")
        ear.onSamples(note(4000.0, n = 4096), t = 1000.5)
        assertEquals(4000.0, ear.rpmEst!!, 250.0)
        val latest = ear.latest()
        assertEquals(setOf("loudness_db", "engine_db", "rpm_est", "exhaust_hz"), latest.keys)
        assertTrue(latest["rpm_est"] is Long, "rpm_est is a whole number, as audio.py:376 rounds it")
    }

    @Test
    fun `the spectrum runs at most once a second`() {
        // audio.py:262 - RPM_EVERY_S.
        ear.listening = true
        ear.onSamples(note(4000.0), t = 1000.0)
        val first = ear.rpmEst
        ear.onSamples(note(6000.0), t = 1000.4)
        assertEquals(first, ear.rpmEst, "a second analysis inside a second")
        ear.onSamples(note(6000.0), t = 1001.1)
        assertEquals(6000.0, ear.rpmEst!!, 400.0)
    }

    @Test
    fun `silence clears the reading and says why`() {
        // audio.py:265-271. The Python wrote "too quiet" to STATE["rejected"], a
        // key nothing reads, instead of STATE["rpm_rejected"] - a typo. Ported as
        // intended, to rpmRejected.
        ear.listening = true
        ear.onSamples(note(4000.0), t = 1000.0)
        assertNotNull(ear.rpmEst)
        ear.onSamples(FloatArray(4096), t = 1001.5)
        assertNull(ear.rpmEst)
        assertNull(ear.rpmHz)
        assertEquals("too quiet", ear.rpmRejected)
    }

    @Test
    fun `a rejected pick leaves the last good reading standing`() {
        // audio.py:282-287: one bad window in a corner must not blank the gauge.
        ear.listening = true
        ear.onSamples(note(2000.0), t = 1000.0)
        val good = ear.rpmEst
        assertNotNull(good)
        ear.onSamples(note(8500.0), t = 1001.5) // a 6500 rpm jump in a second
        assertEquals(good, ear.rpmEst)
        assertTrue("jump" in ear.rpmRejected)
    }

    @Test
    fun `changing the bike forgets the continuity reference`() {
        // audio.py:385-392.
        ear.listening = true
        ear.onSamples(note(2000.0), t = 1000.0)
        assertNotNull(ear.rpmEst)
        ear.setMachine("ex250")
        assertNull(ear.rpmEst)
        assertNull(ear.rpmHz)
        assertEquals("ex250", ear.machine)
    }

    @Test
    fun `stopping blanks the gauges`() {
        // audio.py:346-352.
        ear.listening = true
        ear.onSamples(note(4000.0), t = 1000.0)
        ear.stopped()
        assertFalse(ear.listening)
        assertNull(ear.rpmEst)
        assertNull(ear.loudnessDb)
        assertNull(ear.loudnessNorm)
    }

    @Test
    fun `peaks hold until reset`() {
        ear.listening = true
        ear.onSamples(note(4000.0), t = 1000.0)
        ear.onSamples(note(4000.0, amp = 0.01), t = 1000.2)
        assertTrue(ear.loudnessPeak > (ear.loudnessNorm ?: 0.0))
        ear.resetPeaks()
        assertEquals(0.0, ear.loudnessPeak)
        assertEquals(0.0, ear.enginePeak)
    }

    @Test
    fun `the recorder's state names turn listening on and off`() {
        // audio.py:290-293.
        ear.onStateChange("RecordState.RECORDING")
        assertTrue(ear.listening)
        ear.onStateChange("RecordState.STOPPED")
        assertFalse(ear.listening)
    }

    @Test
    fun `raw pcm bytes go through the same door`() {
        ear.listening = true
        val floats = note(4000.0)
        val bytes = ByteArray(floats.size * 2)
        floats.forEachIndexed { i, f ->
            val s = (f * 16000).toInt()
            bytes[2 * i] = (s and 0xFF).toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        ear.onPcm16(bytes, t = 1000.0)
        assertEquals(1, ear.chunks)
        assertEquals(4000.0, ear.rpmEst!!, 250.0)
    }
}
