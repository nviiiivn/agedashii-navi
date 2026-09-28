package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 29-channel vocabulary. Spec section 06 and rule 3: every value is labelled
 * measured, derived or estimated, and a gauge, an export column or a widget spec
 * names a channel and keeps working.
 */
class ChannelsTest {
    @Test
    fun `every channel is the Python's, in the Python's order, with its kind, unit and meaning`() {
        val want = Golden.load("telemetry.json").arr("channels").map { row ->
            val parts = row.jsonArray.map { it.jsonPrimitive.content }
            listOf(parts[0], parts[1], parts[2], parts[3])
        }
        assertEquals(want, Channels.ALL.map { listOf(it.name, it.kind.wire, it.unit, it.about) })
        assertEquals(29, Channels.ALL.size)
        assertEquals(Channels.ALL.map { it.name }, Channels.ORDER)
    }

    @Test
    fun `every channel declares one of the known kinds`() {
        // test_core.py:176-178.
        assertTrue(Channels.ALL.all { it.kind.wire in setOf("measured", "derived", "estimated") })
    }

    @Test
    fun `the kinds read off the table in order`() {
        assertEquals(listOf("exhaust_hz", "rpm_est"), Channels.kinds(Kind.ESTIMATED))
        assertEquals("g_long", Channels.kinds(Kind.DERIVED).first())
        assertEquals(Kind.DERIVED, Channels["lean_deg"]!!.kind)
        assertEquals(null, Channels["nope"])
    }

    @Test
    fun `the phone's roll is never called the bike's lean`() {
        // Spec section 07: tilt_deg is the PHONE; the UI must never conflate them.
        assertTrue("PHONE" in Channels["tilt_deg"]!!.about)
    }
}
