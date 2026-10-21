package online.iamprettyfamous.navi.core.rpm

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finding an engine in a microphone.
 *
 * Ported from apps/navi/agedashii_navi/dsp.py, with its hand-written DFT replaced
 * by [Fft]. Her suggestion is the shape of it: "a peak EQ to really hone in on
 * the frequency that engine noise is most predominant ... cutting out the lows
 * entirely and most of the highs ... you could probably even do a multi-peak
 * meter". For the pitch, only bins inside the engine's band are ever searched.
 * For the meter it changes everything: [bandEnergy] tracks the throttle instead
 * of the wind, and [bandLevels] is the multi-band meter, free, because the
 * spectrum is already computed.
 *
 * Why harmonic product and not the loudest bin: an exhaust note is a
 * fundamental plus a stack of harmonics, and often the second harmonic is the
 * loudest single component, so the loudest bin returns double the engine speed.
 * Multiplying the spectrum by its own decimations reinforces a true fundamental
 * with every harmonic above it; an isolated loud harmonic is not reinforced, and
 * broadband wind multiplies down to nothing.
 */
object Dsp {
    /** What the signal is decimated to before the transform, in Hz. Twice the top of any engine band here. */
    const val DECIMATE_TO_HZ = 1000.0

    /** How many harmonics the product folds in. Three kills the octave error; more multiplies in wind. */
    const val HARMONICS = 3

    /**
     * A candidate must hold this fraction of the band's strongest bin to be a
     * fundamental at all. See [harmonicPick]: without it 9000 rpm read as 2988.
     */
    const val MIN_CANDIDATE_FRACTION = 0.02

    /** One-pole high-pass cutoff. Below this there is no engine, only the phone moving in its mount. */
    const val HIGHPASS_HZ = 18.0

    // Copy-on-write rather than ConcurrentHashMap, which is JVM-only. Two
    // threads asking for a new length at once both compute it; the window is
    // the same either way, and lengths are few.
    @Volatile private var hannCache: Map<Int, DoubleArray> = emptyMap()

    /**
     * A Hann window of length n. Without one the transform sees the buffer's
     * hard edges as a step, which smears every peak across its neighbours.
     */
    fun hann(n: Int): DoubleArray = hannShared(n).copyOf()

    private fun hannShared(n: Int): DoubleArray = hannCache[n] ?: run {
        val w = if (n > 1) DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)) } else DoubleArray(1) { 1.0 }
        hannCache = hannCache + (n to w)
        w
    }

    fun rms(samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        var total = 0.0
        for (s in samples) total += s * s
        return sqrt(total / samples.size)
    }

    /**
     * A one-pole high-pass. Removes road rumble and mount thump, which otherwise
     * dominate the low bins and drag the band energy up with them. One multiply
     * per sample; the job is removing a nuisance, not meeting a specification.
     */
    fun highpass(samples: DoubleArray, rate: Double, cutoffHz: Double = HIGHPASS_HZ): DoubleArray {
        if (samples.isEmpty() || rate <= 0.0) return samples.copyOf()
        val dt = 1.0 / rate
        val tau = 1.0 / (2.0 * PI * cutoffHz)
        val alpha = tau / (tau + dt)
        val out = DoubleArray(samples.size)
        var prevIn = samples[0]
        var prevOut = 0.0
        for (i in samples.indices) {
            val sample = samples[i]
            prevOut = alpha * (prevOut + sample - prevIn)
            prevIn = sample
            out[i] = prevOut
        }
        return out
    }

    /**
     * (samples, rate) reduced towards [targetHz] by box-car averaging. The
     * average IS the anti-alias filter: a crude sinc low-pass whose first null
     * sits at the new rate, far above the band searched. Untouched when already
     * at or below the target.
     */
    fun decimate(samples: DoubleArray, rate: Double, targetHz: Double = DECIMATE_TO_HZ): Pair<DoubleArray, Double> {
        if (samples.isEmpty() || rate <= targetHz) return samples.copyOf() to rate
        val factor = max(1, floor(rate / targetHz).toInt())
        if (factor == 1) return samples.copyOf() to rate
        val count = if (samples.size >= factor) (samples.size - factor) / factor + 1 else 0
        val out = DoubleArray(count)
        for (block in 0 until count) {
            var total = 0.0
            val start = block * factor
            for (i in start until start + factor) total += samples[i]
            out[block] = total / factor
        }
        return out to rate / factor
    }

    /**
     * The bins inside a band, as (hz, power). The one expensive call. Power, not
     * amplitude: the harmonic product multiplies these, and the square is the
     * right quantity for energy.
     */
    fun spectrum(samples: DoubleArray, rate: Double, loHz: Double, hiHz: Double, window: Boolean = true): List<Bin> {
        val n = samples.size
        if (n < 32 || rate <= 0.0 || hiHz <= loHz) return emptyList()
        val step = rate / n
        val lo = max(1, (loHz / step).toInt())
        val hi = min(n / 2, (hiHz / step).toInt() + 1)
        if (hi <= lo) return emptyList()
        val data = if (window) {
            val weights = hannShared(n)
            DoubleArray(n) { samples[it] * weights[it] }
        } else {
            samples
        }
        val power = Fft.power(data)
        return (lo until hi).map { k -> Bin(k * step, power[k]) }
    }

    /**
     * The fundamental in a spectrum, by harmonic product, or null.
     *
     * Each candidate's power is multiplied by the power at 2x, 3x ... its
     * frequency. A harmonic beyond the band contributes the band's median rather
     * than zero, so a fundamental near the top is not disqualified for having
     * its harmonics out of the window.
     *
     * That substitution opened a real hole: at 9000 rpm on the CB750 every
     * harmonic of the 300 Hz fundamental is above the band, so candidate 100 Hz
     * scored median x median x (real energy at 300) and won - 2988 rpm. Hence
     * the floor: a candidate must hold [floorFraction] of the strongest bin. 2% of
     * peak power is about 14% of peak amplitude - low enough to keep a real
     * fundamental quieter than its own second harmonic, high enough to exclude
     * the noise floor.
     */
    fun harmonicPick(
        bins: List<Bin>,
        harmonics: Int = HARMONICS,
        floorFraction: Double = MIN_CANDIDATE_FRACTION,
    ): Double? {
        if (bins.isEmpty()) return null
        val strongest = bins.maxOf { it.power }
        if (strongest <= 0.0) return null
        val median = medianPower(bins).let { if (it == 0.0) 1e-12 else it }
        val lowest = bins[0].hz
        val span = bins.last().hz - lowest
        if (span <= 0.0 || bins.size < 2) return bins[0].hz
        val stride = span / (bins.size - 1)
        val floor = strongest * floorFraction

        fun powerAt(hz: Double): Double {
            val index = kotlin.math.round((hz - lowest) / stride).toInt()
            return if (index in bins.indices) bins[index].power else median
        }

        var bestHz: Double? = null
        var bestScore = 0.0
        for ((hz, power) in bins) {
            if (power < floor) continue
            var score = power
            for (harmonic in 2..harmonics) score *= powerAt(hz * harmonic)
            if (score > bestScore) {
                bestScore = score
                bestHz = hz
            }
        }
        return bestHz
    }

    /**
     * How far the power at [hz] stands above the band's background, as a ratio
     * against the median bin. This separates an engine from wind: given only
     * broadband noise the harmonic product still has a largest value, so
     * [harmonicPick] still answers, and the answer looks real. On uniform white
     * noise this is NOT near 1: its median is about 4 and one window in twenty
     * passes 8 (see [Exhaust.MIN_CONTRAST]). An unbaffled four measures orders of
     * magnitude above.
     *
     * Returns 0.0 when there is nothing to measure, which no threshold passes.
     */
    fun contrast(bins: List<Bin>, hz: Double?): Double {
        if (bins.isEmpty() || hz == null) return 0.0
        val median = medianPower(bins)
        if (median <= 0.0) return 0.0
        val lowest = bins[0].hz
        val stride = if (bins.size > 1) (bins.last().hz - lowest) / (bins.size - 1) else 1.0
        val index = if (stride != 0.0) kotlin.math.round((hz - lowest) / stride).toInt() else 0
        if (index !in bins.indices) return 0.0
        // The neighbours count too: a Hann window spreads a pure tone across
        // three bins, so the centre alone understates a real peak by about a third.
        var loudest = 0.0
        for (i in max(0, index - 1) until min(bins.size, index + 2)) loudest = max(loudest, bins[i].power)
        return loudest / median
    }

    /**
     * Total power in a band of an already-computed spectrum - what the loudness
     * meter should have shown all along, because it excludes the airflow.
     */
    fun bandEnergy(bins: List<Bin>, loHz: Double? = null, hiHz: Double? = null): Double {
        var total = 0.0
        for ((hz, power) in bins) {
            if (loHz != null && hz < loHz) continue
            if (hiHz != null && hz > hiHz) continue
            total += power
        }
        return total
    }

    /** (label, power) for consecutive bands. Her multi-peak meter, from one spectrum. */
    fun bandLevels(bins: List<Bin>, edges: List<Triple<String, Double, Double>>): List<Pair<String, Double>> =
        edges.map { (label, lo, hi) -> label to bandEnergy(bins, lo, hi) }

    /** Power to decibels, floored at -90 rather than running to -infinity. */
    fun toDb(power: Double, reference: Double = 1.0): Double {
        if (power <= 0.0 || reference <= 0.0) return -90.0
        return max(-90.0, 10.0 * log10(power / reference))
    }

    /** `sorted(powers)[len // 2]`, which for an even count is the upper middle. */
    private fun medianPower(bins: List<Bin>): Double {
        val powers = DoubleArray(bins.size) { bins[it].power }
        powers.sort()
        return powers[powers.size / 2]
    }
}

/** One spectrum bin: its centre frequency and its power. */
data class Bin(val hz: Double, val power: Double)
