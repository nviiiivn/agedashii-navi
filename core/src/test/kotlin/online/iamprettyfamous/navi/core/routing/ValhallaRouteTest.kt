package online.iamprettyfamous.navi.core.routing

import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.guidance.ManeuverKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Valhalla's /route reply into a RouteLine. The whole route has been matched
 * against pyvalhalla on the same tar (20.63 mi, 37.7 min, on the emulator); these
 * pin the pieces: the polyline at 1e6, lengths in the request's units, each
 * maneuver at its shape index, and the kind from Valhalla's own type.
 */
class ValhallaRouteTest {
    // Google's polyline example, read at Valhalla's precision (1e6 not 1e5):
    // the same characters decode to a tenth of Google's degrees.
    private val shape = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `the polyline is decoded at a millionth of a degree`() {
        val p = ValhallaRoute.decodePolyline6(shape)
        assertEquals(3, p.size)
        assertEquals(3.85, p[0].lat, 1e-9)
        assertEquals(-12.02, p[0].lon, 1e-9)
        assertEquals(4.07, p[1].lat, 1e-9)
        assertEquals(-12.095, p[1].lon, 1e-9)
        assertEquals(4.3252, p[2].lat, 1e-9)
        assertEquals(-12.6453, p[2].lon, 1e-9)
    }

    private fun reply(units: String) = """
        {"trip":{"units":"$units","summary":{"length":0.75,"time":90},
         "legs":[{"shape":"$shape","maneuvers":[
           {"type":1,"instruction":"Drive north on A Street.","begin_shape_index":0,"length":0.5,"street_names":["A Street"]},
           {"type":10,"instruction":"Turn right onto B Street.","begin_shape_index":1,"length":0.25,"street_names":["B Street"]},
           {"type":4,"instruction":"You have arrived at your destination.","begin_shape_index":2,"length":0.0}
         ]}]}}
    """.trimIndent()

    @Test
    fun `miles come back as metres and each maneuver sits at its shape index`() {
        val r = ValhallaRoute.parse(reply("miles"))
        assertTrue(r.ok)
        assertEquals("offline", r.source)
        assertEquals(0.75 * 1609.344, r.distanceM, 1e-6)
        assertEquals(90.0, r.durationS)
        assertEquals(listOf(0, 1, 2), r.maneuvers.map { it.index })
        assertEquals(r.points[1], r.maneuvers[1].at)
        assertEquals(0.5 * 1609.344, r.maneuvers[0].distanceM, 1e-6)
        assertEquals(Geo.haversineM(r.points[0], r.points[1]), r.maneuvers[1].cumM, 1e-6)
        assertEquals("B Street", r.maneuvers[1].street)
    }

    @Test
    fun `kilometres are kilometres`() {
        assertEquals(750.0, ValhallaRoute.parse(reply("kilometers")).distanceM, 1e-6)
    }

    @Test
    fun `each maneuver carries Valhalla's own kind`() {
        val kinds = ValhallaRoute.parse(reply("miles")).maneuvers.map { it.kind }
        assertEquals(listOf(ManeuverKind.START, ManeuverKind.TURN_RIGHT, ManeuverKind.ARRIVE), kinds)
    }

    @Test
    fun `an error is a route that is not ok, with the router's reason`() {
        val r = ValhallaRoute.parse("""{"error_code":442,"error":"No path could be found for input","status_code":400}""")
        assertFalse(r.ok)
        assertEquals("No path could be found for input", r.reason)
    }

    @Test
    fun `nonsense is not ok and not a crash`() {
        assertFalse(ValhallaRoute.parse("not json").ok)
        assertFalse(ValhallaRoute.parse("""{"trip":{"legs":[]}}""").ok)
    }

    @Test
    fun `two legs share their joining point`() {
        val two = """
            {"trip":{"units":"miles","summary":{"length":1,"time":1},"legs":[
              {"shape":"$shape","maneuvers":[{"type":1,"begin_shape_index":0},{"type":4,"begin_shape_index":2}]},
              {"shape":"$shape","maneuvers":[{"type":1,"begin_shape_index":0},{"type":4,"begin_shape_index":2}]}]}}
        """.trimIndent()
        val r = ValhallaRoute.parse(two)
        assertEquals(5, r.points.size)
        assertEquals(listOf(0, 2, 2, 4), r.maneuvers.map { it.index })
        assertEquals(LatLon(4.3252, -12.6453), r.points[4])
    }
}
