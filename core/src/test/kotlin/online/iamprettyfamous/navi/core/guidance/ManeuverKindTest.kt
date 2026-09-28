package online.iamprettyfamous.navi.core.guidance

import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.Maneuver
import online.iamprettyfamous.navi.core.geo.RouteLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The clip keys: a contract with her recordings, and one classifier for both apps. */
class ManeuverKindTest {
    @Test
    fun `the eleven names are exactly the ones she was given`() {
        assertEquals(
            listOf(
                "turn-left", "turn-right", "slight-left", "slight-right", "sharp-left", "sharp-right",
                "uturn", "continue", "merge", "exit", "arrive",
            ),
            ManeuverKind.MANEUVERS,
        )
        assertEquals("start", ManeuverKind.START)
    }

    @Test
    fun `every Valhalla maneuver type is a clip, the start, or honestly none`() {
        val none = setOf(0, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 39, 40, 41, 42, 43)
        for (type in 0..43) {
            val kind = ManeuverKind.fromValhalla(type)
            if (type in none) assertNull(kind, "type $type has no honest clip") else {
                assertTrue(kind in ManeuverKind.MANEUVERS || kind == ManeuverKind.START, "type $type -> $kind")
            }
        }
        assertNull(ManeuverKind.fromValhalla(null))
    }

    @Test
    fun `the turns are Valhalla's own`() {
        val want = mapOf(
            9 to "slight-right", 10 to "turn-right", 11 to "sharp-right", 12 to "uturn", 13 to "uturn",
            14 to "sharp-left", 15 to "turn-left", 16 to "slight-left", 4 to "arrive", 5 to "arrive", 6 to "arrive",
            8 to "continue", 20 to "exit", 21 to "exit", 25 to "merge", 37 to "merge", 38 to "merge",
        )
        for ((type, kind) in want) assertEquals(kind, ManeuverKind.fromValhalla(type), "type $type")
    }

    @Test
    fun `a ramp is not an exit - an on-ramp announced as one is wrong`() {
        assertEquals("slight-right", ManeuverKind.fromValhalla(18))
        assertEquals("slight-left", ManeuverKind.fromValhalla(19))
        assertEquals("continue", ManeuverKind.fromValhalla(17))
    }

    @Test
    fun `turn degrees fall in Valhalla 3_6_3's bands, edge by edge`() {
        val edges = mapOf(
            0 to "continue", 10 to "continue", 11 to "slight-right", 44 to "slight-right", 45 to "turn-right",
            135 to "turn-right", 136 to "sharp-right", 159 to "sharp-right", 160 to "uturn", 200 to "uturn",
            201 to "sharp-left", 224 to "sharp-left", 225 to "turn-left", 315 to "turn-left", 316 to "slight-left",
            349 to "slight-left", 350 to "continue", 359 to "continue", 360 to "continue", -10 to "continue", -45 to "turn-left",
        )
        for ((deg, kind) in edges) assertEquals(kind, ManeuverKind.fromTurnDegree(deg), "$deg degrees")
    }

    @Test
    fun `status carries the kind and the band, apart from the words`() {
        val points = (0..40).map { LatLon(37.80 + it * 0.0005, -122.27) }
        val cum = Geo.cumulativeM(points)
        // A real route's shape: a start, the turn, an arrival (a single maneuver has no "next").
        val start = Maneuver("Head north", "First St", 0.0, LatLon(0.0, 0.0), 0, 0.0, kind = ManeuverKind.fromValhalla(1))
        val turn = Maneuver("Keep right at the fork", "Grand Ave", 0.0, LatLon(0.0, 0.0), 20, cum[20], kind = ManeuverKind.fromValhalla(23))
        val end = Maneuver("You have arrived", "", 0.0, LatLon(0.0, 0.0), 40, cum.last(), kind = ManeuverKind.fromValhalla(4))
        val route = RouteLine(ok = true, source = "offline", points = points, cumM = cum, maneuvers = listOf(start, turn, end), distanceM = cum.last(), durationS = 600.0)
        val follower = Follower(route, offrouteM = 45.0)
        val prompts = points.take(20).mapIndexedNotNull { i, p ->
            follower.update(p, speedMs = 15.0, now = i.toDouble()).takeIf { it.say != null }
        }
        assertTrue(prompts.isNotEmpty())
        for (s in prompts) {
            assertEquals("slight-right", s.kind)
            assertTrue(s.band in listOf("far", "near", "now"), "${s.band}")
        }
        // "now" is the maneuver alone - no distance in the words, and the band says so.
        val now = prompts.last()
        assertEquals("now", now.band)
        assertTrue("In " !in now.say!!)
        // Between prompts: the kind of what is next, and no band.
        val quiet = follower.update(points[19], speedMs = 15.0, now = 99.0)
        assertNull(quiet.say)
        assertNull(quiet.band)
        assertEquals("slight-right", quiet.kind)
    }
}
