package online.iamprettyfamous.navi.core.geo

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.doubles
import online.iamprettyfamous.navi.core.obj
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The golden route, as Kotlin types. Shared with the guidance tests. */
object GoldenRoute {
    val blob: JsonObject by lazy { Golden.load("route.json") }

    val route: RouteLine by lazy {
        val points = blob.arr("points").map { LatLon(it.jsonArray[0].jsonPrimitive.double, it.jsonArray[1].jsonPrimitive.double) }
        val maneuvers = blob.arr("maneuvers").map {
            val m = it.jsonObject
            val at = m.getValue("at").jsonArray
            Maneuver(
                instruction = m.str("instruction"),
                street = m.str("street"),
                distanceM = m.getValue("distance_m").jsonPrimitive.double,
                at = LatLon(at[0].jsonPrimitive.double, at[1].jsonPrimitive.double),
                index = m.getValue("index").jsonPrimitive.int,
                cumM = m.getValue("cum_m").jsonPrimitive.double,
            )
        }
        RouteLine(
            ok = true,
            source = "offline",
            points = points,
            cumM = blob.getValue("cum_m").doubles().toList(),
            maneuvers = maneuvers,
            distanceM = blob.getValue("distance_m").jsonPrimitive.double,
            durationS = blob.getValue("duration_s").jsonPrimitive.double,
        )
    }
}

/**
 * Where along a route a fix is, and how far off it.
 *
 * Ported from apps/navi/tests/test_core.py:820-864 (OffRouteIsMeasuredToTheLine),
 * which pins the bug that silenced turn-by-turn a few turns into every ride:
 * route points are raw OSM shape points 183-449 m apart on a straight road, and
 * measuring to the nearest VERTEX made a rider dead on the line read as up to
 * 224 m off. Then value-for-value against washi routing.py:1247-1351 on 404
 * positions around a winding route.
 */
class RouteMathTest {
    /** A straight north-south road with shape points 400 m apart - an ordinary OSM way. */
    private val straight = listOf(LatLon(37.8000, -122.2000), LatLon(37.8036, -122.2000), LatLon(37.8072, -122.2000))
    private val straightCum = listOf(0.0, 400.0, 800.0)

    @Test
    fun `on the line between two points is not off route`() {
        val got = RouteMath.progress(straight, straightCum, LatLon(37.8018, -122.2000))
        assertTrue(got.offRouteM!! < 5.0, "a rider dead on the line reads as ${got.offRouteM} m off route")
    }

    @Test
    fun `along interpolates instead of jumping`() {
        // This also drives "in X ft" and when the 60/20/6 s prompts fire.
        val got = RouteMath.progress(straight, straightCum, LatLon(37.8018, -122.2000))
        assertEquals(200.0, got.alongM, 10.0)
    }

    @Test
    fun `a real deviation is still caught`() {
        // 60 m to the side SHOULD read as off route. The fix must not work by
        // simply reporting zero.
        val east = -122.2000 + 60.0 / (111_320.0 * cos(Math.toRadians(37.8)))
        val got = RouteMath.progress(straight, straightCum, LatLon(37.8018, east))
        assertEquals(60.0, got.offRouteM!!, 5.0)
    }

    @Test
    fun `past the end measures to the end`() {
        // Not to an imaginary extension of the road.
        val got = RouteMath.progress(straight, straightCum, LatLon(37.8100, -122.2000))
        assertTrue(got.offRouteM!! > 250.0)
    }

    @Test
    fun `the index names the vertex being ridden towards`() {
        // routing.py:1319-1322: past halfway along a segment, its far end.
        assertEquals(0, RouteMath.progress(straight, straightCum, LatLon(37.8010, -122.2000)).index)
        assertEquals(1, RouteMath.progress(straight, straightCum, LatLon(37.8026, -122.2000)).index)
    }

    @Test
    fun `an empty route has no progress and a single point is measured to itself`() {
        val empty = RouteMath.progress(emptyList(), emptyList(), LatLon(37.8, -122.2))
        assertNull(empty.index)
        assertEquals(0.0, empty.alongM)
        assertNull(empty.offRouteM)

        val single = GoldenRoute.blob.obj("single")
        val point = single.getValue("point").jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val probe = single.getValue("probe").jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
        val answer = single.getValue("answer").jsonArray
        val got = RouteMath.progress(listOf(point), listOf(0.0), probe)
        assertEquals(answer[0].jsonPrimitive.int, got.index)
        assertEquals(answer[1].jsonPrimitive.double, got.alongM)
        assertClose(answer[2].jsonPrimitive.double, got.offRouteM, 1e-12, 1e-9, "single-point off")
    }

    @Test
    fun `fewer than two maneuvers means no next maneuver, but progress still works`() {
        // guidance.py:279-284: taking off-route from next_maneuver meant a
        // straight route with no turns could never report being off.
        val route = RouteLine(ok = true, points = straight, cumM = straightCum, maneuvers = emptyList(), distanceM = 800.0, durationS = 60.0)
        val next = RouteMath.nextManeuver(route, LatLon(37.8018, -122.1990))
        assertNull(next.maneuver)
        assertNull(next.metres)
        assertNull(next.offRouteM)
        assertTrue(RouteMath.progress(route, LatLon(37.8018, -122.1990)).offRouteM!! > 80.0)
    }

    @Test
    fun `a failed route has nothing remaining`() {
        val failed = RouteLine(ok = false, reason = "no graph")
        assertEquals(Remaining(null, null), RouteMath.remaining(failed, LatLon(37.8, -122.2)))
        val zero = RouteLine(ok = true, points = straight, cumM = straightCum, distanceM = 0.0, durationS = 0.0)
        assertEquals(Remaining(0.0, 0.0), RouteMath.remaining(zero, LatLon(37.8018, -122.2)))
    }

    @Test
    fun `cumulative distance along a polyline is the sum of its legs`() {
        val cum = Geo.cumulativeM(straight)
        assertEquals(0.0, cum[0])
        assertEquals(Geo.haversineM(straight[0], straight[1]), cum[1], 1e-9)
        assertEquals(cum[1] + Geo.haversineM(straight[1], straight[2]), cum[2], 1e-9)
        assertEquals(listOf(0.0), Geo.cumulativeM(straight.take(1)))
        assertEquals(GoldenRoute.route.cumM.size, Geo.cumulativeM(GoldenRoute.route.points).size)
        GoldenRoute.route.cumM.zip(Geo.cumulativeM(GoldenRoute.route.points)).forEach { (want, got) ->
            assertClose(want, got, 1e-12, 1e-9, "cumulative")
        }
    }

    @Test
    fun `a bearing is compass degrees`() {
        assertEquals(0.0, Geo.bearing(LatLon(37.0, -122.0), LatLon(38.0, -122.0)), 1e-9)
        assertEquals(90.0, Geo.bearing(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-9)
        assertEquals(180.0, Geo.bearing(LatLon(38.0, -122.0), LatLon(37.0, -122.0)), 1e-9)
        assertEquals(270.0, Geo.bearing(LatLon(0.0, 1.0), LatLon(0.0, 0.0)), 1e-9)
    }

    @Test
    fun `every probe answers what the Python answered`() {
        val route = GoldenRoute.route
        assertEquals(404, GoldenRoute.blob.arr("probes").size)
        for ((n, element) in GoldenRoute.blob.arr("probes").withIndex()) {
            val probe = element.jsonObject
            val at = LatLon(probe.getValue("lat").jsonPrimitive.double, probe.getValue("lon").jsonPrimitive.double)
            val what = "probe $n"
            val progress = RouteMath.progress(route, at)
            assertEquals(probe.getValue("index").jsonPrimitive.int, progress.index, "$what index")
            assertClose(probe["along"].doubleOrNull(), progress.alongM, 1e-12, 1e-9, "$what along")
            assertClose(probe["off"].doubleOrNull(), progress.offRouteM, 1e-12, 1e-9, "$what off")

            val next = RouteMath.nextManeuver(route, at)
            val manIndex = probe.getValue("man_index").let { if (it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals(manIndex, next.maneuver?.index, "$what maneuver")
            assertClose(probe["man_m"].doubleOrNull(), next.metres, 1e-12, 1e-9, "$what maneuver metres")
            assertClose(probe["man_off"].doubleOrNull(), next.offRouteM, 1e-12, 1e-9, "$what maneuver off")

            val left = RouteMath.remaining(route, at)
            assertClose(probe["left_m"].doubleOrNull(), left.metres, 1e-12, 1e-9, "$what left m")
            assertClose(probe["left_s"].doubleOrNull(), left.seconds, 1e-12, 1e-9, "$what left s")
        }
    }
}
