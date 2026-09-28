package online.iamprettyfamous.navi.core.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.Maneuver
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.telemetry.Physics

/**
 * A Valhalla /route response (its native JSON) turned into the core's RouteLine,
 * which the Follower and the map both read. Lengths come back in the request's
 * units - miles or kilometres - and are converted to metres here, once.
 */
object ValhallaRoute {
    private const val METRES_PER_MILE = 1609.344

    fun parse(json: String, source: String = "offline", sourceDetail: String = "Valhalla on the phone"): RouteLine {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            return RouteLine(ok = false, source = source, reason = "unreadable router reply: ${e.message}")
        }
        root["error"]?.let { err ->
            return RouteLine(ok = false, source = source, reason = err.jsonPrimitive.contentOrNull ?: "router error")
        }
        val trip = root["trip"] as? JsonObject ?: return RouteLine(ok = false, source = source, reason = "no trip in the reply")
        val perUnit = if (trip["units"]?.jsonPrimitive?.contentOrNull == "kilometers") 1000.0 else METRES_PER_MILE
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        for (legEl in trip["legs"]?.jsonArray.orEmpty()) {
            val leg = legEl.jsonObject
            val offset = if (points.isEmpty()) 0 else points.size - 1
            val shape = decodePolyline6(leg["shape"]?.jsonPrimitive?.contentOrNull.orEmpty())
            // Consecutive legs share their joining point.
            points.addAll(if (points.isEmpty()) shape else shape.drop(1))
            for (mEl in leg["maneuvers"]?.jsonArray.orEmpty()) {
                val m = mEl.jsonObject
                val index = offset + (m["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: 0)
                val streets = (m["street_names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                maneuvers += Maneuver(
                    instruction = m["instruction"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    street = streets.firstOrNull().orEmpty(),
                    distanceM = (m["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * perUnit,
                    at = LatLon(0.0, 0.0), // placed below, once the whole shape is known
                    index = index,
                    cumM = 0.0,
                )
            }
        }
        if (points.size < 2) return RouteLine(ok = false, source = source, reason = "the route has no shape")
        val cum = ArrayList<Double>(points.size)
        var total = 0.0
        cum += 0.0
        for (i in 1 until points.size) {
            total += Physics.haversineM(points[i - 1], points[i])
            cum += total
        }
        val placed = maneuvers.map { m ->
            val i = m.index.coerceIn(0, points.size - 1)
            m.copy(at = points[i], index = i, cumM = cum[i])
        }
        val summary = trip["summary"] as? JsonObject
        return RouteLine(
            ok = true, source = source, sourceDetail = sourceDetail,
            points = points, cumM = cum, maneuvers = placed,
            distanceM = (summary?.get("length")?.jsonPrimitive?.doubleOrNull ?: 0.0) * perUnit,
            durationS = summary?.get("time")?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    /** Google's polyline algorithm at Valhalla's precision, 1e6. */
    fun decodePolyline6(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = encoded[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            out += LatLon(lat / 1e6, lon / 1e6)
        }
        return out
    }
}
