package online.iamprettyfamous.navi.core.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.iamprettyfamous.navi.core.geo.LatLon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRequestTest {
    private val fremont = LatLon(37.5485, -121.9886)
    private val calaverasPin = LatLon(37.4700, -121.8200) // snapped to a private dirt track without the floor

    private fun request(imperial: Boolean = true): JsonObject =
        Json.parseToJsonElement(RouteRequest.motorcycle(fremont, calaverasPin, imperial)).jsonObject

    @Test
    fun `the destination may only snap to a proper road`() {
        val dest = request()["locations"]!!.jsonArray[1].jsonObject
        assertEquals("residential", dest["search_filter"]!!.jsonObject["min_road_class"]!!.jsonPrimitive.content)
        assertEquals(calaverasPin.lat, dest["lat"]!!.jsonPrimitive.double)
    }

    @Test
    fun `the origin is never filtered - she may be standing on gravel`() {
        val origin = request()["locations"]!!.jsonArray[0].jsonObject
        assertFalse("search_filter" in origin, origin.toString())
    }

    @Test
    fun `motorcycle costing avoids gravel and sends nothing Valhalla would ignore`() {
        val r = request()
        assertEquals("motorcycle", r["costing"]!!.jsonPrimitive.content)
        val moto = r["costing_options"]!!.jsonObject["motorcycle"]!!.jsonObject
        assertEquals(0.0, moto["use_trails"]!!.jsonPrimitive.double)
        assertFalse("use_hills" in moto || "use_primary" in moto, moto.toString())
    }

    @Test
    fun `units follow her setting`() {
        assertEquals("miles", request(imperial = true)["units"]!!.jsonPrimitive.content)
        assertEquals("kilometers", request(imperial = false)["units"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a route that ends away from the pin says so`() {
        // ~400 m north of the pin, the size of gap the snap floor produces.
        val end = LatLon(calaverasPin.lat + 400.0 / 111_195.0, calaverasPin.lon)
        val gap = assertNotNull(Arrival.gap(calaverasPin, end, "Calaveras Road"))
        assertTrue(gap.metres in 395.0..405.0, gap.toString())
        assertEquals("Calaveras Road", gap.street)
    }

    @Test
    fun `a route that ends at the pin says nothing`() {
        val end = LatLon(calaverasPin.lat + 10.0 / 111_195.0, calaverasPin.lon)
        assertNull(Arrival.gap(calaverasPin, end, "Calaveras Road"))
    }

    @Test
    fun `a blank street name is reported as unknown, not as an empty name`() {
        val end = LatLon(calaverasPin.lat + 100.0 / 111_195.0, calaverasPin.lon)
        assertNull(assertNotNull(Arrival.gap(calaverasPin, end, "  ")).street)
    }

    /** Lets the real router be fed exactly what the app will send. */
    @Test
    fun `write the request for the real router when asked`() {
        val out = System.getenv("NAVI_REQUEST_OUT") ?: return
        File(out).writeText(RouteRequest.motorcycle(fremont, calaverasPin, imperial = false))
    }
}
