package online.iamprettyfamous.navi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.theme.TypeScale
import kotlin.math.ceil

/**
 * The round-button column down the start edge (spec section 03): the menu,
 * then record with its lines beside it, then follow when the map has let go. Its geometry is
 * here, once, because the dash has to keep clear of it and must not drift from
 * it. The Flet app let its gauges run under these buttons - the same fault as
 * its menu over the turn-by-turn band, which BAND_INSET fixed there.
 */
object ButtonColumn {
    /** From the safe-drawing edge to the buttons. */
    const val EDGE_DP = 16

    /** Between one button and the next. */
    const val GAP_DP = 12

    /** Between the record button and the lines beside it. */
    const val BESIDE_DP = 10

    /** Where the dash may start beside the column: the edge, a button, one gap. */
    const val CLEAR_DP = EDGE_DP + TypeScale.TOUCH_DP + GAP_DP

    /** Record's place in the column, from 0: spec 03 puts the menu first and record under it. */
    const val RECORD_SLOT = 1

    /** Where the lines beside record start, from the top: level with record. */
    const val LINES_TOP_DP = EDGE_DP + RECORD_SLOT * (TypeScale.TOUCH_DP + GAP_DP)

    /** Where the dash may start below the record button. */
    const val BELOW_RECORD_DP = LINES_TOP_DP + TypeScale.TOUCH_DP + GAP_DP

    /** Where the lines beside record start. */
    const val LINES_START_DP = EDGE_DP + TypeScale.TOUCH_DP + BESIDE_DP

    /**
     * The widest the lines grow before wrapping: under half a landscape screen,
     * so they never reach a gauge column down the far edge. In portrait the
     * screen is narrower than this, and everything at the top clears them.
     */
    const val LINES_MAX_DP = 360

}

/**
 * What sits beside the column at the top, as measured - it is text, so never
 * assumed: the turn banner when a route is being followed, then the lines
 * beside record. [bottomDp] is its lower edge from the safe top, 0 when there
 * is nothing; [banner] says the banner is up, spanning the top to the far edge.
 */
data class TopStack(val bottomDp: Int = 0, val banner: Boolean = false) {
    /** Where the dash may start below it. */
    val clearDp: Int get() = if (bottomDp == 0) 0 else bottomDp + ButtonColumn.GAP_DP
}

/**
 * The column and what sits beside it, positioned in one place: MainActivity
 * fills it, and so does the screenshot test, so the pictures show the real
 * layout. [buttons] must put record at [ButtonColumn.RECORD_SLOT].
 *
 * Beside the column: the turn [banner], when there is one, level with the
 * menu and out to the far edge; then the [lines] - the recording and the
 * status - level with record, or under the banner when it is up. None of it is
 * in the column's flow, so nothing appearing ever moves a button under a
 * glove; [onTopStack] reports how far down it all reaches, and the dash moves
 * out from under it.
 */
@Composable
fun BoxScope.ButtonColumnLayout(
    onTopStack: (TopStack) -> Unit,
    banner: (@Composable () -> Unit)? = null,
    lines: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(ButtonColumn.EDGE_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ButtonColumn.GAP_DP.dp),
        content = buttons,
    )
    val top = if (banner != null) ButtonColumn.EDGE_DP else ButtonColumn.LINES_TOP_DP
    Column(
        Modifier.align(Alignment.TopStart).safeDrawingPadding()
            .padding(start = ButtonColumn.LINES_START_DP.dp, top = top.dp, end = ButtonColumn.EDGE_DP.dp)
            .onSizeChanged {
                val h = ceil(it.height / density.density).toInt()
                onTopStack(TopStack(if (h == 0) 0 else top + h, banner != null))
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        banner?.invoke()
        Column(Modifier.widthIn(max = ButtonColumn.LINES_MAX_DP.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = lines)
    }
}

/**
 * 56 dp: the spec's minimum target, for gloves. The ring is DashColours.CONTROL_RING,
 * 2 dp: in `border` it was 1.0-1.4:1 against the map in seven themes, a button
 * findable only by touch.
 */
@Composable
fun RoundButton(p: Palette, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(TypeScale.TOUCH_DP.dp)
            .background(p.surface, CircleShape)
            .border(2.dp, p[DashColours.CONTROL_RING], CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
fun RoundButton(label: String, p: Palette, ink: Color = p.accent, onClick: () -> Unit) = RoundButton(p, onClick) {
    BasicText(label, style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
}

/**
 * Follow - "centre on me": a ring round a dot, drawn, because the ◎ character
 * renders a few pixels wide at 16 sp, too small to find on a control.
 */
@Composable
fun FollowGlyph(ink: Color) {
    Canvas(Modifier.size(26.dp)) {
        val stroke = 2.5.dp.toPx()
        drawCircle(ink, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawCircle(ink, radius = 4.dp.toPx())
    }
}

/** One line beside record: the recording, or what is wrong. */
@Composable
fun Chip(text: String, p: Palette, edge: Color = p.border, ink: Color = p.text) {
    BasicText(
        text,
        style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .background(p.surface, RoundedCornerShape(8.dp))
            .border(if (edge == p.border) 1.dp else 2.dp, edge, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
