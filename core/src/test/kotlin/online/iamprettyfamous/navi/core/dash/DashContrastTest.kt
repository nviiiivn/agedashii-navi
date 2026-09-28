package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.theme.Contrast
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.Themes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Readable in the Sunlight theme at speed", as numbers the build must clear.
 *
 * Every floating gauge sits on glass - the theme's bg at [DashColours.GLASS_ALPHA]
 * over the map - so its real background is that glass composited over whatever
 * the map draws there. [mapFills] is the darkest and lightest area fill each
 * theme's map style can put under the glass, measured by minion 3 from the
 * generated styles (both schemas, all eight themes, 2026-09-27); they come from
 * theme.py and the style rules, so rebuilding tiles does not move them.
 */
class DashContrastTest {
    private val mapFills: Map<String, Pair<Int, Int>> = mapOf(
        "night" to (0x06080b to 0x0d252e),
        "day" to (0xd1e1ec to 0xeef1f5),
        "sunlight" to (0xe6ecf6 to 0xffffff),
        "redshift" to (0x000000 to 0x35130f),
        "cyberpunk" to (0x0a0612 to 0x092531),
        "matrix" to (0x000000 to 0x00240e),
        "pink" to (0x120810 to 0x3b1725),
        "cluster" to (0x080604 to 0x2c1f08),
    )

    /**
     * Roles the gauges ask for AS TEXT: numbers, units and labels, lean warming,
     * over-limit and shift. Each is checked as the colour actually drawn, through
     * [DashColours.textRole].
     */
    private val textRoles = listOf(Role.TEXT, Role.TEXT2, Role.WARN, Role.BAD)

    /** Roles the dash draws as graphics: bars, dots, arrows (the level arrow is TEXT2), the rev gradient. */
    private val graphicRoles = listOf(Role.ACCENT, Role.WARN, Role.BAD, Role.GOOD, Role.TEXT, Role.TEXT2)

    /** The colour a text role is drawn in, in that theme. */
    private fun ink(theme: String, role: Role): Int = Themes[theme][DashColours.textRole(theme, role)]

    private fun worstOnGlass(theme: String, colour: Int): Double {
        val p = Themes[theme]
        val (dark, light) = mapFills.getValue(theme)
        return listOf(dark, light).minOf { Contrast.ratio(colour, Contrast.over(p.bg, DashColours.GLASS_ALPHA, it)) }
    }

    @Test
    fun `the helper agrees with minion 3's gates`() {
        // One helper for the dash and the map styles: the same numbers both sides.
        assertEquals(21.0, Contrast.ratio(0xffffff, 0x000000), 1e-9)
        assertEquals(18.477, Contrast.ratio(Themes["night"].text, Themes["night"].bg), 0.0005)
        assertEquals(mapFills.keys, Themes.ALL.keys)
    }

    @Test
    fun `every readout clears the text floor on the glass over her real map`() {
        // No exceptions: the next drop below a floor is a regression, and fails the build.
        val below = mutableMapOf<String, Double>()
        for (theme in Themes.ALL.keys) {
            for (role in textRoles) {
                val worst = worstOnGlass(theme, ink(theme, role))
                if (worst < Contrast.textFloor(theme)) below["$theme ${role.name.lowercase()}"] = worst
            }
        }
        assertEquals(emptyMap<String, Double>(), below, "below the text floor on glass")
    }

    @Test
    fun `red night draws its words in one red, and keeps text2's hex`() {
        // Her choice, 2026-09-27: labels and units in `text`, only size separating
        // them from the numbers. The palette itself is not redefined.
        assertEquals(Role.TEXT, DashColours.textRole("redshift", Role.TEXT2))
        assertEquals(0xb32a22, Themes["redshift"].text2)
        for (theme in Themes.ALL.keys - "redshift") {
            for (role in Role.entries) assertEquals(role, DashColours.textRole(theme, role), "$theme $role")
        }
    }

    @Test
    fun `sunlight clears seven to one for everything it shows, graphics included`() {
        val p = Themes["sunlight"]
        for (role in (textRoles + graphicRoles).distinct()) {
            val worst = worstOnGlass("sunlight", p[role])
            assertTrue(worst >= Contrast.SUNLIGHT_TEXT_FLOOR, "sunlight $role is only $worst:1 on glass")
        }
    }

    @Test
    fun `every graphic clears three to one on the glass and on the gauge surfaces`() {
        for ((theme, p) in Themes.ALL) {
            for (role in graphicRoles) {
                val onGlass = worstOnGlass(theme, p[role])
                val onSurface = Contrast.ratio(p[role], p.surface)
                assertTrue(onGlass >= Contrast.GRAPHIC_FLOOR, "$theme $role on glass $onGlass:1")
                assertTrue(onSurface >= Contrast.GRAPHIC_FLOOR, "$theme $role on surface $onSurface:1")
            }
        }
    }

    @Test
    fun `the strip's solid band and the gauge boxes clear the text floor too`() {
        val below = mutableMapOf<String, Double>()
        for ((theme, p) in Themes.ALL) {
            for (role in textRoles) {
                val colour = ink(theme, role)
                val worst = minOf(Contrast.ratio(colour, p.bg), Contrast.ratio(colour, p.surface))
                if (worst < Contrast.textFloor(theme)) below["$theme ${role.name.lowercase()}"] = worst
            }
        }
        assertEquals(emptyMap<String, Double>(), below, "below the text floor on solid")
    }
}
