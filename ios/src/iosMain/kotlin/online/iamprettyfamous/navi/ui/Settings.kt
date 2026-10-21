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
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.routing.Router
import online.iamprettyfamous.navi.voice.Voice
import online.iamprettyfamous.navi.voice.VoiceClips
import kotlin.math.roundToLong

/** A setting that changed, so the Ride screen can apply it at once. */
enum class Changed { THEME, AUTO_THEME, BIKE, BUILDINGS, NORTH_UP, KEEP_AWAKE, HUD, TELEMETRY, VOICE, OFFROUTE }

/**
 * Settings on the iPhone: the Android app's panel, with only the switches this
 * build has a feature behind. A control that does nothing is a dead tap and a
 * lie about the app; the motion sensors, the rev counter, ride sync and map
 * packs arrive here with their features.
 */
@Composable
fun SettingsPanel(
    p: Palette,
    prefs: Prefs,
    /** Bumped on every change, so the panel re-reads prefs (NSUserDefaults are not Compose state). */
    rev: Int,
    voice: Voice.State,
    router: Router.State,
    fixAgeS: Double?,
    fixAccM: Double?,
    version: String,
    onChanged: (Changed) -> Unit,
    onClose: () -> Unit,
    onHelp: () -> Unit = {},
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
            Choice("Follow the sun", p, OFF_ON, onOff(prefs.autoTheme)) {
                prefs.autoTheme = it == "on"; onChanged(Changed.AUTO_THEME)
            }
            Text("Sunlight by day, Night at dusk, Red night after dark, and the screen dimmed to match - " +
                "worked out from where you are, with no sensor and no signal. Picking a theme above turns this off.",
                p, size = 12, faint = true)
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
            Choice("Off route after", p, Prefs.OFFROUTE_CHOICES.map { m ->
                "$m" to if (prefs.imperial) "${(m * 3.28084 / 10.0).roundToLong() * 10} ft" else "$m m"
            }, "${prefs.offrouteM}") {
                prefs.offrouteM = it.toInt(); onChanged(Changed.OFFROUTE)
            }
            Text("Three fixes in a row beyond this before it says so, and it has to come back well inside " +
                "before it stops - otherwise one noisy fix re-routes a corner you took correctly.", p, size = 12, faint = true)

            Heading("Your recorded voice", p)
            Text(VoiceClips.statusText(), p, size = 15, bold = true)
            Choice("Character lines", p, OFF_ON, onOff(prefs.voiceCharacter)) {
                prefs.voiceCharacter = it == "on"; onChanged(Changed.VOICE)
            }
            Choice("A line when you tap", p, OFF_ON, onOff(prefs.voiceTaps)) {
                prefs.voiceTaps = it == "on"; onChanged(Changed.VOICE)
            }
            Choice("Street names after your line", p, OFF_ON, onOff(prefs.voiceStreets)) {
                prefs.voiceStreets = it == "on"; onChanged(Changed.VOICE)
            }
            Text("Your own clips go in the Files app: On My iPhone, xnav.k, voice. A clip named for a prompt " +
                "replaces the built-in one; take it out and the built-in one is back.", p, size = 12, faint = true)

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
            Choice("Record automatically", p, OFF_ON, onOff(prefs.autoRecord)) {
                prefs.autoRecord = it == "on"; onChanged(Changed.TELEMETRY)
            }
            Text("Starts a ride log once the bike is rolling (over about 6 mph for three fixes). " +
                "A ride you stop by hand is not restarted until the bike has stopped. Rides are in the Files app: " +
                "On My iPhone, xnav.k, rides - the log, and a CSV, GPX and JSON of each.", p, size = 12, faint = true)

            Heading("What is actually being read", p)
            Text(gpsLine(fixAgeS, fixAccM, prefs.imperial), p, size = 16)
            Text(voiceAccount(voice, prefs.voice), p, size = 16, faint = !prefs.voice)
            Text(if (router.ready) "Routing: on the phone, offline" else "Routing: ${router.why}", p, size = 16, faint = !router.ready)
            Text("Motion sensors and the rev counter are not in the iPhone build yet: lean and cornering g " +
                "come from the GPS alone.", p, size = 12, faint = true)

            Heading("About", p)
            Action("How this works", p) { onHelp() }
            Text("xnav.k $version", p, size = 16)
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

/** The voice's line in the honest account. */
fun voiceAccount(v: Voice.State, on: Boolean): String = when {
    !on -> "Voice: off - turns are shown, not spoken"
    v.ready && v.failed.isNotEmpty() -> "Voice: ${v.voice} - ${v.failed}"
    v.ready -> "Voice: ${v.voice}"
    else -> "Voice: ${v.why}"
}

/**
 * Shown here and nowhere on the map - her decision, 2026-09-28. Required again
 * before the app goes beyond her own phones (KNOWN.md).
 */
private val CREDITS = listOf(
    "Map data © OpenStreetMap contributors, ODbL",
    "Map style from Protomaps basemaps, CC0",
    "Map drawing: MapLibre Native, BSD-2-Clause",
    "Routing: Valhalla, MIT",
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

/** A button among the chips: 56 dp for gloves, the round buttons' ring. */
@Composable
private fun Action(label: String, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 56.dp)
            .background(p.surface, RoundedCornerShape(10.dp))
            .border(2.dp, p[DashColours.CONTROL_RING], RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
    }
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
                        .border(2.dp, if (on) p.accent else p[DashColours.CONTROL_RING], RoundedCornerShape(10.dp))
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
