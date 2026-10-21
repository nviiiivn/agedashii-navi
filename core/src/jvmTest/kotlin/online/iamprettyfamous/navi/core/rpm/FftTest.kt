package online.iamprettyfamous.navi.core.rpm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FFT that replaces dsp.py's hand-written DFT. The Python's DFT was a
 * workaround for having no numpy on the phone, so the one thing to prove here is
 * that the FFT computes the same transform: X[k] = sum x[n] e^(-2 pi i k n / N),
 * for any length, power of two or not.
 */
class FftTest {
    private fun naivePower(x: DoubleArray): DoubleArray {
        val n = x.size
        return DoubleArray(n) { k ->
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val angle = 2.0 * PI * k * i / n
                re += x[i] * cos(angle)
                im -= x[i] * sin(angle)
            }
            re * re + im * im
        }
    }

    private fun assertSameTransform(n: Int, seed: Int) {
        val random = Random(seed)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val expected = naivePower(x)
        val actual = Fft.power(x)
        assertEquals(n, actual.size)
        val scale = expected.max()
        for (k in 0 until n) {
            assertTrue(abs(expected[k] - actual[k]) <= 1e-9 * scale, "n=$n bin $k: ${expected[k]} vs ${actual[k]}")
        }
    }

    @Test
    fun `a power of two matches the direct transform`() {
        for (n in listOf(1, 2, 4, 32, 512, 1024)) assertSameTransform(n, n)
    }

    @Test
    fun `any other length matches it too`() {
        // 500 and 37 go through Bluestein. A window whose decimated length is not
        // a power of two must not silently change the answer.
        for (n in listOf(3, 37, 100, 500, 513)) assertSameTransform(n, n)
    }

    @Test
    fun `a pure tone lands in its own bin`() {
        val n = 512
        val x = DoubleArray(n) { i -> sin(2.0 * PI * 25.0 * i / n) }
        val power = Fft.power(x)
        val peak = (0..n / 2).maxBy { power[it] }
        assertEquals(25, peak)
    }

    @Test
    fun `energy is conserved`() {
        // Parseval: sum |x|^2 = sum |X|^2 / N.
        val random = Random(11)
        val x = DoubleArray(500) { random.nextDouble(-1.0, 1.0) }
        val time = x.sumOf { it * it }
        val frequency = Fft.power(x).sum() / x.size
        assertEquals(time, frequency, time * 1e-12)
    }

    @Test
    fun `nothing in is nothing out`() {
        assertEquals(0, Fft.power(DoubleArray(0)).size)
    }
}
