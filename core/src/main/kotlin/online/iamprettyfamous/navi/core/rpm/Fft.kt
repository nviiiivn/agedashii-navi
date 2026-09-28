package online.iamprettyfamous.navi.core.rpm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex FFT for any length.
 *
 * Replaces dsp.py's hand-written DFT, which was a workaround for having no numpy
 * on the phone rather than a design choice. It computes the same transform,
 *
 *     X[k] = sum over n of x[n] * e^(-2 pi i k n / N)
 *
 * iteratively in radix 2 when N is a power of two, and through Bluestein's
 * chirp-z otherwise, so a window whose decimated length is not a power of two
 * still gets the exact DFT rather than a padded approximation of it.
 *
 * Twiddles are computed directly from the angle, never by repeated
 * multiplication, so rounding does not accumulate across the transform.
 */
object Fft {
    /** |X[k]|^2 for every k in 0 until x.size. Power, not amplitude. */
    fun power(x: DoubleArray): DoubleArray {
        val re = x.copyOf()
        val im = DoubleArray(x.size)
        transform(re, im)
        return DoubleArray(x.size) { re[it] * re[it] + im[it] * im[it] }
    }

    /** In place: (re, im) becomes its forward transform. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == im.size) { "re and im differ in length" }
        val n = re.size
        if (n <= 1) return
        if (n and (n - 1) == 0) radix2(re, im) else bluestein(re, im)
    }

    private fun radix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val half = n / 2
        val cosTable = DoubleArray(half) { cos(2.0 * PI * it / n) }
        val sinTable = DoubleArray(half) { sin(2.0 * PI * it / n) }
        var length = 2
        while (length <= n) {
            val halfLength = length / 2
            val stride = n / length
            var start = 0
            while (start < n) {
                for (k in 0 until halfLength) {
                    val wr = cosTable[k * stride]
                    val wi = -sinTable[k * stride]
                    val a = start + k
                    val b = a + halfLength
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += length
            }
            length = length shl 1
        }
    }

    /**
     * kn = (k^2 + n^2 - (k - n)^2) / 2 turns the DFT into a convolution with the
     * chirp w[j] = e^(-i pi j^2 / N), which a power-of-two FFT can do. j^2 is
     * reduced mod 2N first: the chirp has that period, and a small angle keeps
     * cos and sin exact where j^2 itself would lose digits.
     */
    private fun bluestein(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var m = 1
        while (m < 2 * n - 1) m = m shl 1
        val wr = DoubleArray(n)
        val wi = DoubleArray(n)
        for (k in 0 until n) {
            val angle = PI * ((k.toLong() * k) % (2L * n)) / n
            wr[k] = cos(angle)
            wi[k] = -sin(angle)
        }
        val ar = DoubleArray(m)
        val ai = DoubleArray(m)
        for (k in 0 until n) {
            ar[k] = re[k] * wr[k] - im[k] * wi[k]
            ai[k] = re[k] * wi[k] + im[k] * wr[k]
        }
        val br = DoubleArray(m)
        val bi = DoubleArray(m)
        br[0] = wr[0]
        bi[0] = -wi[0]
        for (k in 1 until n) {
            br[k] = wr[k]
            bi[k] = -wi[k]
            br[m - k] = wr[k]
            bi[m - k] = -wi[k]
        }
        radix2(ar, ai)
        radix2(br, bi)
        for (i in 0 until m) {
            val r = ar[i] * br[i] - ai[i] * bi[i]
            val v = ar[i] * bi[i] + ai[i] * br[i]
            ar[i] = r
            ai[i] = -v // conjugate, so the forward transform below runs as an inverse
        }
        radix2(ar, ai)
        for (k in 0 until n) {
            val cr = ar[k] / m
            val ci = -ai[k] / m
            re[k] = cr * wr[k] - ci * wi[k]
            im[k] = cr * wi[k] + ci * wr[k]
        }
    }
}
