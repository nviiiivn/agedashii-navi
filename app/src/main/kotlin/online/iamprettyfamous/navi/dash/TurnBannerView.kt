package online.iamprettyfamous.navi.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.dash.TurnBanner
import online.iamprettyfamous.navi.core.dash.TurnBanner.Glyph
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.ui.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * The turn banner, drawn: a solid band on the theme's surface - never glass, so
 * the map under it cannot move its contrast - with the turn as a drawn glyph,
 * the instruction at BIG, the distance and street under it, and the arrival
 * time at the end. Off-route rings it in the alarm colour. Nothing is tappable.
 */
@Composable
fun TurnBannerView(plan: TurnBanner.Plan, p: Palette) {
    val shape = RoundedCornerShape(12.dp)
    val edge = if (plan.offRoute) p[TurnBanner.OFF_ROUTE] else p.border
    Row(
        Modifier.fillMaxWidth().background(p.surface, shape).border(if (plan.offRoute) 3.dp else 1.dp, edge, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The box stays when there is no glyph, so the words never jump sideways.
        Box(Modifier.size(GLYPH)) { plan.glyph?.let { TurnGlyph(it, p[plan.glyphRole], GLYPH) } }
        Column(Modifier.weight(1f)) {
            BasicText(
                plan.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = p.ink(TurnBanner.TITLE), fontSize = TypeScale.BIG_SP.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp),
            )
            // Two lines: at one, portrait cut "re-routing" off the off-route line (seen in the screenshots).
            BasicText(
                plan.detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = p.ink(TurnBanner.DETAIL), fontSize = TypeScale.MID_SP.sp, fontWeight = FontWeight.Bold),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            BasicText(plan.eta, maxLines = 1, style = TextStyle(color = p.ink(TurnBanner.ETA), fontSize = TypeScale.MID_SP.sp, fontWeight = FontWeight.Bold))
            BasicText(TurnBanner.ETA_LABEL, maxLines = 1, style = TextStyle(color = p.ink(TurnBanner.DETAIL), fontSize = TypeScale.SMALL_SP.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

private val GLYPH = 44.dp

/**
 * A turn as a drawn path - no icon font. A stem from below, then the bend:
 * a quarter turn for left and right, an eighth for slight, three eighths for
 * sharp - shapes that differ in angle, which is what a glance can tell apart.
 */
@Composable
fun TurnGlyph(glyph: Glyph, colour: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(x: Float, y: Float) = Offset(x * s, y * s)
        when (glyph) {
            Glyph.STRAIGHT -> arrow(at(0.5f, 0.92f), at(0.5f, 0.5f), 0f, 0.4f, colour, stroke)
            Glyph.LEFT -> arrow(at(0.62f, 0.92f), at(0.62f, 0.5f), -90f, 0.44f, colour, stroke)
            Glyph.RIGHT -> arrow(at(0.38f, 0.92f), at(0.38f, 0.5f), 90f, 0.44f, colour, stroke)
            Glyph.SLIGHT_LEFT -> arrow(at(0.6f, 0.92f), at(0.6f, 0.55f), -45f, 0.42f, colour, stroke)
            Glyph.SLIGHT_RIGHT -> arrow(at(0.4f, 0.92f), at(0.4f, 0.55f), 45f, 0.42f, colour, stroke)
            Glyph.SHARP_LEFT -> arrow(at(0.64f, 0.92f), at(0.64f, 0.3f), -135f, 0.42f, colour, stroke)
            Glyph.SHARP_RIGHT -> arrow(at(0.36f, 0.92f), at(0.36f, 0.3f), 135f, 0.42f, colour, stroke)
            Glyph.U_TURN -> {
                // Up on the right, over the top, and back down on the left.
                val path = Path().apply {
                    moveTo(0.68f * s, 0.92f * s)
                    lineTo(0.68f * s, 0.42f * s)
                    arcTo(Rect(0.3f * s, 0.24f * s, 0.68f * s, 0.62f * s), 0f, -180f, false)
                    lineTo(0.3f * s, 0.7f * s)
                }
                drawPath(path, colour, style = stroke)
                head(at(0.3f, 0.78f), 180f, colour, stroke)
            }
            Glyph.FLAG -> {
                drawLine(colour, at(0.3f, 0.94f), at(0.3f, 0.1f), stroke.width, StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(0.3f * s, 0.1f * s)
                    lineTo(0.84f * s, 0.27f * s)
                    lineTo(0.3f * s, 0.46f * s)
                    close()
                }
                drawPath(flag, colour)
            }
        }
    }
}

/** A stem from [from] to [bend], then [length] (of the glyph) at [angle] degrees from straight up, with a head. */
private fun DrawScope.arrow(from: Offset, bend: Offset, angle: Float, length: Float, colour: Color, stroke: Stroke) {
    val s = size.minDimension
    val rad = Math.toRadians(angle.toDouble())
    val tip = Offset(bend.x + (sin(rad) * length * s).toFloat(), bend.y - (cos(rad) * length * s).toFloat())
    val path = Path().apply {
        moveTo(from.x, from.y)
        lineTo(bend.x, bend.y)
        lineTo(tip.x, tip.y)
    }
    drawPath(path, colour, style = stroke)
    head(tip, angle, colour, stroke)
}

/** An arrowhead at [tip] pointing [angle] degrees from straight up. */
private fun DrawScope.head(tip: Offset, angle: Float, colour: Color, stroke: Stroke) {
    val s = size.minDimension
    for (side in listOf(-1, 1)) {
        val back = Math.toRadians((angle + 180 + side * 40).toDouble())
        val end = Offset(tip.x + (sin(back) * 0.24f * s).toFloat(), tip.y - (cos(back) * 0.24f * s).toFloat())
        drawLine(colour, tip, end, stroke.width, StrokeCap.Round)
    }
}
