package online.iamprettyfamous.navi.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.Arrow
import online.iamprettyfamous.navi.core.dash.BandsItem
import online.iamprettyfamous.navi.core.dash.ClimbItem
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.dash.GBallItem
import online.iamprettyfamous.navi.core.dash.GForceItem
import online.iamprettyfamous.navi.core.dash.GearItem
import online.iamprettyfamous.navi.core.dash.Group
import online.iamprettyfamous.navi.core.dash.HorizonItem
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Item
import online.iamprettyfamous.navi.core.dash.MeterItem
import online.iamprettyfamous.navi.core.dash.Num
import online.iamprettyfamous.navi.core.dash.Region
import online.iamprettyfamous.navi.core.dash.RpmItem
import online.iamprettyfamous.navi.core.dash.Size
import online.iamprettyfamous.navi.core.dash.SpeedBlock
import online.iamprettyfamous.navi.core.guidance.LimitSource
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.ui.ButtonColumn
import online.iamprettyfamous.navi.ui.Palette
import androidx.compose.ui.geometry.Size as Box2

/*
 * The dash, drawn. Every number, word, angle and colour role comes from
 * core.dash (proved against gauges.py); this file only turns them into pixels,
 * with Compose foundation and Canvas - no Material, no icon font. Her design
 * brief is that this must not look like a Google app.
 *
 * No glow, no gradient behind text, no shadow: lovely on a desk, noise on a
 * windscreen. Numbers are the heaviest weight there is, because in direct sun
 * at 60 mph stroke width is legibility.
 */

fun sp(size: Size): TextUnit = when (size) {
    Size.HUGE -> TypeScale.HUGE_SP.sp
    Size.BIG -> TypeScale.BIG_SP.sp
    Size.MID -> TypeScale.MID_SP.sp
    Size.SMALL -> TypeScale.SMALL_SP.sp
}

private fun style(colour: Color, size: TextUnit, weight: FontWeight) = TextStyle(color = colour, fontSize = size, fontWeight = weight)

/** The colour for text in a role - every word and number, and the level arrow (see DashColours.textRole). */
private fun Palette.ink(role: Role): Color = this[DashColours.textRole(key, role)]

@Composable
private fun Label(text: String, p: Palette, colour: Color = p.ink(Role.TEXT2), modifier: Modifier = Modifier) =
    BasicText(text, modifier, style(colour, TypeScale.SMALL_SP.sp, FontWeight.SemiBold), maxLines = 1)

/**
 * A translucent slab over the map. Opaque would be a band - the thing being
 * avoided - and 0.72 is where tiles read as texture behind the numbers rather
 * than detail competing with them. Denser where the map's own roads would pull
 * a readout under its floor: DashColours.glassAlpha, proved over the shipped
 * styles by DashOverMapTest.
 */
@Composable
fun Glass(p: Palette, modifier: Modifier = Modifier, vertical: Dp = 6.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.background(p.bg.copy(alpha = DashColours.glassAlpha(p.key)), shape)
            .border(1.dp, p.border.copy(alpha = DashColours.GLASS_BORDER_ALPHA), shape)
            .padding(horizontal = 10.dp, vertical = vertical),
    ) { content() }
}

/** A number and its unit, the unit small on the baseline - so "60 mph" never reads as five digits. */
@Composable
fun NumView(n: Num, p: Palette) {
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(n.value, Modifier.alignByBaseline(), style(p.ink(n.role), sp(n.size), FontWeight.Black), maxLines = 1)
        if (n.unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            BasicText(n.unit, Modifier.alignByBaseline(), style(p.ink(Role.TEXT2), TypeScale.SMALL_SP.sp, FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Speed and its limit. A posted limit is framed like a sign; a typical one is plain - they never share a style. */
@Composable
fun SpeedBlockView(b: SpeedBlock, p: Palette) {
    Column {
        NumView(b.speed, p)
        if (b.limitSource == LimitSource.POSTED) {
            BasicText(
                b.limit,
                Modifier.border(1.dp, p.text, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                style(p.text, TypeScale.SMALL_SP.sp, FontWeight.Bold),
                maxLines = 1,
            )
        } else {
            Label(b.limit, p)
        }
    }
}

/** An arrow drawn, not a glyph from a font. [diagonal] tips UP and DOWN 45 degrees forward, for climb. */
@Composable
fun ArrowGlyph(arrow: Arrow, colour: Color, size: Dp, diagonal: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.14f
        fun line(a: Offset, b: Offset) = drawLine(colour, a, b, stroke, StrokeCap.Round)
        val tilt = when {
            !diagonal -> 0f
            arrow == Arrow.UP -> 45f
            arrow == Arrow.DOWN -> -45f
            else -> 0f
        }
        rotate(tilt) {
            when (arrow) {
                Arrow.UP -> {
                    line(Offset(w / 2, w * 0.85f), Offset(w / 2, w * 0.15f))
                    line(Offset(w / 2, w * 0.15f), Offset(w * 0.22f, w * 0.43f))
                    line(Offset(w / 2, w * 0.15f), Offset(w * 0.78f, w * 0.43f))
                }
                Arrow.DOWN -> {
                    line(Offset(w / 2, w * 0.15f), Offset(w / 2, w * 0.85f))
                    line(Offset(w / 2, w * 0.85f), Offset(w * 0.22f, w * 0.57f))
                    line(Offset(w / 2, w * 0.85f), Offset(w * 0.78f, w * 0.57f))
                }
                Arrow.LEVEL -> {
                    line(Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2))
                    line(Offset(w * 0.85f, w / 2), Offset(w * 0.57f, w * 0.22f))
                    line(Offset(w * 0.85f, w / 2), Offset(w * 0.57f, w * 0.78f))
                }
                Arrow.NONE -> line(Offset(w * 0.25f, w / 2), Offset(w * 0.75f, w / 2))
            }
        }
    }
}

/**
 * The attitude indicator: sky above, ground below, lean rotating the pair, pitch
 * raising it, all read against a fixed mark that does not move. The phone's own
 * roll is a thin needle, drawn only when it disagrees with the lean.
 */
@Composable
fun HorizonView(item: HorizonItem, p: Palette) {
    val h = item.horizon
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(item.sizeDp.dp)) {
            val s = size.width
            val c = center
            drawCircle(p.surface)
            // No lean, no horizon: an empty ball, never a level one (Gauges.PYTHON_WRONG).
            val rotation = h.rotationDeg
            if (rotation != null) clipPath(Path().apply { addOval(Rect(0f, 0f, s, s)) }) {
                rotate(rotation.toFloat(), c) {
                    // The band is two balls tall, so rotating never shows an edge;
                    // shift is a fraction of that band, as the Python's offset was.
                    translate(top = (h.shift * 2 * s).toFloat()) {
                        val sky = DashColours.HORIZON_SKY
                        val ground = DashColours.HORIZON_GROUND
                        drawRect(p[sky.role].copy(alpha = sky.alpha), Offset(-s / 2, -s / 2), Box2(2 * s, s))
                        drawRect(p[ground.role].copy(alpha = ground.alpha), Offset(-s / 2, s / 2), Box2(2 * s, s))
                        drawLine(p[DashColours.HORIZON_LINE], Offset(-s / 2, s / 2), Offset(1.5f * s, s / 2), 2.dp.toPx())
                    }
                }
            }
            drawCircle(p.border, style = Stroke(1.dp.toPx()))
            val mark = p[DashColours.HORIZON_MARK]
            val dot = 2.5.dp.toPx()
            val gap = 4.dp.toPx()
            val stub = 0.2f * s
            drawLine(mark, Offset(c.x - dot - gap - stub, c.y), Offset(c.x - dot - gap, c.y), 2.dp.toPx())
            drawLine(mark, Offset(c.x + dot + gap, c.y), Offset(c.x + dot + gap + stub, c.y), 2.dp.toPx())
            drawCircle(mark, dot, c)
            h.needleDeg?.let { needle ->
                rotate(needle.toFloat(), c) {
                    drawLine(p[DashColours.ROLL_NEEDLE], Offset(c.x, 0f), Offset(c.x, 0.42f * s), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Label(h.caption, p)
    }
}

/** The g-g diagram: two rings and a dot. */
@Composable
fun GBallView(item: GBallItem, p: Palette) {
    val b = item.ball
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(item.sizeDp.dp)) {
            val r = size.width / 2
            drawCircle(p.border, r - 0.5f, style = Stroke(1.dp.toPx()))
            drawCircle(p.border, r / 2, style = Stroke(1.dp.toPx()))
            val dot = 6.dp.toPx()
            // No g, no dot: a centred one would read as perfectly balanced (Gauges.PYTHON_WRONG).
            val x = b.x
            val y = b.y
            if (x != null && y != null) {
                drawCircle(p[b.dot], dot, Offset(center.x + (x * (r - dot)).toFloat(), center.y + (y * (r - dot)).toFloat()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Label(b.caption, p)
    }
}

private fun DrawScope.fillFromBottom(fraction: Double, colour: Color, radius: Float) {
    val h = size.height * fraction.toFloat()
    if (h > 0f) drawRoundRect(colour, Offset(0f, size.height - h), Box2(size.width, h), CornerRadius(radius))
}

/** A vertical bar with a peak tick. Empty for no signal - "no signal" must not look like "flat out". */
@Composable
fun MeterView(item: MeterItem, p: Palette, height: Dp = 92.dp) {
    val m = item.meter
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.width(26.dp).height(height).clip(RoundedCornerShape(6.dp)).background(p.surface)
                .border(1.dp, p.border, RoundedCornerShape(6.dp)),
        ) {
            fillFromBottom(m.fill, p[m.bar], 4.dp.toPx())
            m.tick?.let { t ->
                val y = size.height * (1f - t.toFloat())
                drawLine(p.text, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(4.dp))
        BasicText(m.value, style = style(p.text, TypeScale.SMALL_SP.sp, FontWeight.ExtraBold), maxLines = 1)
        Label(m.label, p)
    }
}

/** Height, and which way it is going: the arrow at a glance, the rate beneath. */
@Composable
fun ClimbView(item: ClimbItem, p: Palette, height: Dp = 92.dp) {
    val k = item.climb
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.height(height).background(p.surface, shape).border(1.dp, p.border, shape).padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowGlyph(k.arrow, p.ink(k.role), 20.dp, diagonal = true)
                Spacer(Modifier.width(6.dp))
                BasicText(k.height, style = style(p.text, TypeScale.MID_SP.sp, FontWeight.ExtraBold), maxLines = 1)
            }
            Label(k.detail, p)
            Label("altitude", p)
        }
    }
}

/** Her multi-peak meter: one thin bar per band, side by side. */
@Composable
fun BandsView(item: BandsItem, p: Palette, height: Dp = 92.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (band in item.bands) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(
                        Modifier.width(14.dp).height(height - 16.dp).clip(RoundedCornerShape(4.dp)).background(p.surface)
                            .border(1.dp, p.border, RoundedCornerShape(4.dp)),
                    ) { fillFromBottom(band.fill, p[band.role], 0f) }
                    Spacer(Modifier.height(3.dp))
                    Label(band.label, p)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Label(item.label, p)
    }
}

/** The gear, and the shift light as a coloured arrow that is always there - so nothing jumps as she reads it. */
@Composable
fun GearView(item: GearItem, p: Palette) {
    val g = item.badge
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(g.text, style = style(p.ink(g.textRole), sp(item.size), FontWeight.Black), maxLines = 1)
        Spacer(Modifier.width(4.dp))
        ArrowGlyph(g.arrow, p[g.arrowRole], (sp(item.size).value * 0.55f).dp)
    }
}

private fun DrawScope.track(p: Palette) {
    drawRoundRect(p.border.copy(alpha = 0.45f), cornerRadius = CornerRadius(size.height / 2))
}

/** Braking/drive and cornering, each growing from a centre line: where it grows TO is the reading. */
@Composable
fun GForceView(item: GForceItem, p: Palette) {
    val width = item.widthDp.dp
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (bar in listOf(item.bars.long, item.bars.lat)) {
            Canvas(Modifier.width(width).height(10.dp).clip(RoundedCornerShape(5.dp))) {
                track(p)
                drawRect(p[bar.role], Offset(size.width * bar.left.toFloat(), 0f), Box2(size.width * bar.fill.toFloat(), size.height))
            }
        }
        Row(Modifier.width(width)) {
            Label("BRK", p)
            Spacer(Modifier.weight(1f))
            Label("ACC", p)
        }
    }
}

/** Engine speed as one bar reddening towards the redline; the redline zone is a state, so it may be red. */
@Composable
fun RpmView(item: RpmItem, p: Palette) {
    val r = item.line
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.width(item.widthDp.dp).height(8.dp).clip(RoundedCornerShape(4.dp))) {
            track(p)
            val w = size.width * r.fill.toFloat()
            if (w > 0f) {
                drawRect(Brush.horizontalGradient(DashColours.REV_ZONES.map { p[it] }, startX = 0f, endX = w), size = Box2(w, size.height))
            }
        }
        Label(r.text, p, p.ink(r.textRole))
    }
}

@Composable
fun ItemView(item: Item, p: Palette) = when (item) {
    is Num -> NumView(item, p)
    is SpeedBlock -> SpeedBlockView(item, p)
    is GForceItem -> GForceView(item, p)
    is RpmItem -> RpmView(item, p)
    is GearItem -> GearView(item, p)
    is HorizonItem -> HorizonView(item, p)
    is GBallItem -> GBallView(item, p)
    is ClimbItem -> ClimbView(item, p)
    is MeterItem -> MeterView(item, p)
    is BandsItem -> BandsView(item, p)
}

/**
 * A group wraps onto another line rather than scrolling: nothing here can be
 * touched while riding, so a gauge scrolled off the edge is a gauge she never
 * sees. The portrait ribbon lost gear and altitude that way, and the strip
 * its engine bands - seen in the screenshots, with every contrast test green.
 */
@Composable
private fun GroupView(group: Group, p: Palette, spacing: Dp) {
    if (group.vertical) {
        FlowColumn(verticalArrangement = Arrangement.spacedBy(spacing), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            for (item in group.items) ItemView(item, p)
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            for (item in group.items) ItemView(item, p)
        }
    }
}

/**
 * The planned HUD, laid over the map. Every layout floats except STRIP, which
 * is the one view of everything at once and takes its own band at the bottom.
 * Nothing here is tappable: the dash is read, not touched, while riding.
 */
@Composable
fun HudOverlay(hud: Hud?, p: Palette, modifier: Modifier = Modifier, linesDp: Int = 0) {
    if (hud == null) return
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        for (group in hud.groups) RegionView(group, hud, p, linesDp)
    }
}

/**
 * Extra (start, top) that keeps a group off the button column, from
 * [ButtonColumn] so it cannot drift from the buttons. Anything down the start
 * edge clears the buttons and the record button; anything at the top also
 * clears the lines beside record, [linesDp] tall as measured - the recording
 * line stays up the whole ride and runs most of a portrait screen wide, and a
 * status line under it comes and goes. The column is about 350 dp tall: clear
 * of the bottom of a portrait screen, but the whole height of a landscape one,
 * so the bottom corner and the strip clear it only there.
 */
private fun clearance(region: Region, hud: Hud, linesDp: Int): Pair<Dp, Dp> {
    val beside = ButtonColumn.CLEAR_DP.dp
    val belowLines = ButtonColumn.belowLines(linesDp)
    val below = maxOf(ButtonColumn.BELOW_RECORD_DP, belowLines).dp
    return when {
        region == Region.TOP_START -> beside to below
        region == Region.EDGE && (hud.anchor == Anchor.TOP || hud.anchor == Anchor.LEFT) -> beside to below
        region == Region.TOP_END -> 0.dp to belowLines.dp
        region == Region.BOTTOM_START || region == Region.STRIP -> (if (hud.wide) beside else 0.dp) to 0.dp
        else -> 0.dp to 0.dp
    }
}

@Composable
private fun BoxScope.RegionView(group: Group, hud: Hud, p: Palette, linesDp: Int) {
    val (start, top) = clearance(group.region, hud, linesDp)
    when (group.region) {
        Region.STRIP -> Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(p.bg).border(1.dp, p.border)
                .padding(start = 10.dp + start, top = 10.dp, end = 10.dp, bottom = 10.dp),
        ) { GroupView(group, p, 10.dp) }
        Region.CENTER -> Box(Modifier.align(Alignment.Center)) { GroupView(group, p, 4.dp) }
        else -> {
            val align = when (group.region) {
                Region.TOP_START -> Alignment.TopStart
                Region.TOP_END -> Alignment.TopEnd
                Region.BOTTOM_START -> Alignment.BottomStart
                Region.BOTTOM_END -> Alignment.BottomEnd
                else -> when (hud.anchor) {
                    Anchor.TOP -> Alignment.TopCenter
                    Anchor.LEFT -> Alignment.CenterStart
                    Anchor.RIGHT -> Alignment.CenterEnd
                    Anchor.BOTTOM -> Alignment.BottomCenter
                }
            }
            val spacing = when {
                group.region == Region.EDGE && !group.vertical -> 14.dp
                group.region == Region.EDGE -> 12.dp
                group.items.size > 1 -> 4.dp
                else -> 0.dp
            }
            val margin = Modifier.align(align).padding(start = 8.dp + start, top = 10.dp + top, end = 8.dp, bottom = 10.dp)
            Glass(p, margin, vertical = if (group.region == Region.EDGE) 8.dp else 6.dp) {
                GroupView(group, p, spacing)
            }
        }
    }
}
