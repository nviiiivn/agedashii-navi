package online.iamprettyfamous.navi.core.rpm

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.doubles
import online.iamprettyfamous.navi.core.obj
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from apps/navi/tests/test_core.py:388-433 (TheSignalProcessing), plus
 * value-for-value checks against the Python in golden/rpm.json.
 */
class DspTest {
    private fun wave(hz: Double, rate: Double, n: Int, amp: Double = 1.0) =
        DoubleArray(n) { i -> amp * sin(2 * PI * hz * i / rate) }

    @Test
    fun `decimation reduces the rate and the work`() {
        val (narrow, rate) = Dsp.decimate(wave(50.0, 8000.0, 4096), 8000.0)
        assertEquals(1000.0, rate)
        assertEquals(512, narrow.size)
    }

    @Test
    fun `decimation leaves an already slow signal alone`() {
        val (narrow, rate) = Dsp.decimate(DoubleArray(100) { 0.1 }, 500.0)
        assertEquals(500.0, rate)
        assertEquals(100, narrow.size)
    }

    @Test
    fun `the high pass removes a slow drift and keeps the note`() {
        val drift = wave(2.0, 8000.0, 4096, 0.5)
        val note = wave(120.0, 8000.0, 4096, 0.5)
        assertTrue(Dsp.rms(Dsp.highpass(drift, 8000.0)) < Dsp.rms(drift) * 0.25)
        assertTrue(Dsp.rms(Dsp.highpass(note, 8000.0)) > Dsp.rms(note) * 0.7)
    }

    @Test
    fun `contrast separates a tone from noise`() {
        val tone = wave(100.0, 1000.0, 512)
        // The Python's own noise, random.seed(3), replayed exactly. Not a fresh
        // Kotlin draw: 15 of 200 such windows pass the gate in the Python too -
        // see ExhaustTest's KNOWN WEAKNESS.
        val noise = Golden.load("rpm.json").arr("spectra").map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "noise" }.getValue("samples").doubles()
        val toneBins = Dsp.spectrum(tone, 1000.0, 20.0, 400.0)
        val noiseBins = Dsp.spectrum(noise, 1000.0, 20.0, 400.0)
        assertTrue(Dsp.contrast(toneBins, 100.0) > 50.0)
        assertTrue(Dsp.contrast(noiseBins, Dsp.harmonicPick(noiseBins) ?: 100.0) < Exhaust.MIN_CONTRAST)
    }

    @Test
    fun `a hann window is zero at the edges`() {
        val window = Dsp.hann(64)
        assertEquals(0.0, window.first(), 1e-12)
        assertEquals(0.0, window.last(), 1e-12)
        // Not exactly 1.0 for an even length: the peak falls between the two
        // middle samples.
        assertEquals(1.0, window.max(), 0.005)
    }

    @Test
    fun `nothing useful returns none rather than a number`() {
        assertEquals(emptyList(), Dsp.spectrum(DoubleArray(0), 8000.0, 20.0, 200.0))
        assertEquals(emptyList(), Dsp.spectrum(DoubleArray(8) { 0.1 }, 8000.0, 20.0, 200.0))
        assertNull(Dsp.harmonicPick(emptyList()))
        assertNull(Dsp.harmonicPick(listOf(Bin(50.0, 0.0), Bin(60.0, 0.0))))
        assertEquals(0.0, Dsp.contrast(emptyList(), 100.0))
        assertEquals(0.0, Dsp.contrast(listOf(Bin(50.0, 1.0)), null))
        assertEquals(-90.0, Dsp.toDb(0.0))
    }

    @Test
    fun `a band with nothing in it has no energy`() {
        val bins = listOf(Bin(10.0, 1.0), Bin(20.0, 2.0), Bin(30.0, 4.0))
        assertEquals(7.0, Dsp.bandEnergy(bins))
        assertEquals(6.0, Dsp.bandEnergy(bins, 15.0, null))
        assertEquals(2.0, Dsp.bandEnergy(bins, 15.0, 25.0))
        assertEquals(listOf("a" to 1.0, "b" to 6.0), Dsp.bandLevels(bins, listOf(Triple("a", 0.0, 10.0), Triple("b", 11.0, 40.0))))
    }

    // ------------------------------------------------------------- the Python

    @Test
    fun `the filters produce what the Python produced`() {
        val filters = Golden.load("rpm.json").obj("filters")
        val samples = filters.getValue("samples").doubles()
        val high = Dsp.highpass(samples, 8000.0)
        val expectedHigh = filters.getValue("highpass").doubles()
        assertEquals(expectedHigh.size, high.size)
        for (i in high.indices) assertClose(expectedHigh[i], high[i], 1e-9, 1e-12, "highpass[$i]")
        val (narrow, rate) = Dsp.decimate(high, 8000.0)
        assertEquals(filters.getValue("decimated_rate").jsonPrimitive.double, rate)
        val expectedNarrow = filters.getValue("decimated").doubles()
        assertEquals(expectedNarrow.size, narrow.size)
        for (i in narrow.indices) assertClose(expectedNarrow[i], narrow[i], 1e-9, 1e-12, "decimated[$i]")
    }

    @Test
    fun `the spectrum, the pick and the contrast are the Python's`() {
        for (element in Golden.load("rpm.json").arr("spectra")) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val bins = Dsp.spectrum(case.getValue("samples").doubles(), 1000.0, 20.0, 400.0)
            val expected = case.getValue("bins").jsonArray
            assertEquals(expected.size, bins.size, name)
            val peak = expected.maxOf { it.jsonArray[1].jsonPrimitive.double }
            bins.forEachIndexed { i, bin ->
                val pair = expected[i].jsonArray
                assertEquals(pair[0].jsonPrimitive.double, bin.hz, "$name bin $i hz")
                // Bin power to within a billionth of the loudest bin: the FFT and
                // the DFT round differently, and nothing else may differ.
                assertClose(pair[1].jsonPrimitive.double, bin.power, 0.0, peak * 1e-9, "$name bin $i power")
            }
            val pick = Dsp.harmonicPick(bins)
            assertEquals(case["pick"].doubleOrNull(), pick, "$name pick")
            assertClose(case["contrast"].doubleOrNull(), Dsp.contrast(bins, pick ?: 100.0), 1e-5, 0.0, "$name contrast")
        }
    }
}
