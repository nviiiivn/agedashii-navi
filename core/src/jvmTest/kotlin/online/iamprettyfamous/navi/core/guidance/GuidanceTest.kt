package online.iamprettyfamous.navi.core.guidance

import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.GoldenRoute
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.Maneuver
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun straightRoute(points: List<LatLon>, maneuvers: List<Maneuver> = emptyList(), durationS: Double = 600.0): RouteLine {
    val cum = Geo.cumulativeM(points)
    return RouteLine(ok = true, source = "offline", points = points, cumM = cum, maneuvers = maneuvers, distanceM = cum.last(), durationS = durationS)
}

private fun man(instruction: String, street: String, cumM: Double, index: Int) =
    Maneuver(instruction = instruction, street = street, distanceM = 0.0, at = LatLon(0.0, 0.0), index = index, cumM = cumM)

/**
 * Ported from apps/navi/tests/test_core.py:499-531 (OffRouteHysteresis). The
 * deadband: a bare threshold flickers on one noisy fix, and each flicker is an
 * announcement and a re-route for a corner taken correctly.
 */
class OffRouteHysteresisTest {
    private val points = (0..10).map { LatLon(37.80 + it * 0.001, -122.27) }
    private lateinit var follower: Follower

    @BeforeTest
    fun setUp() {
        follower = Follower(straightRoute(points), offrouteM = 45.0)
    }

    @Test
    fun `one bad fix does not say off route`() {
        follower.update(LatLon(37.805, -122.2718), now = 0.0) // ~17 m off
        follower.update(LatLon(37.805, -122.2695), now = 1.0) // ~40 m off
        assertFalse(follower.offRoute)
    }

    @Test
    fun `three consecutive bad fixes do`() {
        repeat(Guidance.CONFIRM_FIXES) { follower.update(LatLon(37.805, -122.2660), now = it.toDouble()) } // ~350 m
        assertTrue(follower.offRoute)
        assertTrue(follower.shouldReroute())
        assertEquals(2.0, follower.offSince)
    }

    @Test
    fun `coming back needs to clear the deadband`() {
        repeat(Guidance.CONFIRM_FIXES) { follower.update(LatLon(37.805, -122.2660), now = it.toDouble()) }
        // Just inside the threshold is NOT enough to clear it.
        follower.update(LatLon(37.805, -122.26955), now = 3.0) // ~40 m off
        assertTrue(follower.offRoute)
        // On the line is.
        follower.update(LatLon(37.805, -122.27), now = 4.0)
        assertFalse(follower.offRoute)
        assertNull(follower.offSince)
    }

    @Test
    fun `arrival stops asking for a re-route`() {
        follower.update(points.last(), now = 0.0)
        assertTrue(follower.arrived)
        assertFalse(follower.shouldReroute())
    }

    @Test
    fun `no route is said plainly`() {
        val lost = Follower(null)
        val status = lost.update(LatLon(37.8, -122.27), now = 0.0)
        assertFalse(status.ok)
        assertEquals("no route", status.reason)
        assertNull(status.say)
        assertFalse(Follower(RouteLine(ok = false, reason = "x")).update(LatLon(37.8, -122.27), now = 0.0).ok)
    }
}

/**
 * Ported from apps/navi/tests/test_core.py:533-586 (Prompts). One prompt per
 * band per maneuver, walked nearest first.
 */
class PromptsTest {
    private val points = (0..40).map { LatLon(37.80 + it * 0.0005, -122.27) }
    private lateinit var route: RouteLine
    private lateinit var follower: Follower

    @BeforeTest
    fun setUp() {
        val base = straightRoute(points)
        route = base.copy(
            maneuvers = listOf(
                man("head north", "First St", 0.0, 0),
                man("turn left", "Grand Ave", base.cumM[20], 20),
                man("arrive at your destination", "", base.cumM.last(), 40),
            ),
        )
        follower = Follower(route, offrouteM = 45.0)
    }

    @Test
    fun `prompt distances scale with speed`() {
        val slow = Guidance.promptPoints(10.0)
        val fast = Guidance.promptPoints(30.0)
        assertTrue(fast.getValue("far") > slow.getValue("far"))
        assertEquals(30.0 * 20.0, fast.getValue("near"), 1e-9)
    }

    @Test
    fun `below a crawl the distances are fixed`() {
        assertEquals(Guidance.CRAWL_BANDS_M, Guidance.promptPoints(0.0))
        assertEquals(Guidance.CRAWL_BANDS_M, Guidance.promptPoints(null))
        assertEquals(mapOf("far" to 250.0, "near" to 90.0, "now" to 25.0), Guidance.CRAWL_BANDS_M)
    }

    @Test
    fun `each band speaks once`() {
        val said = points.take(20).mapIndexedNotNull { i, p -> follower.update(p, speedMs = 15.0, now = i.toDouble()).say }
        assertTrue(said.isNotEmpty(), "nothing was ever announced")
        assertEquals(said.size, said.toSet().size, "the same prompt was said twice")
        assertTrue(said.size <= Guidance.PROMPT_BANDS.size)
    }

    @Test
    fun `crossing every band in one fix says the nearest thing once`() {
        // One point short of the turn at high speed: all three bands behind us.
        val status = follower.update(points[19], speedMs = 30.0, now = 0.0)
        assertNotNull(status.say)
        assertFalse("In " in status.say!!)
        assertNull(follower.update(points[19], speedMs = 30.0, now = 1.0).say)
    }

    @Test
    fun `a re-route forgets what it already said`() {
        points.take(20).forEachIndexed { i, p -> follower.update(p, speedMs = 15.0, now = i.toDouble()) }
        follower.setRoute(route)
        val again = points.take(20).mapIndexed { i, p -> follower.update(p, speedMs = 15.0, now = 20.0 + i).say }
        assertTrue(again.any { it != null }, "a re-route went silent")
    }

    @Test
    fun `picked at a light then ridden off - the turn is said once, then at the corner`() {
        // minion 1's rides: stopped 178 m out, "far" (crawl bands) fires; riding
        // off at 15 m/s moves "near" to 300 m, due at once - the same words again.
        val turn = points[20]
        fun short(m: Double) = LatLon(turn.lat - m / 111_320.0, turn.lon)
        val first = follower.update(short(178.0), speedMs = 0.0, now = 0.0)
        assertTrue(first.say!!.startsWith("In "), "far, at the light")
        assertNull(follower.update(short(170.0), speedMs = 15.0, now = 1.0).say, "not again 8 m later")
        assertNull(follower.update(short(120.0), speedMs = 15.0, now = 4.0).say)
        val corner = follower.update(short(85.0), speedMs = 15.0, now = 6.0)
        assertEquals("now", corner.band)
        assertFalse(corner.say!!.startsWith("In "))
    }

    @Test
    fun `and then - the next maneuver, when it is too close to be warned of on its own`() {
        val base = straightRoute(points)
        fun m(at: Int, kind: String?, words: String) = Maneuver(words, "", 0.0, LatLon(0.0, 0.0), at, base.cumM[at], kind = kind)
        val head = m(0, null, "head north")
        val arrive = m(40, ManeuverKind.ARRIVE, "arrive at your destination")
        fun ride(vararg mans: Maneuver, speed: Double?): List<Status> {
            val f = Follower(base.copy(maneuvers = listOf(head) + mans + arrive), offrouteM = 45.0)
            return points.take(22).mapIndexed { i, p -> f.update(p, speedMs = speed, now = i.toDouble()) }.filter { it.say != null }
        }
        // turn left, then turn right 56 m on: at 15 m/s the limit is 135 m
        val close = ride(m(20, ManeuverKind.TURN_LEFT, "turn left"), m(21, ManeuverKind.TURN_RIGHT, "turn right"), speed = 15.0)
        val nowLeft = close.first { it.band == "now" && it.kind == ManeuverKind.TURN_LEFT }
        assertEquals(ManeuverKind.TURN_RIGHT, nowLeft.then?.kind)
        assertTrue(close.filter { it.band != "now" }.all { it.then == null }, "only on the now prompt")
        // 111 m on: too far at a crawl (60 m floor), close enough at 15 m/s
        val gap = listOf(m(20, ManeuverKind.TURN_LEFT, "turn left"), m(22, ManeuverKind.TURN_RIGHT, "turn right"))
        assertNull(ride(*gap.toTypedArray(), speed = null).first { it.band == "now" }.then)
        assertEquals(ManeuverKind.TURN_RIGHT, ride(*gap.toTypedArray(), speed = 15.0).first { it.band == "now" }.then?.kind)
        // never an arrival, a continue or a merge
        val cont = ride(m(20, ManeuverKind.TURN_LEFT, "turn left"), m(21, ManeuverKind.CONTINUE, "continue"), speed = 15.0)
        assertNull(cont.first { it.band == "now" }.then)
    }

    @Test
    fun `a warning is not spoken when now is a moment away`() {
        // minion 1's #4: right after a turn the next came up just outside "now";
        // at 15 m/s "In 350 ft, turn left" then "Turn left" 1 s apart.
        val turn = points[20]
        fun short(m: Double) = LatLon(turn.lat - m / 111_320.0, turn.lon)
        assertNull(follower.update(short(100.0), speedMs = 15.0, now = 0.0).say, "now is under a second away")
        assertEquals("now", follower.update(short(88.0), speedMs = 15.0, now = 1.0).band)
        val far = Follower(route, offrouteM = 45.0)
        assertEquals("near", far.update(short(290.0), speedMs = 15.0, now = 0.0).band, "13 s out: still warned")
    }

    @Test
    fun `a continue is said once`() {
        val base = route
        val cont = base.copy(maneuvers = listOf(
            base.maneuvers[0],
            Maneuver("Continue toward CA 24.", "Grove Shafter Freeway", 0.0, LatLon(0.0, 0.0), 20, base.cumM[20], kind = ManeuverKind.CONTINUE),
            base.maneuvers[2],
        ))
        val f = Follower(cont, offrouteM = 45.0)
        val said = points.take(21).mapIndexedNotNull { i, p -> f.update(p, speedMs = 15.0, now = i.toDouble()).say }
            .filter { "CA 24" in it }
        assertEquals(1, said.size, "said: $said")
    }

    @Test
    fun `slowing down still gets the nearer warning`() {
        val turn = points[20]
        fun short(m: Double) = LatLon(turn.lat - m / 111_320.0, turn.lon)
        assertEquals("far", follower.update(short(850.0), speedMs = 15.0, now = 0.0).band)
        assertEquals("near", follower.update(short(85.0), speedMs = 2.0, now = 60.0).band, "85 m is well under 0.6 x 850")
    }

    @Test
    fun `arriving at walking pace still says so`() {
        // Below a crawl the "now" band is 25 m, the same as the arrival distance:
        // the fix that arrives is the only one that can say it (Flet v1.17.0).
        points.dropLast(1).forEachIndexed { i, p -> follower.update(p, speedMs = 1.0, now = i.toDouble()) }
        val end = points.last()
        val there = follower.update(LatLon(end.lat - 0.00015, end.lon), speedMs = 1.0, now = 100.0) // ~17 m short
        assertTrue(there.arrived)
        assertEquals("arrive at your destination", there.say)
        assertNull(follower.update(end, speedMs = 1.0, now = 101.0).say, "and only once")
    }

    @Test
    fun `the head instruction is never a prompt`() {
        // next_maneuver starts at maneuvers[1]; "head north" is what she is already doing.
        val status = follower.update(points[0], speedMs = 15.0, now = 0.0)
        assertFalse("head" in (status.say ?: "").lowercase())
    }
}

/** Ported from apps/navi/tests/test_core.py:588-619 (Wording). */
class WordingTest {
    @Test
    fun `distances are rounded to something sayable`() {
        assertEquals("400 ft", Guidance.distanceText(122.0))
        assertEquals("1.0 mi", Guidance.distanceText(1609.344))
        assertEquals("30 m", Guidance.distanceText(30.0, imperial = false))
        assertEquals("1.5 km", Guidance.distanceText(1500.0, imperial = false))
        assertEquals("—", Guidance.distanceText(null))
        assertEquals("12 mi", Guidance.distanceText(19000.0))
    }

    @Test
    fun `speed has no decimal point`() {
        assertEquals("60", Guidance.speedText(26.8224))
        assertEquals("97", Guidance.speedText(26.8224, imperial = false))
        assertEquals("—", Guidance.speedText(null))
        assertEquals("mph", Guidance.speedUnit(true))
        assertEquals("km/h", Guidance.speedUnit(false))
    }

    @Test
    fun `now drops the distance`() {
        val near = Guidance.spoken("turn left", "Grand Ave", 100.0, band = "near")
        val now = Guidance.spoken("turn left", "Grand Ave", 20.0, band = "now")
        assertTrue(near.startsWith("In "))
        assertEquals("turn left onto Grand Ave", now)
        assertEquals("continue", Guidance.spoken(null, null, 20.0, band = "now"))
    }

    @Test
    fun `nothing goes after a sentence's full stop`() {
        assertEquals(
            "In 0.5 mi, Continue toward CA 24/Berkeley/Walnut Creek.",
            Guidance.spoken("Continue toward CA 24/Berkeley/Walnut Creek.", "Grove Shafter Freeway", 800.0, band = "far"),
        )
        assertEquals("turn left onto Grand Ave", Guidance.spoken("turn left", "Grand Ave", 20.0, band = "now"))
    }

    @Test
    fun `a street already in the instruction is not repeated`() {
        val said = Guidance.spoken("turn left onto Grand Ave", "Grand Ave", 20.0, band = "now")
        assertEquals(1, Regex("Grand Ave").findAll(said).count())
    }

    @Test
    fun `turn icons read the words`() {
        assertEquals("sharp_left", Guidance.turnIcon("Turn sharp left"))
        assertEquals("slight_right", Guidance.turnIcon("Bear right"))
        assertEquals("u_turn", Guidance.turnIcon("Make a U-turn"))
        assertEquals("flag", Guidance.turnIcon("Arrive at your destination"))
        assertEquals("straight", Guidance.turnIcon("Continue"))
        assertEquals("straight", Guidance.turnIcon(null))
    }

    @Test
    fun `arrival is a clock time in her time zone`() {
        // 1700000000 is 22:13:20 UTC on 2023-11-14; plus 10 minutes.
        assertEquals("22:23", Guidance.etaText(600.0, now = 1700000000.0, zone = TimeZone.of("UTC")))
        assertEquals("14:23", Guidance.etaText(600.0, now = 1700000000.0, zone = TimeZone.of("America/Los_Angeles")))
        assertEquals("—", Guidance.etaText(null, now = 1700000000.0))
    }
}

/**
 * Ported from apps/navi/tests/test_core.py:622-642 (SpeedLimits), plus the
 * spec's section 11 rule that a posted limit wins over a typical one.
 */
class SpeedLimitsTest {
    /** washi routing.py:116-124, the graph's classes and its fitted door-to-door speeds. */
    private val routingClasses = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
        "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road",
    )
    private val routingSpeedKmh = listOf(79.0, 38.7, 79.0, 38.7, 36.1, 38.7, 36.1, 38.7, 28.0, 38.7, 24.0, 12.1, 12.1, 6.3, 6.3)

    @Test
    fun `every road class has a typical limit`() {
        for (name in routingClasses) assertTrue(name in Guidance.TYPICAL_MPH, name)
        assertEquals(routingClasses, Guidance.ROAD_CLASSES)
    }

    @Test
    fun `the typical limits are not the routing speeds`() {
        // The graph's speeds are fitted door-to-door travel, a different quantity.
        val residential = routingSpeedKmh[routingClasses.indexOf("residential")] / 1.609344
        assertTrue(Guidance.TYPICAL_MPH.getValue("residential") > residential)
    }

    @Test
    fun `no road means no limit rather than a guess`() {
        assertEquals(SpeedLimit.NONE, Guidance.speedLimit(postedMph = null, roadClass = null))
        assertEquals(null to "", Guidance.limitMph(null))
        // limit_mph returns the class even when it has no typical figure.
        assertEquals(null to "footway", Guidance.limitMph("footway"))
        assertEquals(25 to "residential", Guidance.limitMph("residential"))
        assertEquals(25 to "residential", Guidance.limitMphForClassIndex(11))
        assertEquals(null to "", Guidance.limitMphForClassIndex(99))
    }

    @Test
    fun `a posted limit wins and says so`() {
        // Spec section 11: posted first, typical as the fallback, labelled apart.
        assertEquals(SpeedLimit(35, LimitSource.POSTED, "residential"), Guidance.speedLimit(postedMph = 35, roadClass = "residential"))
        assertEquals(SpeedLimit(25, LimitSource.TYPICAL, "residential"), Guidance.speedLimit(postedMph = null, roadClass = "residential"))
        assertEquals(SpeedLimit(null, LimitSource.NONE, "footway"), Guidance.speedLimit(postedMph = null, roadClass = "footway"))
    }

    @Test
    fun `over the limit needs a real margin`() {
        assertFalse(Guidance.overLimit(null, 25))
        assertFalse(Guidance.overLimit(12.0, null))
        assertFalse(Guidance.overLimit(12.0, 25)) // 26.8 mph
        assertTrue(Guidance.overLimit(20.0, 25)) // 44.7 mph
        assertFalse(Guidance.overLimit(20.0, 0)) // `not limit`
    }
}

/**
 * live.py:32-34 and 129-145, 300-303: at most one re-route per 12 s, and never
 * while one is already running. The slot is claimed BEFORE the search, because
 * stamping it after let every fix during a slow search ask again and the
 * requests stacked.
 */
class RerouteGateTest {
    @Test
    fun `only a confirmed off-route asks`() {
        val gate = RerouteGate()
        assertFalse(gate.shouldAsk(offRouteConfirmed = false, t = 1000.0))
        assertTrue(gate.shouldAsk(offRouteConfirmed = true, t = 1000.0))
    }

    @Test
    fun `at most one every twelve seconds`() {
        val gate = RerouteGate()
        gate.begin(1000.0)
        gate.end(1002.0)
        assertFalse(gate.shouldAsk(true, 1013.9))
        assertTrue(gate.shouldAsk(true, 1014.0))
        assertEquals(12.0, RerouteGate.EVERY_S)
    }

    @Test
    fun `never while a search is running, however long it takes`() {
        val gate = RerouteGate()
        gate.begin(1000.0)
        assertFalse(gate.shouldAsk(true, 1030.0), "a second search queued behind a slow one")
        gate.end(1031.0)
        assertFalse(gate.shouldAsk(true, 1035.0))
        assertTrue(gate.shouldAsk(true, 1043.0))
    }

    @Test
    fun `the slot is claimed when the search starts`() {
        val gate = RerouteGate()
        gate.begin(1000.0)
        assertTrue(gate.routingNow)
        assertEquals(1000.0, gate.lastRouteAt)
    }
}

/**
 * Whole rides through the Follower, fix by fix, against what the Python
 * Follower said for the same fixes (tools/golden_route.py). This is the test
 * that proves the port says the same words at the same moments.
 */
class FollowerGoldenTest {
    @Test
    fun `every ride says what the Python said, when the Python said it`() {
        val route = GoldenRoute.route
        val rides = GoldenRoute.blob.arr("rides")
        // Guard against passing on nothing: six rides, with prompts in them.
        assertEquals(6, rides.size)
        val prompts = rides.sumOf { r -> r.jsonObject.arr("statuses").count { it.jsonObject.getValue("say") !is JsonNull } }
        assertTrue(prompts > 100, "only $prompts prompts in the golden rides")
        for (element in rides) {
            val ride = element.jsonObject
            val name = ride.str("name")
            val rerouteAt = ride.getValue("reroute_at").let { if (it is JsonNull) null else it.jsonPrimitive.int }
            val follower = Follower(route, offrouteM = 45.0)
            val fixes = ride.arr("fixes")
            val statuses = ride.arr("statuses")
            assertEquals(fixes.size, statuses.size)
            for (n in fixes.indices) {
                if (rerouteAt != null && n == rerouteAt) {
                    follower.setRoute(route)
                    follower.noteReroute()
                }
                val fix = fixes[n].jsonObject
                val got = follower.update(
                    LatLon(fix.getValue("lat").jsonPrimitive.double, fix.getValue("lon").jsonPrimitive.double),
                    speedMs = fix.getValue("speed_ms").jsonPrimitive.double,
                    now = fix.getValue("t").jsonPrimitive.double,
                )
                val want = statuses[n].jsonObject
                val what = "$name fix $n"
                assertEquals(want.getValue("say").let { if (it is JsonNull) null else it.jsonPrimitive.content }, got.say, "$what say")
                assertEquals(want.getValue("ok").jsonPrimitive.boolean, got.ok, "$what ok")
                assertEquals(want.str("reason"), got.reason, "$what reason")
                assertEquals(want.getValue("maneuver_index").let { if (it is JsonNull) null else it.jsonPrimitive.int }, got.maneuver?.index, "$what maneuver")
                assertEquals(want.str("instruction"), got.instruction, "$what instruction")
                assertEquals(want.str("street"), got.street, "$what street")
                assertEquals(want.str("icon"), got.icon, "$what icon")
                assertEquals(want.getValue("off_route").jsonPrimitive.boolean, got.offRoute, "$what off_route")
                assertEquals(want.getValue("arrived").jsonPrimitive.boolean, got.arrived, "$what arrived")
                assertEquals(want.getValue("should_reroute").jsonPrimitive.boolean, follower.shouldReroute(), "$what should_reroute")
                assertEquals(want.str("source"), got.source, "$what source")
                assertClose(want["turn_in_m"].doubleOrNull(), got.turnInM, 1e-12, 1e-9, "$what turn_in_m")
                assertClose(want["off_route_m"].doubleOrNull(), got.offRouteM, 1e-12, 1e-9, "$what off_route_m")
                assertClose(want["left_m"].doubleOrNull(), got.leftM, 1e-12, 1e-9, "$what left_m")
                assertClose(want["left_s"].doubleOrNull(), got.leftS, 1e-12, 1e-9, "$what left_s")
            }
        }
    }
}
