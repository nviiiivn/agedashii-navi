package online.iamprettyfamous.navi.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil

/**
 * Where the map camera centres her bike: in the middle of the map that nothing
 * covers. The four paddings, in pixels, for MapLibre (left, top, right, bottom).
 *
 * Sideways on the emulator (ride v8), a two-line exit instruction with the route
 * and mic lines under it covered 60% of the height; the camera centred the bike
 * below that, and the dash's edge panel - pushed down by the same stack - sat
 * right on top of it. Now the dash's panels (HudOverlay's boxes, minion 2) are
 * cleared as well as the banner stack.
 */
object CameraClear {
    /** A panel this close to an edge is attached to it (the dash's margin plus an inset), in dp. */
    private const val EDGE_DP = 48f

    /** Dash paddings go up in these steps, so a gauge's digits changing width never nudges the camera. */
    private const val STEP_DP = 24f

    /** The free map is never squeezed below this, whatever covers the rest, in dp. */
    private const val MIN_FREE_DP = 120f

    data class Pad(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0) {
        fun array() = doubleArrayOf(left, top, right, bottom)
    }

    /**
     * [topPx]: the banner stack's clearance from the top of the window. [dash]: the
     * dash's panel boxes in window pixels. [wide]: sideways, where there is width
     * to spare, so panels are cleared sideways first; upright, top or bottom first.
     * [columnPx]: the button column's width, cleared sideways only - upright the
     * bike stays in the middle under the banner, as it always has.
     */
    fun of(widthPx: Float, heightPx: Float, density: Float, topPx: Double, dash: List<Rect>, wide: Boolean, columnPx: Float): Pad {
        if (widthPx <= 0f || heightPx <= 0f) return Pad(top = topPx)
        val edge = EDGE_DP * density
        val step = STEP_DP * density
        var left = if (wide) columnPx.toDouble() else 0.0
        var top = topPx
        var right = 0.0
        var bottom = 0.0
        for (r in dash) {
            // What it would take to clear this panel from each edge it is attached to.
            val sideways = listOfNotNull(
                (r.right.toDouble() to 'L').takeIf { r.left <= edge },
                ((widthPx - r.left).toDouble() to 'R').takeIf { r.right >= widthPx - edge },
            ).filter { it.first < widthPx / 2 }
            val upright = listOfNotNull(
                (r.bottom.toDouble() to 'T').takeIf { r.top <= edge + topPx },
                ((heightPx - r.top).toDouble() to 'B').takeIf { r.bottom >= heightPx - edge },
            ).filter { it.first < heightPx / 2 }
            // A panel attached to no edge (the centre readout) is meant to sit over the map.
            val (px, side) = (if (wide) sideways.ifEmpty { upright } else upright.ifEmpty { sideways })
                .minByOrNull { it.first } ?: continue
            val stepped = ceil(px / step) * step
            when (side) {
                'L' -> left = maxOf(left, stepped)
                'R' -> right = maxOf(right, stepped)
                'T' -> top = maxOf(top, stepped)
                else -> bottom = maxOf(bottom, stepped)
            }
        }
        // Never squeeze the free map to nothing: the dash's side gives way first.
        val minFree = MIN_FREE_DP * density
        right = right.coerceAtMost(maxOf(0.0, widthPx - minFree - left))
        bottom = bottom.coerceAtMost(maxOf(0.0, heightPx - minFree - top))
        return Pad(left, top, right, bottom)
    }
}
