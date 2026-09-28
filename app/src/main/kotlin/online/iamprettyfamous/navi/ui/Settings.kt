package online.iamprettyfamous.navi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.bikes.Bikes
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.record.Telemetry
import online.iamprettyfamous.navi.voice.Voice
import online.iamprettyfamous.navi.voice.VoicePanel

/** A setting that changed, so the Ride screen can apply it at once. */
enum class Changed { THEME, BIKE, BUILDINGS, NORTH_UP, KEEP_AWAKE, HUD, TELEMETRY, MIC_ON, MIC_OFF, VOICE }

/**
 * Settings: every preference that does something today, the honest account of
 * what each sensor is actually doing, and the credits. A panel over the map,
 * not another screen, so the map and a recording are untouched underneath.
 *
 * Only switches that work are here. A control that does nothing is a dead tap
 * and a lie about the app; the spec's other preferences arrive with the
 * features they switch.
 */
@Composable
fun SettingsPanel(
    p: Palette,
    prefs: Prefs,
    /** Bumped on every change, so the panel re-reads prefs (SharedPreferences are not Compose state). */
    rev: Int,
    sensors: List<Telemetry.SensorLine>,
    mic: Telemetry.MicState,
    voice: Voice.State,
    fixAgeS: Double?,
    fixAccM: Double?,
    version: String,
    onChanged: (Changed) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundButton("✕", p) { onClose() }
                Text("Settings", p, size = 28, bold = true)
            }

            Heading("Look", p)
            Choice("Theme", p, PALETTES.map { it.key to it.name }, prefs.theme) {
                prefs.theme = it; onChanged(Changed.THEME)
            }
            Choice("Bike", p, listOf("yellow" to "Yellow", "black" to "Black"), prefs.bike) {
                prefs.bike = it; onChanged(Changed.BIKE)
            }
            Choice("Buildings", p, OFF_ON, onOff(prefs.buildings)) {
                prefs.buildings = it == "on"; onChanged(Changed.BUILDINGS)
            }

            Heading("Ride", p)
            Choice("Units", p, listOf("imperial" to "Miles · feet", "metric" to "Km · metres"),
                if (prefs.imperial) "imperial" else "metric") {
                prefs.imperial = it == "imperial"; onChanged(Changed.TELEMETRY)
            }
            Choice("Motorcycle", p, Bikes.names(), prefs.machine) {
                prefs.machine = it; onChanged(Changed.TELEMETRY)
            }
            Choice("Map", p, listOf("off" to "Turns with me", "on" to "North up"), onOff(prefs.northUp)) {
                prefs.northUp = it == "on"; onChanged(Changed.NORTH_UP)
            }
            Choice("Screen", p, listOf("on" to "Stays on", "off" to "Sleeps as usual"), onOff(prefs.keepAwake)) {
                prefs.keepAwake = it == "on"; onChanged(Changed.KEEP_AWAKE)
            }
            Choice("Spoken turns", p, OFF_ON, onOff(prefs.voice)) {
                prefs.voice = it == "on"; onChanged(Changed.VOICE)
            }

            Heading("Your recorded voice", p)
            VoicePanel(p, prefs, rev) { onChanged(Changed.VOICE) }

            Heading("Gauges", p)
            Choice("Gauges", p, OFF_ON, onOff(prefs.dash)) {
                prefs.dash = it == "on"; onChanged(Changed.HUD)
            }
            val layouts = Layout.entries.filter { it != Layout.OFF }
            Choice("Layout", p, layouts.map { it.key to it.title }, Layout.of(prefs.hud).key) {
                prefs.hud = it; onChanged(Changed.HUD)
            }
            Choice("Landscape side", p, listOf("right" to "Right", "left" to "Left"), prefs.hudSide) {
                prefs.hudSide = it; onChanged(Changed.HUD)
            }
            Choice("Portrait edge", p, listOf("bottom" to "Bottom", "top" to "Top"), prefs.hudEdge) {
                prefs.hudEdge = it; onChanged(Changed.HUD)
            }

            Heading("Recording", p)
            Choice("Log rate", p, Prefs.HZ_CHOICES.map { "$it" to "$it / s" }, "${prefs.hz}") {
                prefs.hz = it.toInt(); onChanged(Changed.TELEMETRY)
            }
            Choice("Sensors", p, listOf("on" to "Only what gauges need", "off" to "All, always"),
                onOff(prefs.sensorsOnDemand)) {
                prefs.sensorsOnDemand = it == "on"; onChanged(Changed.TELEMETRY)
            }

            // Never remembered: she switches it on per ride (spec rule 5), and a
            // ride ending switches it off.
            Choice("Microphone (rev counter)", p, listOf("off" to "Off", "on" to "On for this ride"), onOff(mic.on)) {
                onChanged(if (it == "on") Changed.MIC_ON else Changed.MIC_OFF)
            }

            Heading("What is actually being read", p)
            Text(gpsLine(fixAgeS, fixAccM, prefs.imperial), p, size = 16)
            if (sensors.isEmpty()) Text("Sensors: nothing running yet", p, size = 16, faint = true)
            for (s in sensors) Text(sensorLine(s), p, size = 16, faint = s.hz == null)
            Text(micAccount(mic), p, size = 16, faint = !mic.on)
            Text(voiceAccount(voice, prefs.voice), p, size = 16, faint = !prefs.voice)
            Text("Recording runs every sensor at 50 per second, whatever this says.", p, size = 12, faint = true)

            Heading("About", p)
            Text("Agedashii Navi $version", p, size = 16)
            for (line in CREDITS) Text(line, p, size = 12, faint = true)
        }
    }
}

private val OFF_ON = listOf("off" to "Off", "on" to "On")

private fun onOff(b: Boolean) = if (b) "on" else "off"

private fun gpsLine(ageS: Double?, accM: Double?, imperial: Boolean): String {
    if (ageS == null) return "GPS: no fix yet"
    val acc = accM?.let { if (imperial) " · ±%.0f ft".format(it * 3.28084) else " · ±%.0f m".format(it) } ?: ""
    return "GPS: last fix %.1f s ago%s".format(ageS, acc)
}

private fun sensorLine(s: Telemetry.SensorLine): String = when {
    !s.present -> "${label(s.name)}: not on this phone"
    s.hz == null -> "${label(s.name)}: off - no gauge on screen needs it"
    s.lastSeenS == null -> "${label(s.name)}: on at ${s.hz} / s, nothing received yet"
    else -> "${label(s.name)}: on at ${s.hz} / s, last reading %.1f s ago".format(s.lastSeenS)
}

/** The voice's line in the honest account. */
fun voiceAccount(v: Voice.State, on: Boolean): String = when {
    !on -> "Voice: off - turns are shown, not spoken"
    v.ready && v.failed.isNotEmpty() -> "Voice: ${v.voice} - ${v.failed}"
    v.ready -> "Voice: ${v.voice}"
    else -> "Voice: ${v.why}"
}

/** The microphone's line in the honest account, and beside record while it listens. */
fun micAccount(m: Telemetry.MicState): String = when {
    m.error.isNotEmpty() -> "Microphone: ${m.error}"
    !m.on -> "Microphone: off"
    m.rpm != null -> "Microphone: on · %,d rpm - uncalibrated estimate".format(m.rpm.toLong()) +
        (if (m.rejected.isNotEmpty()) " · last window rejected: ${m.rejected}" else "")
    m.rejected.isNotEmpty() -> "Microphone: on · no rpm: ${m.rejected}"
    else -> "Microphone: on · listening"
}

private fun label(name: String) = when (name) {
    "user_accelerometer" -> "Bumps (linear accel.)"
    else -> name.replaceFirstChar { it.uppercase() }
}

/**
 * Shown here and nowhere on the map - her decision, 2026-09-28. Required again
 * before the app goes beyond her own phones (KNOWN.md).
 */
private val CREDITS = listOf(
    "Map data © OpenStreetMap contributors, ODbL",
    "Map style from Protomaps basemaps, CC0",
    "Map drawing: MapLibre Native, BSD-2-Clause",
    "Type: Archivo, SIL Open Font License 1.1",
)

@Composable
private fun Heading(text: String, p: Palette) {
    BasicText(
        text.uppercase(),
        style = TextStyle(color = p.text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun Text(text: String, p: Palette, size: Int, bold: Boolean = false, faint: Boolean = false) {
    BasicText(
        text,
        style = TextStyle(
            color = if (faint) p.text3 else p.text,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
}

/** A label and its choices as chips, 56 dp tall for gloves; the chosen one filled in accent. */
@Composable
private fun Choice(label: String, p: Palette, options: List<Pair<String, String>>, chosen: String, pick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, p, size = 16)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((key, name) in options) {
                val on = key == chosen
                Box(
                    Modifier.heightIn(min = 56.dp)
                        .background(if (on) p.accent else p.surface, RoundedCornerShape(10.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) p.accent else p.border, RoundedCornerShape(10.dp))
                        .clickable { if (!on) pick(key) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        name,
                        style = TextStyle(
                            color = if (on) p.bg else p.text,
                            fontSize = 16.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}
