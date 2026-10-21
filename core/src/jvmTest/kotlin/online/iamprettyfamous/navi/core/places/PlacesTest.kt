package online.iamprettyfamous.navi.core.places

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coordinates from whatever she pasted. Ported from apps/navi/tests/test_core.py:645-676
 * (ParsingAPlace). Total and offline: a nonsense string is null, never an exception.
 */
class ParsingAPlaceTest {
    @Test
    fun `a bare pair`() {
        val got = Places.parsePoint("37.8044, -122.2712")!!
        assertEquals(37.8044 to -122.2712, got.lat to got.lon)
    }

    @Test
    fun `a share sheet geo uri`() {
        val got = Places.parsePoint("geo:37.8044,-122.2712?z=15")!!
        assertEquals(37.8044 to -122.2712, got.lat to got.lon)
        assertEquals("shared pin", got.label)
    }

    @Test
    fun `a maps url prefers the pair after the at`() {
        val got = Places.parsePoint("https://www.google.com/maps/place/Somewhere/@37.8044,-122.2712,15z/data=!3m1!4b1")!!
        assertEquals(37.8044 to -122.2712, got.lat to got.lon)
    }

    @Test
    fun `a maps url with a query parameter`() {
        val got = Places.parsePoint("https://maps.apple.com/?q=37.8044,-122.2712&t=m")!!
        assertEquals(37.8044 to -122.2712, got.lat to got.lon)
    }

    @Test
    fun `degrees minutes seconds`() {
        val got = Places.parsePoint("""37°48'15.8"N 122°16'16.3"W""")
        assertNotNull(got)
        assertEquals(37.8044, got.lat, 0.0005)
        assertEquals(-122.2712, got.lon, 0.0005)
    }

    @Test
    fun `a point off the planet is refused`() {
        assertNull(Places.parsePoint("91.0, 0.0"))
        assertNull(Places.parsePoint("0.0, 181.0"))
    }

    @Test
    fun `nonsense is null rather than an exception`() {
        for (text in listOf("", null, "somewhere nice", "---")) assertNull(Places.parsePoint(text), text)
    }

    @Test
    fun `every awkward input reads as the Python read it`() {
        val points = Golden.load("places.json").arr("points")
        assertEquals(41, points.size)
        for (element in points) {
            val case = element.jsonObject
            val text = case.str("text")
            val got = Places.parsePoint(text)
            val want = case.getValue("result")
            if (want is JsonNull) {
                assertNull(got, "'$text' read as $got")
            } else {
                val parts = want.jsonArray
                assertEquals(
                    Place(parts[0].jsonPrimitive.double, parts[1].jsonPrimitive.double, parts[2].jsonPrimitive.content),
                    got,
                    "'$text'",
                )
            }
        }
    }
}

/**
 * Ported from test_core.py GpxImportDoesNotCareAboutAttributeOrder. Found by this
 * port: the pattern demanded lat before lon, and a file that wrote lon first
 * imported as an empty list - no error, just nothing. Fixed in the Python
 * (places.py 5baa7e78), then here.
 */
class GpxImportDoesNotCareAboutAttributeOrderTest {
    private fun one(tag: String) = Places.parseGpx("<gpx><trk><trkseg>$tag</trkseg></trk></gpx>").points

    @Test
    fun `lat first`() {
        assertEquals(listOf(LatLon(37.8044, -122.2712)), one("""<trkpt lat="37.8044" lon="-122.2712"/>"""))
    }

    @Test
    fun `lon first`() {
        assertEquals(listOf(LatLon(37.8044, -122.2712)), one("""<trkpt lon="-122.2712" lat="37.8044"/>"""))
    }

    @Test
    fun `other attributes in between`() {
        assertEquals(listOf(LatLon(37.8044, -122.2712)), one("""<trkpt ele="12" lon="-122.2712" lat="37.8044" t="x"/>"""))
    }

    @Test
    fun `a tag missing one of them is skipped not guessed`() {
        assertEquals(emptyList(), one("""<trkpt lat="37.8044"/>"""))
    }
}

/** Ported from apps/navi/tests/test_core.py:679-709 (ImportingGpx). */
class ImportingGpxTest {
    private val track = """<?xml version="1.0"?>
    <gpx version="1.1"><trk><name>Grizzly Peak</name><trkseg>
      <trkpt lat="37.8800" lon="-122.2400"><ele>380</ele></trkpt>
      <trkpt lat="37.8810" lon="-122.2390"></trkpt>
    </trkseg></trk></gpx>"""

    @Test
    fun `a track comes back with its name`() {
        val got = Places.parseGpx(track)
        assertEquals("Grizzly Peak", got.name)
        assertEquals(2, got.points.size)
        assertEquals(LatLon(37.88, -122.24), got.points[0])
    }

    @Test
    fun `a track wins over stray waypoints`() {
        val both = track.replace("</gpx>", """<wpt lat="1.0" lon="2.0"></wpt></gpx>""")
        val got = Places.parseGpx(both)
        assertEquals(2, got.points.size)
        assertFalse(LatLon(1.0, 2.0) in got.points)
    }

    @Test
    fun `a planned route in rtept is still read`() {
        val route = """<gpx><rte><name>Plan</name><rtept lat="37.1" lon="-122.1"></rtept><rtept lat="37.2" lon="-122.2"></rtept></rte></gpx>"""
        val got = Places.parseGpx(route)
        assertEquals("Plan", got.name)
        assertEquals(2, got.points.size)
    }

    @Test
    fun `a file that is not gpx gives no points rather than raising`() {
        assertEquals(emptyList(), Places.parseGpx("hello").points)
        assertEquals(emptyList(), Places.parseGpx("").points)
        assertEquals(emptyList(), Places.parseGpx(null).points)
    }

    @Test
    fun `every gpx reads as the Python read it`() {
        val files = Golden.load("places.json").arr("gpx")
        assertEquals(13, files.size)
        for (element in files) {
            val case = element.jsonObject
            val got = Places.parseGpx(case.str("text"))
            assertEquals(case.str("name"), got.name, case.str("text"))
            val want = case.getValue("points").jsonArray.map { LatLon(it.jsonArray[0].jsonPrimitive.double, it.jsonArray[1].jsonPrimitive.double) }
            assertEquals(want, got.points, case.str("text"))
        }
    }
}
