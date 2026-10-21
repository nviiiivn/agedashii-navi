package online.iamprettyfamous.navi.core.theme

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.obj
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Her eight palettes, against theme.py itself (tools/golden_dash.py reads the
 * module, not the spec's copy of it). theme.py is authoritative.
 */
class ThemesTest {
    private val golden = Golden.load("dash.json")

    private fun hex(value: Int) = "#%06x".format(value)

    @Test
    fun `every palette is theme py's, role for role`() {
        val themes = golden.obj("themes")
        assertEquals(themes.keys, Themes.ALL.keys)
        for ((key, roles) in themes) {
            val palette = Themes.ALL.getValue(key)
            for ((role, value) in roles.jsonObject) {
                assertEquals(value.jsonPrimitive.content.lowercase(), hex(palette[Role.valueOf(role.uppercase())]), "$key.$role")
            }
            assertEquals(11, roles.jsonObject.size, key)
        }
    }

    @Test
    fun `the dashboard palette is theme py's ALOLA`() {
        for ((role, value) in golden.obj("alola")) {
            assertEquals(value.jsonPrimitive.content.lowercase(), hex(Themes.ALOLA[Role.valueOf(role.uppercase())]), role)
        }
    }

    @Test
    fun `names, picker order, dark set, tiles and default are theme py's`() {
        val names = golden.obj("names")
        assertEquals(golden.arr("names_order").map { it.jsonPrimitive.content }, Themes.PICKER_ORDER)
        for (key in Themes.PICKER_ORDER) assertEquals(names.str(key), Themes.ALL.getValue(key).name)
        assertEquals(golden.arr("dark").map { it.jsonPrimitive.content }.toSet(), Themes.DARK)
        for ((key, value) in golden.obj("tiles")) assertEquals(value.jsonPrimitive.content, Themes.TILES[key], key)
        assertEquals(golden.str("default"), Themes.DEFAULT)
    }

    @Test
    fun `her two bike colours and the brand three`() {
        for ((key, value) in golden.obj("bike_colours")) assertEquals(value.jsonPrimitive.content, hex(Themes.BIKE_COLOURS.getValue(key)))
        for ((key, value) in golden.obj("bike_outline")) assertEquals(value.jsonPrimitive.content, hex(Themes.BIKE_OUTLINE.getValue(key)))
        assertEquals(golden.arr("brand").map { it.jsonPrimitive.content }, Themes.BRAND.map { hex(it) })
    }

    @Test
    fun `the type scale and the glove target are ui py's`() {
        val type = golden.obj("type")
        assertEquals(type.getValue("TOUCH").jsonPrimitive.int, TypeScale.TOUCH_DP)
        assertEquals(type.getValue("HUGE").jsonPrimitive.int, TypeScale.HUGE_SP)
        assertEquals(type.getValue("BIG").jsonPrimitive.int, TypeScale.BIG_SP)
        assertEquals(type.getValue("MID").jsonPrimitive.int, TypeScale.MID_SP)
        assertEquals(type.getValue("SMALL").jsonPrimitive.int, TypeScale.SMALL_SP)
    }

    @Test
    fun `an unknown theme falls back rather than raising`() {
        // test_core.py Themes: a deleted theme's name can still be in storage.
        assertEquals(Themes.ALL.getValue("night"), Themes["chartreuse"])
        assertEquals(Themes.ALL.getValue("night"), Themes[null])
    }

    @Test
    fun `the warning colours are never the accent`() {
        // Off-route has to be findable by colour alone. test_core.py Themes.
        for ((key, p) in Themes.ALL) {
            assertNotEquals(p[Role.BAD], p[Role.ACCENT], key)
            assertNotEquals(p[Role.WARN], p[Role.ACCENT], key)
        }
    }

    @Test
    fun `red night is red everywhere`() {
        // A single green chip would undo the whole point of a red screen.
        val p = Themes.ALL.getValue("redshift")
        for (role in listOf(Role.TEXT, Role.TEXT2, Role.ACCENT, Role.GOOD, Role.WARN, Role.BAD, Role.ROAD, Role.BORDER, Role.SURFACE)) {
            val c = p[role]
            val (r, g, b) = Triple(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)
            assertTrue(r >= g && r >= b, "$role ${hex(c)}")
        }
    }

    @Test
    fun `sunlight has no mid tones`() {
        // Direct sun, visor down: black on white, and every state dark enough to read.
        val p = Themes.ALL.getValue("sunlight")
        assertEquals(0xffffff, p[Role.BG])
        assertEquals(0x000000, p[Role.TEXT])
        for (role in listOf(Role.TEXT2, Role.TEXT3, Role.ACCENT, Role.WARN, Role.BAD, Role.GOOD)) {
            assertTrue(Themes.contrast(p[role], p[Role.BG]) >= 7.0, "$role ${hex(p[role])} is only ${Themes.contrast(p[role], p[Role.BG])}:1")
        }
    }
}
