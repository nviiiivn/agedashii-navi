package online.iamprettyfamous.navi.ui

import online.iamprettyfamous.navi.core.theme.TypeScale

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

    /** Where the dash may start below the record row, whose lines run out to the right of it. */
    const val BELOW_RECORD_DP = EDGE_DP + TypeScale.TOUCH_DP + GAP_DP
}
