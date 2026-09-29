package online.iamprettyfamous.navi.voice

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.core.voice.Character
import online.iamprettyfamous.navi.core.voice.VoiceLines
import online.iamprettyfamous.navi.dash.ink
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.ui.Palette
import online.iamprettyfamous.navi.ui.RoundButton

/** How long the second tap of "Remove voice" has to come within (voice_import.CONFIRM_S). */
private const val CONFIRM_MS = 6000L

/**
 * Her recorded voice, in Settings: what is in, Import voice, Remove voice, the
 * street-name switch, and EVERY name with where its sound comes from - hers,
 * built in, the phone's voice, or silent - and a play button: she cannot debug
 * twenty files by riding around. The play button plays exactly what a ride
 * would for that name.
 */
@Composable
fun VoicePanel(p: Palette, prefs: Prefs, rev: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var have by remember(rev) { mutableStateOf(VoiceClips.have(context)) }
    var hers by remember(rev) { mutableStateOf(VoiceClips.imported(context).keys) }
    var said by remember { mutableStateOf("") }
    var saidRole by remember { mutableStateOf(Role.TEXT2) }
    var armedAt by remember { mutableStateOf(0L) }
    val imperial = prefs.imperial

    fun refresh(text: String, role: Role) {
        have = VoiceClips.have(context)
        hers = VoiceClips.imported(context).keys
        said = text
        saidRole = role
        onChanged()
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        said = "Importing ${uris.size} file${if (uris.size == 1) "" else "s"}…"
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { VoiceClips.import(context, uris) }
            } catch (e: Exception) {
                refresh("Import failed: ${e.message ?: e.javaClass.simpleName}", Role.BAD)
                return@launch
            }
            refresh(VoiceClips.summary(result), if (result.trouble) Role.WARN else Role.TEXT)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Line(VoiceClips.statusText(context), p.text, TypeScale.MID_SP, bold = true)
        Line(
            "Pick the clips, or one ZIP of them, from Downloads, Drive or anywhere the phone's picker reaches. " +
                "A clip picked again replaces that one.",
            p.ink(Role.TEXT2), TypeScale.SMALL_SP,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WideButton("Import voice", p, primary = true, Modifier.weight(1f)) {
                armedAt = 0L
                pick.launch(arrayOf("*/*"))
            }
            val armed = armedAt != 0L && SystemClock.elapsedRealtime() - armedAt < CONFIRM_MS
            WideButton(if (armed) "Tap again to remove every clip" else "Remove voice", p, primary = false, Modifier.weight(1f)) {
                val now = SystemClock.elapsedRealtime()
                if (armedAt == 0L || now - armedAt > CONFIRM_MS) {
                    armedAt = now
                } else {
                    armedAt = 0L
                    val gone = VoiceClips.clear(context)
                    refresh("Removed $gone file${if (gone == 1) "" else "s"} - the built-in voice is back.", Role.TEXT2)
                }
            }
        }
        if (said.isNotEmpty()) Line(said, p.ink(saidRole), TypeScale.SMALL_SP)

        Switch("Street names after your lines", prefs.voiceStreets, p) {
            prefs.voiceStreets = it
            Voice.streets = it
            onChanged()
        }
        Switch("Character lines", prefs.voiceCharacter, p) {
            prefs.voiceCharacter = it
            Voice.character.on = it
            onChanged()
        }
        Line(
            "Wild lines in place of a turn's line, a shout after some prompts, the odd cough. " +
                "Off: the plain recorded voice. The instruction always comes first.",
            p.ink(Role.TEXT2), TypeScale.SMALL_SP,
        )
        Switch("A line when you tap Where to", prefs.voiceTaps, p) {
            prefs.voiceTaps = it
            Voice.character.taps = it
            onChanged()
        }

        Line(
            "Distances: add any dist-<N>ft or dist-<N>mile. A turn's distance uses your nearest one within " +
                "${VoiceLines.DISTANCE_BOUND} times; past that the phone says it.",
            p.ink(Role.TEXT2), TypeScale.SMALL_SP,
        )
        val distances = have.filter { VoiceLines.distanceFeet(it) != null }.sortedBy { VoiceLines.distanceFeet(it) }
        val slots = Voice.character.slots(have, hers)
        val wild = have.filter { Voice.character.usable(it, hers) }.groupBy { Character.wildSlot(it) }
        for (name in VoiceLines.FIXED + distances) {
            val source = VoiceLines.source(name, slots)
            val wilds = wild[name].orEmpty().size
            val own = when {
                name in hers -> "yours"
                name in have -> "built in"
                else -> null
            }
            ClipRow(
                name,
                when {
                    name in hers && wilds > 0 -> "yours (and $wilds wild, unused while yours is in)"
                    wilds > 0 && Voice.character.on -> "$wilds wild line${if (wilds == 1) "" else "s"}, at random" + (own?.let { "; $it after" } ?: "")
                    own != null -> own
                    source == VoiceLines.Source.PHONE -> "phone voice: \"${VoiceLines.standIn(name, imperial)}\""
                    else -> "silent - only ever a recorded line"
                },
                p,
                playable = source != VoiceLines.Source.SILENT,
            ) {
                val choose = { slot: String -> Voice.character.voiceFor(slot, hers, have) }
                Voice.play(VoiceLines.preview(name, slots, imperial, choose), "preview $name", preview = true)
            }
        }

        // Her character: every mood, the cough and the numbers, with how many lines each has.
        val extras = listOf(
            "arrival" to "on arriving", "angry" to "after off route or re-routing", "urgent" to "after a far or near turn, now and then",
            "scold" to "after speeding", "start" to "when a route starts", "sorry" to "after saying what went wrong", "ui" to "when you tap Where to",
        )
        for ((mood, whenSaid) in extras) {
            val pool = have.filter { Character.mood(it) == mood }
            if (pool.isEmpty()) continue
            ClipRow("flourish-$mood", "${pool.size} line${if (pool.size == 1) "" else "s"}, $whenSaid", p, playable = true) {
                Voice.play(listOf(VoiceLines.Item.Clip(pool.random(), null)), "preview flourish-$mood", preview = true)
            }
        }
        val coughs = have.filter(Character::isCough)
        if (coughs.isNotEmpty()) {
            ClipRow("interject-cough", "${coughs.size}, rarely, between a distance and its turn", p, playable = true) {
                Voice.play(listOf(VoiceLines.Item.Clip(coughs.random(), null)), "preview cough", preview = true)
            }
        }
        val numbers = have.count(Character::isNumber)
        if (numbers > 0) ClipRow("numbers", "$numbers kept for later - not played yet", p, playable = false) {}
    }
}

@Composable
private fun ClipRow(name: String, detail: String, p: Palette, playable: Boolean, onPlay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Line(name, p.text, TypeScale.MID_SP, bold = true)
            Line(detail, p.ink(Role.TEXT2), TypeScale.SMALL_SP)
        }
        if (playable) RoundButton("▶", p, onClick = onPlay)
    }
}

/** An Off/On pair of glove-sized buttons under a label. */
@Composable
private fun Switch(label: String, on: Boolean, p: Palette, set: (Boolean) -> Unit) {
    Line(label, p.text, TypeScale.MID_SP)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, text) in listOf(false to "Off", true to "On")) {
            val chosen = on == value
            WideButton(text, p, primary = chosen, Modifier.weight(1f)) { if (!chosen) set(value) }
        }
    }
}

@Composable
private fun Line(text: String, colour: Color, sizeSp: Int, bold: Boolean = false) {
    BasicText(text, style = TextStyle(color = colour, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
}

/** A 56 dp glove target with a ring that stands off its background (DashColours.CONTROL_RING). */
@Composable
private fun WideButton(label: String, p: Palette, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = TypeScale.TOUCH_DP.dp)
            .background(if (primary) p.accent else p.surface, shape)
            .border(2.dp, p[DashColours.CONTROL_RING], shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = if (primary) p.bg else p.text, fontSize = TypeScale.MID_SP.sp, fontWeight = FontWeight.Bold))
    }
}
