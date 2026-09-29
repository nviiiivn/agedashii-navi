package online.iamprettyfamous.navi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.dash.ink
import online.iamprettyfamous.navi.places.PlacesStore

/**
 * Where to: a named place from the offline list, or a point she pasted. A
 * panel over the map, like Settings. Picking a row does not route - it offers
 * the place as the pin does, and nothing routes until she says so.
 *
 * The list's own state is said before any search ("127,564 places on this
 * phone"), so "not installed" can never pass for "no results".
 */
@Composable
fun WhereToPanel(
    p: Palette,
    state: PlacesStore.State,
    imperial: Boolean,
    onQuery: (String) -> Unit,
    onPick: (PlacesStore.Hit) -> Unit,
    onClose: () -> Unit,
    /** Whether a place is inside the map this phone can route on; the list reaches further. */
    routable: (online.iamprettyfamous.navi.core.geo.LatLon) -> Boolean = { true },
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundButton("✕", p) { onClose() }
                Line("Where to", p.text, 28, FontWeight.Bold)
            }
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = TypeScale.TOUCH_DP.dp)
                    .background(p.surface, shape).border(2.dp, p.accent, shape)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.query.isEmpty()) Line("A place, or pasted coordinates", p.ink(Role.TEXT3), 20)
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 20.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Line(state.status, if (state.usable) p.ink(Role.TEXT2) else p.warn, TypeScale.MID_SP, maxLines = 3)
            val words = state.query.trim()
            if (words.length >= 2 && state.hits.isEmpty()) {
                Line("Nothing by that name on this phone", p.ink(Role.TEXT2), TypeScale.MID_SP)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.hits) { hit ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = TypeScale.TOUCH_DP.dp)
                            .background(p.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, p.border, RoundedCornerShape(10.dp))
                            .clickable { keyboard?.hide(); onPick(hit) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Line(hit.name, p.text, 18, FontWeight.Bold, maxLines = 2)
                        val far = hit.distM?.let { Guidance.distanceText(it, imperial) }
                        Line(listOfNotNull(hit.detail.ifEmpty { null }, far).joinToString(" · "), p.ink(Role.TEXT2), TypeScale.MID_SP)
                        // Said before she taps, in the Flet app's words: the places
                        // list covers 36.8-38.9 N, the map and router 37.15-38.15.
                        if (!routable(hit.at)) Line("outside the map on this phone", p.warn, TypeScale.MID_SP, FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, colour: Color, sizeSp: Int, weight: FontWeight = FontWeight.Normal, maxLines: Int = 1) {
    BasicText(
        text,
        style = TextStyle(color = colour, fontSize = sizeSp.sp, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
