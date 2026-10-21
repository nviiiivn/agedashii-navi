package online.iamprettyfamous.navi.core.daylight

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto day/night, computed offline from where she is and what time it is.
 *
 * Ported from apps/navi/tests/test_core.py:436-496 (TheSun). This had a real bug
 * once: walking a UTC day at a Californian longitude found sunset before sunrise.
 */
class DaylightTest {
    private val lat = 37.8044
    private val lon = -122.2712

    /** 2025-06-15 15:06:40 UTC, 08:06 in Oakland. */
    private val noon = 1750000000.0

    /** theme.py THEMES, the eight palettes a phase may ask for. */
    private val themes = setOf("night", "day", "sunlight", "redshift", "cyberpunk", "matrix", "pink", "cluster")

    @Test
    fun `the sun is up in the middle of a summer day`() {
        // 20:00 UTC is 13:00 local in California.
        assertTrue(Daylight.solarAltitude(lat, lon, 1750017600.0)!! > 40.0)
    }

    @Test
    fun `the sun is down in the middle of the night`() {
        // 09:00 UTC is 02:00 local.
        assertTrue(Daylight.solarAltitude(lat, lon, 1749978000.0)!! < -10.0)
    }

    @Test
    fun `every phase has a theme and a brightness`() {
        for ((name, _) in Daylight.PHASES) {
            assertTrue(Daylight.themeFor(name) in themes, name)
            val brightness = Daylight.brightnessFor(name)
            assertTrue(brightness > 0.0 && brightness <= 1.0, name)
        }
    }

    @Test
    fun `brightness falls as the sun does`() {
        val order = Daylight.PHASES.map { Daylight.brightnessFor(it.first) }
        assertEquals(order.sortedDescending(), order)
    }

    @Test
    fun `night asks for the red theme`() {
        // Rods are nearly blind to deep red: red on black keeps dark adaptation.
        assertEquals("redshift", Daylight.themeFor("night"))
        assertEquals("sunlight", Daylight.themeFor("day"))
    }

    @Test
    fun `no position falls back to the clock instead of failing`() {
        assertNull(Daylight.solarAltitude(null, null, noon))
        assertTrue(Daylight.phase(null, null, noon) in Daylight.PHASES.map { it.first })
        // daylight.py:124-131, by local hour, here in Pacific daylight time.
        val zone = TimeZone.of("America/Los_Angeles")
        assertEquals("night", Daylight.phase(null, null, 1749978000.0, zone = zone)) // 02:00
        assertEquals("golden", Daylight.phase(null, null, 1749999600.0, zone = zone)) // 08:00
        assertEquals("day", Daylight.phase(null, null, 1750003200.0, zone = zone)) // 09:00
        assertEquals("dusk", Daylight.phase(null, null, 1750042800.0, zone = zone)) // 20:00
    }

    @Test
    fun `the next sunrise and sunset are both within a day`() {
        val (rise, fall) = Daylight.sunTimes(lat, lon, noon)
        assertNotNull(rise)
        assertNotNull(fall)
        for (moment in listOf(rise, fall)) {
            assertTrue(moment - noon < 86400.0 + 600.0)
            assertTrue(moment > noon)
        }
    }

    @Test
    fun `the sun is actually at the horizon at those moments`() {
        // Whatever it calls sunrise must be a moment when the altitude is about zero.
        val (rise, fall) = Daylight.sunTimes(lat, lon, noon)
        for (moment in listOf(rise!!, fall!!)) assertEquals(0.0, Daylight.solarAltitude(lat, lon, moment)!!, 1.5)
    }

    @Test
    fun `the phases are cut at plus six, zero and minus six degrees`() {
        for (element in Golden.load("places.json").arr("phases")) {
            val case = element.jsonObject
            assertEquals(case.str("phase"), Daylight.phaseFor(case.getValue("altitude").jsonPrimitive.double))
        }
    }

    @Test
    fun `the sun is where the Python put it`() {
        val suns = Golden.load("places.json").arr("suns")
        assertEquals(280, suns.size)
        for (element in suns) {
            val case = element.jsonObject
            val got = Daylight.solarAltitude(
                case.getValue("lat").jsonPrimitive.double,
                case.getValue("lon").jsonPrimitive.double,
                case.getValue("when").jsonPrimitive.double,
            )
            val want = case.getValue("altitude").jsonPrimitive.double
            // Degrees to within a millionth: the same NOAA arithmetic, term for term.
            assertTrue(abs(want - got!!) < 1e-6, "altitude $case: $got")
        }
    }

    @Test
    fun `the sun rises and sets when the Python said, even in the arctic`() {
        for (element in Golden.load("places.json").arr("sun_times")) {
            val case = element.jsonObject
            val (rise, fall) = Daylight.sunTimes(
                case.getValue("lat").jsonPrimitive.double,
                case.getValue("lon").jsonPrimitive.double,
                case.getValue("when").jsonPrimitive.double,
            )
            assertClose(case["rise"].doubleOrNull(), rise, 0.0, 1e-6, "rise $case")
            assertClose(case["fall"].doubleOrNull(), fall, 0.0, 1e-6, "fall $case")
        }
    }
}
