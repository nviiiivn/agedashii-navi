package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.dash.TurnBanner.Glyph
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.theme.Contrast
import online.iamprettyfamous.navi.core.theme.Themes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ride.py's turn_band, planned; and never an assertion it cannot back. */
class TurnBannerTest {
    private val turning = Status(
        ok = true, instruction = "Turn right onto 12th Street", street = "12th Street",
        icon = Guidance.turnIcon("Turn right onto 12th Street"), turnInM = 122.0, eta = "10:42",
    )

    @Test
    fun `no route, no banner`() {
        assertNull(TurnBanner.plan(null, imperial = true))
        assertNull(TurnBanner.plan(Status(ok = false, reason = "no route"), imperial = true))
    }

    @Test
    fun `a turn reads as ride py drew it`() {
        val b = TurnBanner.plan(turning, imperial = true)!!
        assertEquals(Glyph.RIGHT, b.glyph)
        assertEquals(TurnBanner.TURN, b.glyphRole)
        assertEquals("Turn right onto 12th Street", b.title)
        assertEquals("400 ft · 12th Street", b.detail)
        assertEquals("10:42", b.eta)
        assertEquals("120 m · 12th Street", TurnBanner.plan(turning, imperial = false)!!.detail)
    }

    @Test
    fun `no distance is a dash, never 0 ft`() {
        val b = TurnBanner.plan(turning.copy(turnInM = null, street = ""), imperial = true)!!
        assertEquals(Gauges.DASH, b.detail)
    }

    @Test
    fun `no instruction is a dash and no arrow - never continue, never straight on`() {
        val b = TurnBanner.plan(turning.copy(instruction = "", icon = Guidance.turnIcon("")), imperial = true)!!
        assertEquals(Gauges.DASH, b.title)
        assertNull(b.glyph, "turnIcon(\"\") is straight; drawing it would assert a road ahead it does not know")
        assertEquals(setOf("instruction", "glyph"), TurnBanner.PYTHON_WRONG.keys)
        for (why in TurnBanner.PYTHON_WRONG.values) assertTrue(why.startsWith("ride.py is wrong here"))
    }

    @Test
    fun `off route is the alarm, with how far and what is happening`() {
        val b = TurnBanner.plan(turning.copy(offRoute = true, offRouteM = 45.7), imperial = true)!!
        assertTrue(b.offRoute)
        assertEquals(TurnBanner.OFF_ROUTE, b.glyphRole)
        assertEquals("off route", b.title)
        assertEquals("150 ft from the line - re-routing", b.detail)
    }

    @Test
    fun `arrival names the destination, or a dash`() {
        val done = turning.copy(arrived = true, instruction = "You have arrived at your destination", icon = "flag")
        assertEquals("Calaveras Road", TurnBanner.plan(done, true, "Calaveras Road")!!.detail)
        assertEquals(Gauges.DASH, TurnBanner.plan(done, true, null)!!.detail)
        assertEquals(Glyph.FLAG, TurnBanner.plan(done, true)!!.glyph)
    }

    @Test
    fun `every icon Guidance can name has a glyph`() {
        val words = listOf(
            "Make a U-turn", "You have arrived at your destination", "Turn sharp left", "Bear left", "Turn left",
            "Turn sharp right", "Keep slight right", "Turn right", "Continue on I-880",
        )
        for (w in words) assertNotNull(TurnBanner.glyphOf(Guidance.turnIcon(w)), w)
        assertEquals(Glyph.entries.size, words.map { TurnBanner.glyphOf(Guidance.turnIcon(it)) }.toSet().size)
    }

    @Test
    fun `the banner's colours clear their floors on its own solid surface`() {
        for ((theme, p) in Themes.ALL) {
            val surface = p.surface
            for (role in listOf(TurnBanner.TITLE, TurnBanner.DETAIL, TurnBanner.ETA)) {
                val r = Contrast.ratio(p[DashColours.textRole(theme, role)], surface)
                assertTrue(r >= Contrast.textFloor(theme), "$theme banner text $role $r:1")
            }
            val graphic = if (theme == "sunlight") Contrast.SUNLIGHT_TEXT_FLOOR else Contrast.GRAPHIC_FLOOR
            for (role in listOf(TurnBanner.TURN, TurnBanner.OFF_ROUTE)) {
                val r = Contrast.ratio(p[role], surface)
                assertTrue(r >= graphic, "$theme banner glyph $role $r:1")
            }
        }
    }
}
