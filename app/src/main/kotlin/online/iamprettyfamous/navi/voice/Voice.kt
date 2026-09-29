package online.iamprettyfamous.navi.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.voice.Character
import online.iamprettyfamous.navi.core.voice.VoiceLines
import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
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
 * - Her recorded lines come first ([prompt], VoiceLines): a prompt is a
 *   sequence of her clips and the phone's words, played as ONE utterance under
 *   one focus hold, and a clip that will not play is replaced by its own words.
 * - Her character ([character]): wild lines per slot, a flourish LAST, a rare
 *   cough in the join. Those two are soft: any newer prompt cuts them at once.
 */
object Voice {
    private const val TAG = "NaviVoice"
    private const val CHECK = "check:"
    private const val MIN_CHECK_BYTES = 1024L
    private const val CHECK_TIMEOUT_MS = 8000L
    /**
     * A prompt held while the voice is checked is said once it is ready, if no
     * older than this. Tight on purpose: the nearest prompt fires ~6 s before
     * the turn, and an instruction heard after its turn is worse than none.
     */
    private const val PENDING_FRESH_MS = 3000L
    private const val STARTING = "starting the phone's voice"
    private const val CHECKING = "checking the phone's voice"

    /** After the last word: the room's echo and the mic's own buffering. */
    const val TAIL_MS = 800L

    data class State(
        val ready: Boolean = false,
        /** Why nothing is spoken, when nothing can be. */
        val why: String = STARTING,
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
    private var checking = -1
    private var pending: Pending? = null
    private lateinit var checkFile: File
    private lateinit var app: Context

    /** A prompt held while the voice starts or is checked, and when it came. */
    private class Pending(val items: List<Item>, val label: String, val at: Long)

    /**
     * A newer prompt that came while one was playing: it starts when the item
     * now playing ends, replacing the rest of that prompt - never cutting a
     * word off. Only the newest waits; a "now" prompt does not wait at all.
     */
    private var queued: Pending? = null

    /** What the current prompt plays, one item after another, and which is playing. */
    private var sequence: List<Item> = emptyList()
    private var step = 0
    private var player: MediaPlayer? = null

    /** Her Settings switch; the prompts are still worked out, just not said. */
    @Volatile var enabled = true

    /** Her switch for the street name after her own line: off by default, or a robot finishes her joke. */
    @Volatile var streets = false

    /** Wild lines, flourishes and the cough, with her two switches (Settings > Your recorded voice). */
    val character = Character()

    @Volatile private var speakingNow = false
    @Volatile private var quietUntil = 0L

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Start the engine once. Main thread. */
    fun start(context: Context) {
        if (tts != null) return
        app = context.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        checkFile = File(app.cacheDir, "voice-check.wav")
        tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
        // An engine that never answers must not leave "starting" up forever.
        main.postDelayed({
            if (_state.value.why == STARTING) {
                _state.value = State(why = "the phone's voice did not start - Settings, search \"text-to-speech\", and check an engine is chosen")
            }
        }, CHECK_TIMEOUT_MS)
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
        checking = i
        _state.value = State(why = CHECKING)
        // A file left by the last voice must not pass for this one's.
        checkFile.delete()
        if (t.synthesizeToFile("Navi", Bundle(), checkFile, "$CHECK$i") != TextToSpeech.SUCCESS) {
            main.post { tryVoice(i + 1) }
            return
        }
        // A voice that never calls back is a voice that does not speak.
        main.postDelayed({
            if (checking == i) {
                Log.w(TAG, "${candidates[i].name} would not speak: no answer in $CHECK_TIMEOUT_MS ms")
                tryVoice(i + 1)
            }
        }, CHECK_TIMEOUT_MS)
    }

    private fun ended(id: String, error: Int?) {
        if (!id.startsWith(CHECK)) return finished(id, if (error == null) "done" else "error $error", error)
        main.post {
            val i = id.removePrefix(CHECK).toInt()
            // Late: already timed out and moved on.
            if (i != checking) return@post
            checking = -1
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
            // A prompt that came while the voice was being checked: the Follower
            // has already counted it said, so it is said now - if still fresh.
            val waiting = pending
            pending = null
            if (waiting != null && SystemClock.elapsedRealtime() - waiting.at < PENDING_FRESH_MS) play(waiting.items, waiting.label)
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
    fun say(text: String) = play(listOf(Item.Speak(text)), text)

    /**
     * The Follower's prompt, in her voice where she has one: VoiceLines builds
     * the items from the maneuver's kind, the band and the distance, and with
     * none of her clips applying it is [Status.say] word for word. [cues] are
     * situation lines that fired on the same fix (start, off route, ...): they
     * come first, in the same utterance, so the prompt never cuts them off.
     */
    fun prompt(status: Status, imperial: Boolean, cues: List<String> = emptyList()) {
        val say = status.say
        if (say == null && cues.isEmpty()) return
        val have = clipNames()
        val hers = herNames()
        val slots = character.slots(have, hers)
        val choose = { slot: String -> character.voiceFor(slot, hers, have, status.band) }
        val lines = cues.flatMap { VoiceLines.situation(it, slots, choose) }
        val core = say?.let {
            VoiceLines.core(status.kind, status.band, status.turnInM, status.instruction, status.street, imperial, slots, streets, choose)
        }
        val whole = if (say != null && core == null) listOf(Item.Speak(say)) else emptyList()
        // The maneuver right after, too close for a warning of its own: "and then turn left".
        val then = status.then?.takeIf { say != null && status.band == "now" }
        val andThen = then?.let { VoiceLines.andThen(it.kind, it.instruction, imperial, slots, choose) } ?: emptyList()
        // Her character arranges the instruction's pieces and adds up to two
        // flourishes, always after it; the distance and direction stay whole.
        val arranged = character.arrange(
            core, whole, status.band, status.kind, if (say != null) status.maneuver else null, cues, have, hers,
            now = SystemClock.elapsedRealtime() / 1000.0,
            flourishes = andThen.isEmpty(),
        )
        // The turn she is at plays FIRST and cuts in at once; anything else
        // waits for the word being said to finish (the Flet app's rule).
        val now = status.band == "now"
        val turn = arranged.turn + andThen
        play(
            (if (now) turn + lines else lines + turn) + arranged.after,
            listOfNotNull(
                cues.joinToString(", ").ifEmpty { null }, say,
                turn.joinToString(" ") { (it as? Item.Clip)?.name ?: "~" }.takeIf { turn.isNotEmpty() },
                arranged.after.joinToString(" + ") { it.name }.ifEmpty { null },
            ).joinToString(" | "),
            urgent = now,
        )
    }

    /**
     * A re-route the router refused, in words, then her "sorry" line - never
     * instead of the words, so none when the phone's voice cannot speak them.
     */
    fun refusal(reason: String) {
        val sorry = if (_state.value.ready) character.sorry(clipNames()) else null
        play(VoiceLines.refusal(reason) + listOfNotNull(sorry), "refused: $reason${sorry?.let { " | ${it.name}" } ?: ""}")
    }

    @Volatile private var opened = false

    /**
     * Her line when the app opens, once per process (a cold launch, not a
     * rotation or a return from Settings): her "open", else a wild one with
     * Character lines on, else the built-in, else nothing. Never over a prompt.
     */
    fun opening() {
        if (opened) return
        opened = true
        val have = clipNames()
        val hers = herNames()
        val items = VoiceLines.situation("open", character.slots(have, hers)) { character.voiceFor(it, hers, have) }
        if (items.isNotEmpty()) play(items, "open", idleOnly = true)
    }

    /** A tap outside the ride's controls (opening Where to, picking a place): rarely a line, never over a prompt. */
    fun tap(what: String) {
        val line = character.tap(clipNames()) ?: return
        play(listOf(line), "tap: $what | ${line.name}", idleOnly = true)
    }

    /** The names she imported herself: they win over any wild line for their slot. */
    private fun herNames(): Set<String> = if (::app.isInitialized) VoiceClips.imported(app).keys else emptySet()

    /** Her clips' names, or none before the engine started. Any thread: a directory listing. */
    fun clipNames(): Set<String> = if (::app.isInitialized) VoiceClips.have(app) else emptySet()

    /**
     * Play [items] as one utterance, cutting off anything still playing. Any
     * thread. Held, not dropped, while the phone's voice starts or is checked -
     * unless every item is her clip, which needs no engine. [preview] plays even
     * with spoken turns off.
     */
    fun play(items: List<Item>, label: String, preview: Boolean = false, urgent: Boolean = preview, idleOnly: Boolean = false) {
        main.post {
            // A preview is her testing a file in Settings: it plays whether or not
            // spoken turns are on for the ride, and a new tap cuts in at once.
            if ((!enabled && !preview) || items.isEmpty()) {
                if (items.isNotEmpty()) Log.i(TAG, "not said (voice off): $label")
                return@post
            }
            val t = tts
            val needsEngine = items.any { it is Item.Speak }
            if (needsEngine && (t == null || !_state.value.ready)) {
                val why = _state.value.why
                if (why == STARTING || why == CHECKING) pending = Pending(items, label, SystemClock.elapsedRealtime())
                Log.i(TAG, "not said yet ($why): $label")
                return@post
            }
            if (idleOnly && current != null) {
                Log.i(TAG, "not said (something is playing): $label")
                return@post
            }
            // A flourish or a cough is cut at once: it must never be what is
            // still playing when an instruction needs to speak.
            val soft = (sequence.getOrNull(step) as? Item.Clip)?.soft == true
            if (current != null && !urgent && !soft) {
                // Never cut a word off: this starts when the current item ends.
                queued = Pending(items, label, SystemClock.elapsedRealtime())
                Log.i(TAG, "after this item: $label")
                return@post
            }
            stopCurrent()
            queued = null
            begin(items, label)
        }
    }

    /** Main thread: start [items] as the current utterance. Focus is kept across a hand-over. */
    private fun begin(items: List<Item>, label: String) {
        val id = "p${++seq}"
        current = id
        sequence = items
        step = 0
        speakingNow = true
        holdFocus()
        _state.value = _state.value.copy(speaking = true, last = label, failed = "")
        Log.i(TAG, "say: $label")
        playStep(id)
    }

    /** Main thread. The next item of [sequence], or the end of the utterance. */
    private fun playStep(id: String) {
        if (id != current) return
        if (step >= sequence.size) return end(id)
        when (val item = sequence[step]) {
            is Item.Speak -> speak(id, item.text)
            is Item.Clip -> playClip(id, item)
        }
    }

    private fun speak(id: String, text: String) {
        val t = tts
        if (t == null || !_state.value.ready) return advance(id)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "$id.$step")
    }

    private fun playClip(id: String, item: Item.Clip) {
        val source = VoiceClips.sources(app)[item.name] ?: return fallback(id, item, "gone")
        val at = step
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            when (source) {
                is VoiceClips.Source.Imported -> mp.setDataSource(source.file.path)
                // Straight from the APK: mp3 assets are stored uncompressed, so they open as a file descriptor.
                is VoiceClips.Source.BuiltIn -> app.assets.openFd(source.asset).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            }
            mp.setOnCompletionListener { main.post { if (id == current && step == at) advance(id) } }
            mp.setOnErrorListener { _, what, extra ->
                main.post { if (id == current && step == at) fallback(id, item, "error $what/$extra") }
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            if (item.soft) character.heard(item.name, SystemClock.elapsedRealtime() / 1000.0)
            Log.d(TAG, "$id.$at clip ${item.name} (${if (source is VoiceClips.Source.Imported) "hers" else "built in"})")
        } catch (e: Exception) {
            mp.release()
            fallback(id, item, e.toString())
        }
    }

    /** A clip that will not play is said by the phone instead - never silence. */
    private fun fallback(id: String, item: Item.Clip, why: String) {
        Log.w(TAG, "clip ${item.name} would not play ($why)")
        releasePlayer()
        val words = item.fallback
        if (words != null && tts != null && _state.value.ready) speak(id, words) else advance(id)
    }

    private fun advance(id: String) {
        if (id != current) return
        releasePlayer()
        val next = queued
        if (next != null) {
            // The item that was playing has finished: the newer prompt replaces the rest.
            queued = null
            return begin(next.items, next.label)
        }
        step++
        playStep(id)
    }

    private fun end(id: String) {
        if (id != current) return
        current = null
        sequence = emptyList()
        speakingNow = false
        quietUntil = SystemClock.elapsedRealtime() + TAIL_MS
        dropFocus()
        _state.value = _state.value.copy(speaking = false)
    }

    /** A newer prompt flushes the one being played: a stale instruction heard late is worse than silence. */
    private fun stopCurrent() {
        if (current == null) return
        current = null
        releasePlayer()
        tts?.stop()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        player = null
    }

    /** True while the voice speaks and for [TAIL_MS] after. Any thread. */
    fun quiet(): Boolean = speakingNow || SystemClock.elapsedRealtime() < quietUntil

    /** A phone-voice item ended: "p<n>.<step>". Only the current utterance's newest item counts. */
    private fun finished(id: String, how: String, error: Int?) {
        main.post {
            Log.d(TAG, "$id $how")
            val prompt = id.substringBefore('.')
            if (prompt != current || id.substringAfter('.', "").toIntOrNull() != step) return@post
            if (error != null) _state.value = _state.value.copy(failed = "the last prompt failed: ${errorName(error)}")
            advance(prompt)
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
