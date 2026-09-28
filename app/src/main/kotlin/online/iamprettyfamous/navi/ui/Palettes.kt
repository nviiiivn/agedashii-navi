package online.iamprettyfamous.navi.ui

import androidx.compose.ui.graphics.Color
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.Themes
import online.iamprettyfamous.navi.core.theme.Palette as CorePalette

/**
 * Her eight palettes as Compose colours. The values are core.theme.Themes, which
 * ThemesTest proves against apps/navi theme.py itself - so there is one copy of
 * every hex, and it is the tested one. Any widget is written against a [Palette]
 * and never against a named theme.
 *
 * `warn`, `bad` and `good` are states, never decoration; `road` is the ROUTE
 * line, kept apart from `accent` so a route stands off already-coloured tiles -
 * never the colour of the street network.
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
) {
    operator fun get(role: Role): Color = when (role) {
        Role.BG -> bg
        Role.SURFACE -> surface
        Role.BORDER -> border
        Role.TEXT -> text
        Role.TEXT2 -> text2
        Role.TEXT3 -> text3
        Role.ACCENT -> accent
        Role.WARN -> warn
        Role.BAD -> bad
        Role.GOOD -> good
        Role.ROAD -> road
    }

    val isDark: Boolean get() = Themes.isDark(key)
}

fun rgb(value: Int) = Color(0xFF000000.toInt() or value)

private fun CorePalette.compose() = Palette(
    key, name, rgb(bg), rgb(surface), rgb(border), rgb(text), rgb(text2), rgb(text3),
    rgb(accent), rgb(warn), rgb(bad), rgb(good), rgb(road),
)

/** In the picker's order (theme.py THEME_NAMES), which is also the order the theme button cycles. */
val PALETTES: List<Palette> = Themes.PICKER_ORDER.map { Themes.ALL.getValue(it).compose() }

/** The dashboard's palette, for surfaces that are not the map when alola chrome is on. */
val ALOLA: Palette = Themes.ALOLA.compose()

/** A palette by key; an unknown one is Night, never an exception. */
fun palette(key: String): Palette = PALETTES.firstOrNull { it.key == key } ?: PALETTES.first { it.key == Themes.DEFAULT }
