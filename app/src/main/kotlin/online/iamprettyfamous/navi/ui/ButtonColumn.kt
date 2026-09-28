package online.iamprettyfamous.navi.ui

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.theme.TypeScale
import kotlin.math.ceil

/**
 * The round-button column down the start edge (spec section 03): record with
 * its lines beside it, then theme, bike, layout and follow. Its geometry is
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

    /** Where the dash may start below the record button. */
    const val BELOW_RECORD_DP = EDGE_DP + TypeScale.TOUCH_DP + GAP_DP

    /** Where the lines beside record start. */
    const val LINES_START_DP = EDGE_DP + TypeScale.TOUCH_DP + BESIDE_DP

    /**
     * The widest the lines grow before wrapping: under half a landscape screen,
     * so they never reach a gauge column down the far edge. In portrait the
     * screen is narrower than this, and everything at the top clears them.
     */
    const val LINES_MAX_DP = 360

    /** Where the dash may start below the lines, [linesDp] tall as measured - they are text, so never assumed. */
    fun belowLines(linesDp: Int): Int = if (linesDp == 0) 0 else EDGE_DP + linesDp + GAP_DP
}

/**
 * The column and the lines beside record, positioned in one place: MainActivity
 * fills it, and so does the screenshot test, so the pictures show the real
 * layout. The lines sit OUT of the column's flow: one appearing never moves a
 * button under a glove. [onLinesHeight] reports their height in dp, because
 * the dash moves out from under them.
 */
@Composable
fun BoxScope.ButtonColumnLayout(
    onLinesHeight: (Int) -> Unit,
    lines: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(ButtonColumn.EDGE_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ButtonColumn.GAP_DP.dp),
        content = buttons,
    )
    Column(
        Modifier.align(Alignment.TopStart).safeDrawingPadding()
            .padding(start = ButtonColumn.LINES_START_DP.dp, top = ButtonColumn.EDGE_DP.dp, end = ButtonColumn.EDGE_DP.dp)
            .widthIn(max = ButtonColumn.LINES_MAX_DP.dp)
            .onSizeChanged { onLinesHeight(ceil(it.height / density.density).toInt()) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = lines,
    )
}

/** 56 dp: the spec's minimum target, for gloves. */
@Composable
fun RoundButton(label: String, p: Palette, ink: Color = p.accent, onClick: () -> Unit) {
    Box(
        Modifier.size(TypeScale.TOUCH_DP.dp)
            .background(p.surface, CircleShape)
            .border(1.dp, p.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
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
