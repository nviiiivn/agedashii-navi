package online.iamprettyfamous.navi.core.theme

import kotlin.math.roundToInt

/**
 * WCAG 2.x contrast, the one helper for the dash AND the map styles (agreed
 * with minion 3, 2026-09-27), so "readable in Sunlight at speed" is a number a
 * test asserts rather than something judged at a desk.
 *
 * THESE ARE FLOORS, NOT TARGETS. WCAG's 4.5:1 is for text on a screen someone
 * sits in front of. She reads this through a visor, in sunlight, at 60 mph, in
 * gloves - anything that only just clears a floor here is not "fine", it is the
 * least that is acceptable.
 */
object Contrast {
    /** Readout text: numbers, units, labels, a state colour used as text. WCAG 1.4.3 AA. */
    const val TEXT_FLOOR = 4.5

    /** Sunlight's whole job is direct sun: text there must clear WCAG AAA. */
    const val SUNLIGHT_TEXT_FLOOR = 7.0

    /** Non-text graphics: bars, dots, arrows, the route against the land beside it. WCAG 1.4.11. */
    const val GRAPHIC_FLOOR = 3.0

    fun textFloor(theme: String): Double = if (theme == "sunlight") SUNLIGHT_TEXT_FLOOR else TEXT_FLOOR

    fun ratio(a: Int, b: Int): Double = Themes.contrast(a, b)

    /** [fg] at [alpha] composited over [bg], in sRGB as Android blends - the glass over the map. */
    fun over(fg: Int, alpha: Float, bg: Int): Int {
        fun ch(shift: Int) = (alpha * (fg shr shift and 0xff) + (1 - alpha) * (bg shr shift and 0xff)).roundToInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
