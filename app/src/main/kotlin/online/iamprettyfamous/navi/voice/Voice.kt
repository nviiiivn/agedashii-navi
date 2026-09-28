package online.iamprettyfamous.navi.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as TtsVoice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/**
 * Spoken turns, through the phone's own text-to-speech - offline voices only,
 * because the phone has no SIM and a network voice is silence on the road.
 * Everything here runs on the main thread; [say] and [quiet] are safe from any.
 *
 * - Her music ducks and comes back: transient focus, MAY_DUCK, as navigation
 *   guidance, never a stop.
 * - Prompts never stack. A newer one flushes the one being spoken: a stale
 *   instruction heard late is worse than silence.
 * - [quiet] is true while it speaks and for [TAIL_MS] after, so the rev counter
 *   does not read the voice as an engine.
 * - With no offline voice installed it says so, in [State.why], in words she
 *   can act on - never a silent failure.
 */
object Voice {
    private const val TAG = "NaviVoice"
    private const val CHECK = "check:"
    private const val MIN_CHECK_BYTES = 1024L

    /** After the last word: the room's echo and the mic's own buffering. */
    const val TAIL_MS = 800L

    data class State(
        val ready: Boolean = false,
        /** Why nothing is spoken, when nothing can be. */
        val why: String = "starting the phone's voice",
        /** The voice in use, for Settings' honest account. */
        val voice: String = "",
        val speaking: Boolean = false,
        val last: String = "",
        /** The last prompt failed to come out, and why: said on screen, not swallowed. */
        val failed: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var current: String? = null
    private var seq = 0
    private var candidates: List<TtsVoice> = emptyList()
    private lateinit var checkFile: File

    /** Her Settings switch; the prompts are still worked out, just not said. */
    @Volatile var enabled = true

    @Volatile private var speakingNow = false
    @Volatile private var quietUntil = 0L

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Start the engine once. Main thread. */
    fun start(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        checkFile = File(app.cacheDir, "voice-check.wav")
        tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            _state.value = State(why = "this phone has no text-to-speech engine - install one (Google Speech Services) to hear turns")
            return
        }
        t.setAudioAttributes(attributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                Log.d(TAG, "$id speaking")
            }
            override fun onDone(id: String) = ended(id, null)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = ended(id, TextToSpeech.ERROR)
            override fun onError(id: String, errorCode: Int) = ended(id, errorCode)
            override fun onStop(id: String, interrupted: Boolean) = finished(id, "cut off by a newer prompt", null)
        })
        candidates = offlineVoices(t)
        Log.i(TAG, "offline English voices: ${candidates.joinToString { it.name }}; engine default ${t.defaultVoice?.name}")
        if (candidates.isEmpty()) {
            _state.value = State(
                why = "no offline voice installed - on the phone: Settings, search \"text-to-speech\", " +
                    "then install the English voice data. Turns are shown, not spoken, until then",
            )
            return
        }
        tryVoice(0)
    }

    /**
     * A voice listed as installed is not proof it speaks: on the emulator the
     * first one listed failed every prompt ("Unable to open project file",
     * error -4) while claiming to be installed. So each is made to say one
     * word into a file first, and the first that does is the one used.
     */
    private fun tryVoice(i: Int) {
        val t = tts ?: return
        if (i >= candidates.size) {
            _state.value = State(
                why = "the phone's voice would not speak (${candidates.size} offline voices tried) - " +
                    "Settings, search \"text-to-speech\", and download the English voice again",
            )
            return
        }
        t.voice = candidates[i]
        _state.value = State(why = "checking the phone's voice")
        // A file left by the last voice must not pass for this one's.
        checkFile.delete()
        if (t.synthesizeToFile("Navi", Bundle(), checkFile, "$CHECK$i") != TextToSpeech.SUCCESS) {
            main.post { tryVoice(i + 1) }
        }
    }

    private fun ended(id: String, error: Int?) {
        if (!id.startsWith(CHECK)) return finished(id, if (error == null) "done" else "error $error", error)
        main.post {
            val i = id.removePrefix(CHECK).toInt()
            val v = candidates[i]
            // "Done" is not proof either: minion 3 found voices that finish
            // cleanly having written 0 bytes (iob, iog, iol, iom on the
            // emulator). One word is kilobytes of audio; a bare header is 44.
            val bytes = checkFile.length()
            if (error != null || bytes < MIN_CHECK_BYTES) {
                Log.w(TAG, "${v.name} would not speak: ${error?.let(::errorName) ?: "$bytes bytes of audio"}")
                return@post tryVoice(i + 1)
            }
            _state.value = State(ready = true, why = "", voice = "${v.name} (${v.locale.toLanguageTag()}, offline)")
            Log.i(TAG, "ready: ${v.name} ($bytes bytes for one word)")
        }
    }

    /**
     * Installed voices that need no network, English (the prompts are written
     * in English): the engine's own default first, then her country's, best
     * quality first.
     */
    private fun offlineVoices(t: TextToSpeech): List<TtsVoice> {
        val usable = t.voices.orEmpty().filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val here = Locale.getDefault().country
        val default = t.defaultVoice?.name
        return usable.sortedWith(
            compareByDescending<TtsVoice> { it.name == default }
                .thenByDescending { it.locale.country == here }
                .thenByDescending { it.quality },
        )
    }

    private fun errorName(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "the voice could not make the words"
        TextToSpeech.ERROR_SERVICE -> "the text-to-speech service failed"
        TextToSpeech.ERROR_OUTPUT -> "no audio output"
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "the voice wanted the network"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "the voice is not downloaded yet"
        else -> "error $code"
    }

    /** Say [text] now, cutting off anything still being said. Any thread. */
    fun say(text: String) {
        main.post {
            val t = tts
            if (!enabled || t == null || !_state.value.ready) {
                Log.i(TAG, "not said (${if (!enabled) "voice off" else _state.value.why}): $text")
                return@post
            }
            val id = "p${++seq}"
            current = id
            speakingNow = true
            holdFocus()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            _state.value = _state.value.copy(speaking = true, last = text)
            Log.i(TAG, "say: $text")
        }
    }

    /** True while the voice speaks and for [TAIL_MS] after. Any thread. */
    fun quiet(): Boolean = speakingNow || SystemClock.elapsedRealtime() < quietUntil

    private fun finished(id: String, how: String, error: Int?) {
        main.post {
            Log.d(TAG, "$id $how")
            // An utterance flushed by a newer one ends too; only the newest counts.
            if (id != current) return@post
            current = null
            speakingNow = false
            quietUntil = SystemClock.elapsedRealtime() + TAIL_MS
            dropFocus()
            _state.value = _state.value.copy(
                speaking = false,
                failed = error?.let { "the last prompt failed: ${errorName(it)}" } ?: "",
            )
        }
    }

    private fun holdFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        // Speak whether or not focus is granted: a turn she misses because a
        // call holds the audio is lost either way, and the engine mixes over.
        val got = audio.requestAudioFocus(request)
        Log.d(TAG, "focus: transient may-duck, navigation guidance -> $got")
        if (got != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "audio focus not granted ($got)")
        focus = request
    }

    private fun dropFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
