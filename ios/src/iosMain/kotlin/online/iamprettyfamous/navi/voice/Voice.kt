package online.iamprettyfamous.navi.voice

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.nowSeconds
import online.iamprettyfamous.navi.core.voice.Character
import online.iamprettyfamous.navi.core.voice.VoiceLines
import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeVoicePrompt
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Spoken turns on the iPhone: her recorded clips through AVAudioPlayer, the
 * phone's words through AVSpeechSynthesizer - the Android app's Voice, with
 * the same promises and the same order of things:
 *
 * - Her music ducks and comes back: the session is Playback in VoicePrompt
 *   mode, ducking others and pausing spoken audio (a podcast), which is what
 *   Apple asks of a navigation app - and with UIBackgroundModes audio a prompt
 *   starts with the phone asleep in her pocket.
 * - Prompts never stack, and never cut a word off: a newer one starts when the
 *   item playing ends; only a "now" cuts in at once.
 * - A clip that will not play is replaced by its own words - never silence.
 * - [quiet] is true while it speaks and for [TAIL_MS] after.
 *
 * iOS's built-in voices live on the phone, so there is no network voice to
 * avoid and no test word: Android's check exists because Google's engine
 * lists voices that say nothing. Everything here runs on the main thread;
 * [play] is safe from any.
 */
object Voice {
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

    /** Her Settings switch; the prompts are still worked out, just not said. */
    var enabled = true

    /** Her switch for the street name after her own line. */
    var streets = false

    /** Wild lines, flourishes and the cough, with her two switches. */
    val character = Character()

    private var synth: AVSpeechSynthesizer? = null
    private var ttsVoice: AVSpeechSynthesisVoice? = null
    private var utterance: AVSpeechUtterance? = null
    private var player: AVAudioPlayer? = null
    private var current: String? = null
    private var seq = 0
    private var sequence: List<Item> = emptyList()
    private var step = 0
    private var focused = false
    private var speakingNow = false
    private var quietUntil = 0.0

    /** A newer prompt that came while one was playing: it starts when the item now playing ends. */
    private class Pending(val items: List<Item>, val label: String)
    private var queued: Pending? = null

    private val speechDelegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            spoken(didFinishSpeechUtterance)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            spoken(didCancelSpeechUtterance)
        }
    }

    private val playerDelegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            if (player === this@Voice.player) clipEnded(if (successfully) null else "did not decode")
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            if (player === this@Voice.player) clipEnded(error?.localizedDescription ?: "decode error")
        }
    }

    /** Start the engine once. Main thread. */
    fun start() {
        if (synth != null) return
        val s = AVSpeechSynthesizer()
        s.delegate = speechDelegate
        synth = s
        AVAudioSession.sharedInstance().setCategory(
            AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeVoicePrompt,
            options = AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers,
            error = null,
        )
        // An enhanced voice where she has downloaded one, else the compact one
        // every iPhone ships: English, her country's first.
        @Suppress("UNCHECKED_CAST")
        val voices = (AVSpeechSynthesisVoice.speechVoices() as List<AVSpeechSynthesisVoice>).filter { it.language.startsWith("en") }
        val v = voices.sortedWith(compareByDescending<AVSpeechSynthesisVoice> { it.language == "en-US" }.thenByDescending { it.quality })
            .firstOrNull() ?: AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
        ttsVoice = v
        _state.value = if (v == null) {
            State(why = "no English voice on this phone - Settings, Accessibility, Spoken Content, Voices, add English. Turns are shown, not spoken, until then")
        } else {
            State(ready = true, why = "", voice = "${v.name} (${v.language}, on the phone)")
        }
    }

    /** Say [text] now, cutting off anything still being said. Any thread. */
    fun say(text: String) = play(listOf(Item.Speak(text)), text)

    /**
     * The Follower's prompt, in her voice where she has one: VoiceLines builds
     * the items from the maneuver's kind, the band and the distance. The
     * Android app's prompt(), unchanged.
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
        val then = status.then?.takeIf { say != null && status.band == "now" }
        val andThen = then?.let { VoiceLines.andThen(it.kind, it.instruction, imperial, slots, choose) } ?: emptyList()
        val arranged = character.arrange(
            core, whole, status.band, status.kind, if (say != null) status.maneuver else null, cues, have, hers,
            now = nowSeconds(),
            flourishes = andThen.isEmpty(),
        )
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

    /** A re-route the router refused, in words, then her "sorry" line. */
    fun refusal(reason: String, held: List<String> = emptyList()) {
        val have = clipNames()
        val hers = herNames()
        val slots = character.slots(have, hers)
        val lead = held.flatMap { VoiceLines.situation(it, slots) { s -> character.voiceFor(s, hers, have) } }
        val sorry = if (_state.value.ready) character.sorry(have) else null
        play(lead + VoiceLines.refusal(reason) + listOfNotNull(sorry), "refused: $reason")
    }

    /** "Hasta la vista, baby", on the way out: she stopped the recording. Her rule, 2026-10-12. */
    fun farewell() {
        val line = character.farewell(clipNames()) ?: return
        play(listOf(line), "farewell | ${line.name}")
    }

    private var opened = false

    /** Her line when the app opens, once per launch. Never over a prompt. */
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

    private fun herNames(): Set<String> = VoiceClips.imported().keys

    fun clipNames(): Set<String> = VoiceClips.have()

    /** Play [items] as one utterance. Any thread. [preview] plays even with spoken turns off. */
    fun play(items: List<Item>, label: String, preview: Boolean = false, urgent: Boolean = preview, idleOnly: Boolean = false) {
        dispatch_async(dispatch_get_main_queue()) {
            if ((!enabled && !preview) || items.isEmpty()) return@dispatch_async
            val needsEngine = items.any { it is Item.Speak }
            if (needsEngine && !_state.value.ready && items.all { it is Item.Speak }) return@dispatch_async
            if (idleOnly && current != null) return@dispatch_async
            // A flourish or a cough is cut at once: it must never be what is
            // still playing when an instruction needs to speak.
            val soft = (sequence.getOrNull(step) as? Item.Clip)?.soft == true
            if (current != null && !urgent && !soft) {
                queued = Pending(items, label)
                return@dispatch_async
            }
            stopCurrent()
            queued = null
            begin(items, label)
        }
    }

    private fun begin(items: List<Item>, label: String) {
        val id = "p${++seq}"
        current = id
        sequence = items
        step = 0
        speakingNow = true
        holdFocus()
        _state.value = _state.value.copy(speaking = true, last = label, failed = "")
        println("[voice] say: $label")
        playStep(id)
    }

    private fun playStep(id: String) {
        if (id != current) return
        if (step >= sequence.size) return end(id)
        when (val item = sequence[step]) {
            is Item.Speak -> speak(item.text)
            is Item.Clip -> playClip(id, item)
        }
    }

    private fun speak(text: String) {
        val s = synth
        val id = current ?: return
        if (s == null || !_state.value.ready) return advance(id)
        val u = AVSpeechUtterance(string = text)
        u.voice = ttsVoice
        utterance = u
        s.speakUtterance(u)
    }

    /** The synthesizer finished or was cut; only the current utterance counts. */
    private fun spoken(u: AVSpeechUtterance) {
        if (u !== utterance) return
        utterance = null
        current?.let(::advance)
    }

    private fun playClip(id: String, item: Item.Clip) {
        val source = VoiceClips.sources()[item.name] ?: return fallback(id, item, "gone")
        val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(source.path), error = null)
        if (p == null || !p.prepareToPlay()) return fallback(id, item, "will not open")
        p.delegate = playerDelegate
        player = p
        if (!p.play()) {
            player = null
            return fallback(id, item, "will not start")
        }
        if (item.soft) character.heard(item.name, nowSeconds())
    }

    private fun clipEnded(error: String?) {
        val id = current ?: return
        val item = sequence.getOrNull(step) as? Item.Clip
        player = null
        if (error != null && item != null) fallback(id, item, error) else advance(id)
    }

    /** A clip that will not play is said by the phone instead - never silence. */
    private fun fallback(id: String, item: Item.Clip, why: String) {
        println("[voice] clip ${item.name} would not play ($why)")
        player = null
        val words = item.fallback
        if (words != null && _state.value.ready) speak(words) else advance(id)
    }

    private fun advance(id: String) {
        if (id != current) return
        player = null
        val next = queued
        if (next != null) {
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
        quietUntil = nowSeconds() + TAIL_MS / 1000.0
        dropFocus()
        _state.value = _state.value.copy(speaking = false)
    }

    /** A newer prompt flushes the one being played: a stale instruction heard late is worse than silence. */
    private fun stopCurrent() {
        if (current == null) return
        current = null
        player?.stop()
        player = null
        utterance = null
        synth?.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    /** True while the voice speaks and for [TAIL_MS] after. */
    fun quiet(): Boolean = speakingNow || nowSeconds() < quietUntil

    private fun holdFocus() {
        if (focused) return
        // Speak whether or not the session activates: a turn she misses
        // because a call holds the audio is lost either way.
        focused = AVAudioSession.sharedInstance().setActive(true, error = null)
        if (!focused) println("[voice] the audio session would not activate")
    }

    private fun dropFocus() {
        if (!focused) return
        focused = false
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
}
