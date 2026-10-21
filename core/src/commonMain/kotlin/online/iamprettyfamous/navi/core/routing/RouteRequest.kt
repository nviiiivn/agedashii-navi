package online.iamprettyfamous.navi.core.routing

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import kotlin.math.roundToInt

/**
 * The route request the phone gives its own Valhalla (valhalla-mobile 0.6.3,
 * Valhalla 3.6.3), as JSON. Every choice below was measured on the Bay Area
 * tiles with the same Valhalla version before it was written here.
 */
object RouteRequest {
    /**
     * Never trade pavement for gravel. In 3.6.3's motorcycle costing
     * (src/sif/motorcyclecost.cc:339-344) 0.5 zeroes the surface penalty and
     * 0.0 applies it eightfold: gravel +4.0, dirt +1.6 per edge. Measured: 0 of
     * 630 ordinary Bay Area trips and 0 of 210 Berkeley/Oakland hills trips
     * change between the two, so on her roads this costs nothing; it only
     * matters where a fire road is a shortcut (0.5 takes 3.1 km of the John
     * Nicholas Trail above Los Gatos instead of 23.5 km of pavement). A 1993
     * CB750 on street tyres. Her server sends 0.5; the app does not.
     */
    const val USE_TRAILS = 0.0

    /**
     * The least road a DESTINATION may snap to. Without it a pin dropped in
     * the hills snaps to whatever is nearest - measured: a pin by Calaveras
     * Road snapped onto `highway=track, access=private` (OSM ways 47973850,
     * 47973848, 47973846) and every route there ended on ~3.4 km of private
     * dirt, whatever USE_TRAILS said. With this floor, Fremont to that pin went
     * from 29.48 km / 46.1 min / 710 m of dirt to 30.14 km / 26.3 min / 0 m,
     * ending on paved Calaveras Road.
     *
     * NEVER on the origin: the origin is where she is, and she may really be
     * standing on gravel - a floor there would start the route somewhere else.
     *
     * The cost is that a destination at the end of a long service road (a
     * trailhead lot) now ends at the street. That must be SHOWN, not hidden:
     * see [Arrival.gap].
     */
    const val DESTINATION_MIN_ROAD_CLASS = "residential"

    /** How far the road at the start may point from her heading (Valhalla's default is 60). */
    const val HEADING_TOLERANCE_DEG = 45

    /**
     * A motorcycle route from where she is to where she asked.
     *
     * Note what is NOT sent: `use_hills` and `use_primary` do not exist for
     * motorcycle costing in 3.6.3 (motorcyclecost.cc:597-601 parses only
     * use_highways, use_tolls, use_trails, top_speed), though her Python
     * client (routing.py:1609) sends both and Valhalla silently ignores them.
     */
    fun motorcycle(origin: LatLon, destination: LatLon, imperial: Boolean = true, heading: Double? = null): String = buildJsonObject {
        putJsonArray("locations") {
            addJsonObject {
                put("lat", origin.lat)
                put("lon", origin.lon)
                put("type", "break")
                // The way she is facing, when she is moving and it is known: a
                // re-route then carries on ahead of her instead of starting with
                // whatever direction is shortest from where she stands - which
                // turned one wrong turn into six prompts in twelve seconds.
                if (heading != null) {
                    put("heading", ((heading.roundToInt() % 360) + 360) % 360)
                    put("heading_tolerance", HEADING_TOLERANCE_DEG)
                }
            }
            addJsonObject {
                put("lat", destination.lat)
                put("lon", destination.lon)
                put("type", "break")
                putJsonObject("search_filter") { put("min_road_class", DESTINATION_MIN_ROAD_CLASS) }
            }
        }
        put("costing", "motorcycle")
        putJsonObject("costing_options") {
            putJsonObject("motorcycle") { put("use_trails", USE_TRAILS) }
        }
        put("units", if (imperial) "miles" else "kilometers")
    }.toString()
}

/** The route ends [metres] from the pin she dropped, on [street] if known. */
data class ArrivalGap(val metres: Double, val street: String?)

/**
 * REQUIREMENT for whoever builds the destination UI: if the route does not end
 * where she asked, say so - e.g. "Route ends at Calaveras Road, 400 m from your
 * pin". Her rule is that the app never quietly substitutes something for what
 * was asked, and [RouteRequest.DESTINATION_MIN_ROAD_CLASS] will sometimes move
 * the end of the route to the nearest proper road.
 *
 * Compare against the END OF THE ROUTE SHAPE (its last point), not against
 * `trip.locations` in the response: measured on 3.6.3, those echo the input
 * coordinates back even when the route ends elsewhere.
 */
object Arrival {
    /**
     * Below this the end is "at the pin". 45 m is the app's own off-route
     * threshold (spec section 11) - the distance already agreed to mean "not
     * where the route is" - so the two notions of close stay one number.
     */
    const val VISIBLE_GAP_M = 45.0

    fun gap(pin: LatLon, routeEnd: LatLon, endStreet: String? = null): ArrivalGap? {
        val m = Geo.haversineM(pin, routeEnd)
        return if (m < VISIBLE_GAP_M) null else ArrivalGap(m, endStreet?.takeIf { it.isNotBlank() })
    }
}
