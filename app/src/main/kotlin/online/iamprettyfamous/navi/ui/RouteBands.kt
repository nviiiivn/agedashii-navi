package online.iamprettyfamous.navi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.telemetry.Physics
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.dash.ink
import online.iamprettyfamous.navi.routing.Navigator

/**
 * Under the turn banner while a route is up: where the route came from (the
 * rule - every route says phone or server), how far is left, and, when the
 * route stops short of the pin, where it really ends. Her rule is that the app
 * never quietly substitutes something for what she asked, so that line stays
 * for the whole route, not a moment.
 */
@Composable
fun RouteStrip(nav: Navigator.Nav, p: Palette, imperial: Boolean, voiceWhy: String? = null, destName: String? = null) {
    val route = nav.route ?: return
    val left = Guidance.distanceText(nav.status?.leftM ?: route.distanceM, imperial)
    Card(p) {
        Line("${route.source} route · $left left" + if (nav.reroutes > 0) " · re-routed" else "",
            p.ink(Role.TEXT2), TypeScale.MID_SP, FontWeight.Bold)
        nav.gap?.let { gap ->
            val where = gap.street?.let { "at $it, " } ?: ""
            Line("Route ends ${where}${Guidance.distanceText(gap.metres, imperial)} from ${destName ?: "your pin"}",
                p.warn, TypeScale.MID_SP, FontWeight.Bold, maxLines = 2)
        }
        if (nav.rerouteNote.isNotEmpty()) Line(nav.rerouteNote, p.warn, TypeScale.MID_SP, maxLines = 2)
        // She switched spoken turns on and hears nothing: say why, here, not
        // only in Settings.
        voiceWhy?.let { Line("Not spoken: $it", p.warn, TypeScale.MID_SP, maxLines = 3) }
    }
}

/**
 * The point she long-pressed, offered and not yet chosen: nothing routes until
 * she says so. Then "finding", or why there is no route, with the pin kept so
 * she can try again or cancel.
 */
@Composable
fun PinBand(
    pin: LatLon,
    nav: Navigator.Nav,
    here: LatLon?,
    p: Palette,
    imperial: Boolean,
    onRoute: () -> Unit,
    onCancel: () -> Unit,
    /** The place's name and "kind · town" when it came from Where to; null for a long press. */
    name: String? = null,
    about: String? = null,
    /** The point is outside the map this phone can route on. */
    outside: Boolean = false,
) {
    val finding = nav.asking == pin
    val failed = nav.failedFor == pin
    val title = when {
        finding && here == null -> "Waiting for GPS to start the route"
        finding -> "Finding a route…"
        failed -> "No route to that point"
        else -> "Route to ${name ?: "this point"}?"
    }
    val away = here?.let { "${Guidance.distanceText(Physics.haversineM(it, pin), imperial)} away" }
    val detail = if (failed && outside) "outside the map on this phone - its maps and routes cover the Bay Area only"
    else if (failed) nav.failed
    else listOfNotNull(about?.ifEmpty { null } ?: "%.5f, %.5f".format(pin.lat, pin.lon), away).joinToString(" · ")
    Card(p) {
        Line(title, p.text, TypeScale.MID_SP, FontWeight.ExtraBold, maxLines = 2)
        Line(detail, if (failed) p.warn else p.ink(Role.TEXT2), TypeScale.MID_SP, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(if (failed) "Try again" else "Route here", p, primary = true, enabled = !finding,
                modifier = Modifier.weight(1f), onClick = onRoute)
            Button("Cancel", p, primary = false, enabled = true, modifier = Modifier.weight(1f), onClick = onCancel)
        }
    }
}

/** The turn banner's card: a solid surface, so the map cannot move the contrast. */
@Composable
private fun Card(p: Palette, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Line(text: String, colour: Color, sizeSp: Int, weight: FontWeight = FontWeight.Normal, maxLines: Int = 1) {
    BasicText(
        text,
        style = TextStyle(color = colour, fontSize = sizeSp.sp, fontWeight = weight, lineHeight = (sizeSp * 1.2).sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Button(label: String, p: Palette, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val lit = primary && enabled
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = TypeScale.TOUCH_DP.dp)
            .background(if (lit) p.accent else p.bg, shape)
            // The round buttons' ring: a bg fill with a hairline border was
            // 1.1-1.3:1 on the card in the dark themes, a control found by touch.
            .border(2.dp, p[DashColours.CONTROL_RING], shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Line(label, if (lit) p.bg else p.text, TypeScale.MID_SP, FontWeight.Bold)
    }
}
