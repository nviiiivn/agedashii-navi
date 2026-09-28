package online.iamprettyfamous.navi.dash

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.iamprettyfamous.navi.core.dash.DashColours
import online.iamprettyfamous.navi.core.theme.Contrast
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dash over the map that SHIPS: every road, casing and area fill each
 * theme's style paints, read from the style files in the APK's assets, under
 * that theme's glass. DashContrastTest measured area fills only, and on a real
 * screen Sunlight's black roads turned the glass #b8b8b8 grey under the
 * over-limit red (4.24:1). A style change that pulls a readout under its floor
 * now fails this build. Symbol layers (map labels) are left out: thin strokes
 * through the glass, and her call.
 */
class DashOverMapTest {
    private val styles = File("src/main/assets/styles")

    /** As DashContrastTest: what the dash draws as text (through textRole), and as graphics. */
    private val textRoles = listOf(Role.TEXT, Role.TEXT2, Role.WARN, Role.BAD)
    private val graphicRoles = listOf(Role.ACCENT, Role.WARN, Role.BAD, Role.GOOD, Role.TEXT, Role.TEXT2)

    /** Every colour a non-label layer paints, at full strength: a translucent line counted as opaque is the worst case. */
    private fun paints(theme: String): Set<Int> {
        val style = Json.parseToJsonElement(File(styles, "$theme.json").readText()).jsonObject
        val out = mutableSetOf<Int>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonPrimitive -> if (e.isString && e.content.startsWith("#")) {
                    // Every colour in the shipped styles is #rrggbb; anything else must fail, not be skipped.
                    val hex = e.content
                    assertTrue("$theme: unread colour $hex", hex.length == 7)
                    out += hex.substring(1).toInt(16)
                } else if (e.isString) {
                    assertTrue("$theme: unread colour ${e.content}", !e.content.startsWith("rgb") && !e.content.startsWith("hsl"))
                }
                is JsonArray -> e.forEach(::walk)
                is JsonObject -> e.values.forEach(::walk)
            }
        }
        for (layer in style.getValue("layers").jsonArray) {
            val l = layer.jsonObject
            if (l.getValue("type").jsonPrimitive.content != "symbol") l["paint"]?.let(::walk)
        }
        return out
    }

    @Test
    fun `every theme ships its own style`() {
        assertEquals(Themes.ALL.keys, styles.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet())
    }

    @Test
    fun `every readout clears its floor over every road and fill its own map paints`() {
        val below = mutableListOf<String>()
        for ((theme, p) in Themes.ALL) {
            val glass = paints(theme).map { Contrast.over(p.bg, DashColours.glassAlpha(theme), it) }
            fun worst(colour: Int) = glass.minOf { Contrast.ratio(colour, it) }
            val sun = theme == "sunlight"
            for (role in textRoles) {
                val w = worst(p[DashColours.textRole(theme, role)])
                if (w < Contrast.textFloor(theme)) below += "$theme text $role %.2f".format(w)
            }
            for (role in graphicRoles) {
                val w = worst(p[DashColours.textRole(theme, role)])
                if (w < (if (sun) Contrast.SUNLIGHT_TEXT_FLOOR else Contrast.GRAPHIC_FLOOR)) below += "$theme graphic $role %.2f".format(w)
            }
        }
        assertEquals("below the floor over the shipped map: $below", emptyList<String>(), below)
    }
}
