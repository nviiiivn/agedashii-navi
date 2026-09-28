package online.iamprettyfamous.navi.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * The microphone, for the rev counter: 8 kHz mono 16-bit PCM in 100 ms chunks.
 *
 * Exhaust fundamentals sit well under 1 kHz, so 8 kHz is plenty (spec §08). The
 * source is UNPROCESSED where the phone offers it - no noise suppression or gain
 * control, which would flatten exactly the engine note being measured (rpm-boss's
 * finding) - and VOICE_RECOGNITION, the least-processed standard source,
 * where it does not.
 *
 * Off unless she switches it on (spec rule 5). This class only captures; the
 * chunks go to the ride thread, where the core's ExhaustListener reads them.
 */
class Mic(private val context: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Which source was actually opened, for Settings' honest account. */
    @Volatile var source: String = ""
        private set

    /**
     * Starts capture. [onChunk] runs on the capture thread with a fresh copy of
     * each chunk and its unix time; [onError] with a reason she can read. Returns
     * whether capture started.
     */
    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray, Double) -> Unit, onError: (String) -> Unit): Boolean {
        if (running) return true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            onError("this phone cannot record at $RATE Hz")
            return false
        }
        val record = try {
            AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
        } catch (e: RuntimeException) {
            onError("microphone unavailable: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("microphone would not start (another app may be using it)")
            return false
        }
        source = if (unprocessed) "unprocessed" else "voice recognition (the phone offers no unprocessed source)"
        running = true
        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (running) onError("microphone stopped delivering sound ($n)")
                        break
                    }
                    onChunk(buf.copyOf(n), System.currentTimeMillis() / 1000.0)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "capture failed", e)
                onError("microphone failed: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val TAG = "NaviMic"
        const val RATE = 8000
        // 800 samples x 2 bytes: 100 ms.
        const val CHUNK_BYTES = 1600
    }
}
