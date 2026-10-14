package online.iamprettyfamous.navi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.dash.ink

/**
 * How this works: the Flet app's help page, rewritten for what THIS app does.
 * Its text is not copied where the two differ - the Flet page describes an
 * MBTiles server, online places and a speed limit this app does not have, and
 * a help page that describes another app is worse than none.
 */
@Composable
fun HelpPanel(p: Palette, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundButton("✕", p) { onClose() }
                BasicText("How this works", style = TextStyle(color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Bold))
            }
            for ((heading, lines) in SECTIONS) {
                BasicText(
                    heading.uppercase(),
                    style = TextStyle(color = p.ink(Role.TEXT2), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
                    modifier = Modifier.padding(top = 14.dp),
                )
                for (line in lines) BasicText(line, style = TextStyle(color = p.text, fontSize = 16.sp, lineHeight = 22.sp))
            }
        }
    }
}

private val SECTIONS: List<Pair<String, List<String>>> = listOf(
    "It works with no signal" to listOf(
        "The map, the router and the places list are all on the phone - this app does not even have " +
            "permission to use the network. Every route says 'offline route': it was worked out here.",
        "The map and routes cover the Bay Area. Past its edge the app says so rather than showing a blank map.",
    ),
    "The buttons" to listOf(
        "Down the left: ☰ is Settings; the round dot records a ride; the magnifier is Where to; the ring " +
            "follows your bike - filled while the map follows you, dark once you have moved the map, one tap " +
            "to come back. End appears while there is a route, and takes two taps.",
    ),
    "Turns, and when they are said" to listOf(
        "Prompts are timed, not fixed: roughly a minute, twenty seconds and six seconds before a turn, so " +
            "they land at the right moment at 25 mph and at 70. Each is said once.",
        "Off route needs three fixes in a row beyond the distance set in Settings, and coming back needs you " +
            "well inside it - so one bad fix under a bridge does not announce a mistake you did not make. The " +
            "new route starts the way you are facing.",
        "The voice is the phone's own, offline, through the helmet if one is paired; your music dips for it " +
            "and comes back. Your recorded lines play in its place where you have them.",
        "A route keeps going with the screen locked, as a recording does.",
    ),
    "Where to" to listOf(
        "Type a name: the list on the phone holds every named museum, park, café, fuel station and peak. " +
            "Once the name matches, the nearest wins - so type enough of it to mean the one you want.",
        "Pasted coordinates, a geo: pin or a maps link work too. Or hold a finger on the map to drop a pin.",
        "Nothing routes until you tap Route here. If the road ends short of your pin, it says where.",
        "The list reaches past the map; places the phone cannot route to are marked before you tap them.",
    ),
    "The telemetry, and what is behind it" to listOf(
        "Speed and heading are the GPS receiver's own. Lean is worked out from speed and how fast you turn - " +
            "what lean is for any bike in a steady corner - and says nothing below about 11 mph, where it means nothing.",
        "The motion sensors (tilt, bumps, the gyro, compass and barometer) can be switched off in Settings, and " +
            "stay off while recording. Set level here calls your mount's angle level, kept across rides.",
        "A phone without a sensor gives no reading for it - a dash, never a zero.",
    ),
    "The engine note, and the RPM" to listOf(
        "With the microphone on, the exhaust's note is turned into engine speed: the CB750, an inline four, " +
            "fires twice a revolution, so RPM = Hz x 30. That is why the right bike must be chosen.",
        "It refuses rather than guesses - too quiet, no clear note, out of the engine's range, or a jump no " +
            "engine makes - and shows a dash. It pauses while the voice speaks. It has not yet been calibrated " +
            "against your bike's own tach.",
    ),
    "Following the sun" to listOf(
        "Sunlight by day, Night at dusk, Red night after dark, and the screen dimmed to match - from where you " +
            "are and the time, no light sensor. Red on black keeps the night vision it took twenty minutes to build.",
        "Picking a theme by hand turns it off.",
    ),
    "Rides" to listOf(
        "A recording writes to the phone as you go, so a flat battery costs seconds, not the ride. It is saved " +
            "as CSV, GPX and JSON in the app's folder on the phone. A list of rides is not in this app yet.",
    ),
)
