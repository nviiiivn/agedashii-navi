package online.iamprettyfamous.navi.core.rpm

import online.iamprettyfamous.navi.core.bikes.Bikes
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The exhaust note: loudness now, engine speed if it can be found.
 *
 * Ported from the pure half of apps/navi/agedashii_navi/audio.py. Her ask: "a
 * sort of 'volume' meter slash pseudo rpm". Two confidences, and the difference
 * matters:
 *
 * Loudness is a measurement - RMS of the PCM window in dBFS, exactly as
 * trustworthy as the microphone.
 *
 * RPM is an estimate, and it is allowed to fail. At 70 mph in a helmet wind is
 * broadband and enormous, so [analyse] reports no rpm whenever the band has no
 * clear peak and the gauge shows a dash. A tachometer that invents a plausible
 * figure out of wind noise is worse than no tachometer.
 *
 * The microphone is OFF unless she turns it on. It is a microphone.
 */
object Exhaust {
    /** Mono 8 kHz: the fundamentals sit well under 1 kHz, and higher rates cost CPU for nothing. */
    const val SAMPLE_RATE = 8000
    const val CHANNELS = 1

    /**
     * 4096 at 8 kHz is 512 ms; after decimation to 1 kHz that leaves 512
     * samples and bins just under 2 Hz wide - about 59 rpm on the four.
     */
    const val WINDOW_SAMPLES = 4096

    /** How often the RPM estimate is recomputed. Loudness updates on every chunk. */
    const val RPM_EVERY_S = 1.0

    /**
     * How long a reading stands through REJECTED windows: two of them at
     * [RPM_EVERY_S]. The Python held it forever, so with the engine off in wind,
     * or the phone knocked in its mount, the tach and the gear badge showed a
     * figure from a minute ago with no sign it was old - and, as the continuity
     * reference, that stale figure rejected the real rpm as an "implausible
     * jump" for as long as it stood. The rpm must never look more certain than
     * it is. A deliberate departure: PORTING.md.
     */
    const val RPM_HOLD_S = 2.0

    /** Below this the engine is off or the mic is blocked, and no RPM is claimed. */
    const val QUIET_DBFS = -55.0

    /** Full scale for a 16-bit sample. */
    const val FULL_SCALE = 32768.0

    /**
     * The most an engine can change in one second, as a fraction of its
     * redline. A pick implying more is a bad pick - the octave error is a 100%
     * jump.
     */
    const val MAX_RPM_JUMP_FRACTION = 0.6

    /**
     * How far the fundamental must stand above the band's background, as a
     * POWER RATIO - not decibels. 8.0 is about 9 dB. An unbaffled four measures
     * in the hundreds.
     *
     * Pure noise does NOT measure near 1. Measured 2026-09-27 on 200 windows of
     * uniform white noise (4096 samples, 8 kHz, cb750): p50 3.7-4.1, p95 8.0,
     * p99 11-12, and 10-11 of the 200 still passed and returned an RPM - the
     * Python's random.seed(0) reads 3691 rpm at 9.49. So this rejects most wind,
     * not all of it. That is her deliberate trade: raising it would also reject
     * a quiet engine at idle, and she would rather switch the microphone off.
     * ExhaustTest's KNOWN WEAKNESS pins the rate.
     */
    const val MIN_CONTRAST = 8.0

    /** Her multi-band meter, as fractions of the engine's own band, so it means the same on a twin and a four. */
    val BAND_SPLITS: List<Triple<String, Double, Double>> = listOf(
        Triple("low", 0.0, 0.25),
        Triple("mid", 0.25, 0.6),
        Triple("high", 0.6, 1.0),
    )

    const val REJECT_NO_FUNDAMENTAL = "no clear fundamental - wind, or the engine is off"
    const val REJECT_OUT_OF_RANGE = "outside this engine's rev range"
    const val REJECT_JUMP = "implausible jump from the last reading"
    const val REJECT_QUIET = "too quiet"

    /**
     * Signed 16-bit little-endian bytes to floats in -1.0..1.0. An odd trailing
     * byte is dropped rather than raising: a stream chunk can split a sample.
     */
    fun pcm16ToFloats(chunk: ByteArray): FloatArray {
        val count = chunk.size / 2
        return FloatArray(count) { i ->
            val lo = chunk[2 * i].toInt() and 0xFF
            val hi = chunk[2 * i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            (sample / FULL_SCALE).toFloat()
        }
    }

    /** RMS of a window in dBFS; -90.0 for silence rather than -infinity; null for nothing. */
    fun loudnessDbfs(samples: FloatArray): Double? {
        if (samples.isEmpty()) return null
        var total = 0.0
        for (s in samples) total += s.toDouble() * s.toDouble()
        return dbfsFromRms(sqrt(total / samples.size))
    }

    fun loudnessDbfs(samples: DoubleArray): Double? {
        if (samples.isEmpty()) return null
        var total = 0.0
        for (s in samples) total += s * s
        return dbfsFromRms(sqrt(total / samples.size))
    }

    private fun dbfsFromRms(rms: Double): Double = if (rms <= 1e-6) -90.0 else max(-90.0, 20.0 * log10(rms))

    /**
     * dBFS to 0..1 for a meter, over the span a bike actually occupies: -60 is a
     * quiet room and -6 a loud pipe near the top of the rev range.
     */
    fun normalise(dbfs: Double?): Double? {
        if (dbfs == null) return null
        return max(0.0, min(1.0, (dbfs + 60.0) / 54.0))
    }

    fun analyse(samples: FloatArray, rate: Double, machine: String? = null, previousRpm: Double? = null): Analysis =
        analyse(DoubleArray(samples.size) { samples[it].toDouble() }, rate, machine, previousRpm)

    /**
     * One window in, everything the audio gauges need out. Pure: a test hands it
     * a synthetic note and asserts on the answer, which is the only way the
     * harmonic picking and the octave guard can be proved at all.
     */
    fun analyse(samples: DoubleArray, rate: Double, machine: String? = null, previousRpm: Double? = null): Analysis {
        val (loHz, hiHz) = Bikes.bandHz(machine)
        // High-pass FIRST, at the original rate, where the mount thump still lives.
        val filtered = Dsp.highpass(samples, rate)
        val (narrow, narrowRate) = Dsp.decimate(filtered, rate)
        val bins = Dsp.spectrum(narrow, narrowRate, loHz, hiHz)
        if (bins.isEmpty()) return Analysis()

        // A level, not a total: referenced to the bin count so a longer window
        // does not read louder.
        val engineDb = Dsp.toDb(Dsp.bandEnergy(bins) / max(1, bins.size))

        val span = hiHz - loHz
        val edges = BAND_SPLITS.map { (label, lo, hi) -> Triple(label, loHz + span * lo, loHz + span * hi) }
        val levels = Dsp.bandLevels(bins, edges)
        // Against the loudest band, not an absolute: the meter shows WHERE the
        // energy is, and an absolute scale would sit at the bottom at idle.
        val loudest = (levels.maxOfOrNull { it.second } ?: 0.0).let { if (it == 0.0) 1.0 else it }
        val bands = levels.map { (label, power) -> label to power / loudest }
        val base = Analysis(engineDb = engineDb, bands = bands)

        val hz = Dsp.harmonicPick(bins) ?: return base
        val sharpness = Dsp.contrast(bins, hz)
        if (sharpness < MIN_CONTRAST) return base.copy(contrast = sharpness, rejected = REJECT_NO_FUNDAMENTAL)
        val rpm = Bikes.rpmFromHz(hz, machine)
        if (!Bikes.plausible(rpm, machine)) return base.copy(contrast = sharpness, rejected = REJECT_OUT_OF_RANGE)
        if (previousRpm != null && previousRpm != 0.0) {
            val ceiling = Bikes.redlineRpm(machine) * MAX_RPM_JUMP_FRACTION
            // Almost always an octave error. Nothing mechanical moves this far in a second.
            if (abs(rpm!! - previousRpm) > ceiling) return base.copy(contrast = sharpness, rejected = REJECT_JUMP)
        }
        return base.copy(contrast = sharpness, hz = hz, rpm = rpm)
    }
}

/**
 * What one window says. [rpm] and [hz] are null whenever [rejected] says why;
 * [contrast] is the power ratio the gate compared against [Exhaust.MIN_CONTRAST].
 */
data class Analysis(
    val engineDb: Double? = null,
    val bands: List<Pair<String, Double>> = emptyList(),
    val hz: Double? = null,
    val rpm: Double? = null,
    val rejected: String = "",
    val contrast: Double = 0.0,
)
