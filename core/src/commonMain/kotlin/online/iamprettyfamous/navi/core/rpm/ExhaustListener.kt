package online.iamprettyfamous.navi.core.rpm

import online.iamprettyfamous.navi.core.Py
import kotlin.math.max

/**
 * The live microphone state: fed PCM as it arrives, read once a second.
 *
 * Ported from audio.py's `on_stream`, `on_state_change`, `latest`, `set_machine`
 * and `stop`, minus Flet. The app's recorder hands chunks to [onPcm16] or
 * [onSamples] with the time they arrived; nothing here reads a clock or knows
 * what a recorder is. Not thread-safe: feed and read it from one thread.
 */
class ExhaustListener(machine: String? = null, private val sampleRate: Int = Exhaust.SAMPLE_RATE) {
    /** Which bike is being listened to. Change it through [setMachine], which also drops the continuity reference. */
    var machine: String? = machine
        private set

    /** Everything the microphone hears, wind included, in dBFS. Kept because clipping matters regardless. */
    var loudnessDb: Double? = null
        private set
    var loudnessNorm: Double? = null
        private set
    var loudnessPeak: Double = 0.0
        private set

    /** The engine's band alone - the one that tracks the throttle. */
    var engineDb: Double? = null
        private set
    var engineNorm: Double? = null
        private set
    var enginePeak: Double = 0.0
        private set

    /** The multi-band meter, (label, 0.0-1.0). */
    var bands: List<Pair<String, Double>> = emptyList()
        private set
    var rpmEst: Double? = null
        private set
    var rpmHz: Double? = null
        private set
    var rpmAt: Double = 0.0
        private set

    /** When the standing [rpmEst] was last ACCEPTED, so a gauge can show its age; null with no reading. */
    var rpmAcceptedAt: Double? = null
        private set

    /** Why the last pick was thrown away, if it was. */
    var rpmRejected: String = ""
        private set
    var chunks: Int = 0
        private set
    var listening: Boolean = false
    var error: String = ""

    private val buffer = FloatArray(Exhaust.WINDOW_SAMPLES)
    private var filled = 0

    fun onPcm16(chunk: ByteArray, t: Double) = onSamples(Exhaust.pcm16ToFloats(chunk), t)

    /** A chunk. Loudness every time, the spectrum at most once per [Exhaust.RPM_EVERY_S]. */
    fun onSamples(samples: FloatArray, t: Double) {
        if (samples.isEmpty()) return
        chunks += 1

        val dbfs = Exhaust.loudnessDbfs(samples)
        loudnessDb = dbfs
        val norm = Exhaust.normalise(dbfs)
        loudnessNorm = norm
        if (norm != null && norm > loudnessPeak) loudnessPeak = norm

        append(samples)
        if (t - rpmAt < Exhaust.RPM_EVERY_S || filled < Exhaust.WINDOW_SAMPLES) return
        rpmAt = t
        if (dbfs != null && dbfs < Exhaust.QUIET_DBFS) {
            // Silence has no fundamental. Say nothing rather than picking the
            // loudest bin of the noise floor. (audio.py:270 wrote this reason to
            // an unread key; it belongs here.)
            clearRpm()
            rpmRejected = Exhaust.REJECT_QUIET
            return
        }
        val got = Exhaust.analyse(window(), sampleRate.toDouble(), machine, previousRpm = rpmEst)
        engineDb = got.engineDb
        engineNorm = Exhaust.normalise(got.engineDb)
        engineNorm?.let { enginePeak = max(enginePeak, it) }
        bands = got.bands
        rpmRejected = got.rejected
        // A rejected pick leaves the previous reading standing: one bad window in
        // a corner should not wipe a figure that was right a second ago. But only
        // for RPM_HOLD_S - past that the figure is old, and a gauge showing it
        // would claim a certainty it does not have (the Python held it forever).
        if (got.rpm != null) {
            rpmEst = got.rpm
            rpmHz = got.hz
            rpmAcceptedAt = t
        } else {
            val acceptedAt = rpmAcceptedAt
            if (acceptedAt == null || t - acceptedAt > Exhaust.RPM_HOLD_S) clearRpm()
        }
    }

    private fun clearRpm() {
        rpmEst = null
        rpmHz = null
        rpmAcceptedAt = null
    }

    /** The recorder's state, by name: listening while it records and has not stopped. */
    fun onStateChange(state: String) {
        val lower = state.lowercase()
        listening = "record" in lower && "stop" !in lower
    }

    /**
     * The phone's own voice is speaking: what the microphone hears now is the
     * prompt, not the engine, and a spoken "turn left" has harmonics enough to
     * pass for an exhaust note. Blank the reading, say why, and forget the
     * window, so the next analysis is made from engine alone.
     */
    fun pausedForVoice() {
        clearRpm()
        loudnessDb = null
        loudnessNorm = null
        engineDb = null
        engineNorm = null
        bands = emptyList()
        filled = 0
        rpmRejected = Exhaust.REJECT_VOICE
    }

    /** The recorder started. */
    fun started() {
        listening = true
        error = ""
    }

    /** The recorder stopped: the gauges go blank rather than freezing on the last reading. */
    fun stopped() {
        listening = false
        clearRpm()
        loudnessDb = null
        loudnessNorm = null
    }

    /**
     * Which bike is being listened to. The previous reading was in the other
     * engine's terms, so it must not be the continuity reference for the next.
     */
    fun setMachine(machine: String?) {
        this.machine = machine
        clearRpm()
    }

    fun resetPeaks() {
        loudnessPeak = 0.0
        enginePeak = 0.0
    }

    /**
     * The audio channels, named for the ride log. Empty while not listening, and
     * a key only when there is a reading behind it. exhaust_hz travels with
     * rpm_est: the ratio between them depends on the bike profile, and a log
     * with only the rpm could not be recomputed if the profile was wrong.
     */
    fun latest(): Map<String, Any> {
        if (!listening) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        loudnessDb?.let { out["loudness_db"] = Py.round(it, 1) }
        engineDb?.let { out["engine_db"] = Py.round(it, 1) }
        rpmEst?.let { out["rpm_est"] = Py.roundToLong(it) }
        rpmHz?.let { out["exhaust_hz"] = Py.round(it, 2) }
        return out
    }

    private fun append(samples: FloatArray) {
        val size = Exhaust.WINDOW_SAMPLES
        val n = samples.size
        if (n >= size) {
            samples.copyInto(buffer, 0, n - size, n)
            filled = size
            return
        }
        val overflow = filled + n - size
        if (overflow > 0) {
            buffer.copyInto(buffer, 0, overflow, filled)
            filled -= overflow
        }
        samples.copyInto(buffer, filled, 0, n)
        filled += n
    }

    private fun window(): DoubleArray = DoubleArray(filled) { buffer[it].toDouble() }
}
