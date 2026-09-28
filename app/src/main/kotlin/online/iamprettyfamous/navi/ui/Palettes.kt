package online.iamprettyfamous.navi.ui

import androidx.compose.ui.graphics.Color
import online.iamprettyfamous.navi.core.theme.Themes

/**
 * Her eight palettes as Compose colours. The values live in ONE place,
 * core's Themes (proved against theme.py by golden test); this is only the
 * adapter. Until the dash branch landed the app carried its own copy - all 88
 * values agreed, checked one by one at the merge, but two copies is how a
 * palette quietly forks.
 *
 * `warn`, `bad` and `good` are states, never decoration; `road` is the route
 * line, kept apart from `accent` so a route stands off already-coloured tiles.
 */
data class Palette(
    val key: String,
    val name: String,
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val warn: Color,
    val bad: Color,
    val good: Color,
    val road: Color,
)

private fun c(rgb: Int) = Color(0xFF000000 or rgb.toLong())

private fun from(p: online.iamprettyfamous.navi.core.theme.Palette) = Palette(
    p.key, p.name, c(p.bg), c(p.surface), c(p.border), c(p.text), c(p.text2), c(p.text3),
    c(p.accent), c(p.warn), c(p.bad), c(p.good), c(p.road),
)

/** In the picker's order (theme.py THEME_NAMES), which is also the order the theme button cycles. */
val PALETTES: List<Palette> = Themes.PICKER_ORDER.map { from(Themes[it]) }

/** A palette by key; an unknown one is Night, never an exception. */
fun palette(key: String): Palette = from(Themes[key])
