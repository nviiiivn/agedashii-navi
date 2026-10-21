package online.iamprettyfamous.navi.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import online.iamprettyfamous.navi.core.Disk
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.map.PmtilesHeader
import online.iamprettyfamous.navi.core.map.StylePatch
import online.iamprettyfamous.navi.data.DataFiles
import online.iamprettyfamous.navi.host.MapEvents
import online.iamprettyfamous.navi.host.MapHost
import online.iamprettyfamous.navi.record.Fix

/**
 * The map's decisions, on the iPhone: the Android app's RideMapController,
 * with MapLibre itself behind the Swift app's MapHost. Here the WHOLE style is
 * built before MapLibre sees it - the route's casing and line slotted below the
 * style's agedashii:route-below, the destination marks on top, tiles and glyphs
 * pointed at files in the app - so the Swift side only loads a document and
 * swaps GeoJSON, and both phones draw the route from the same metadata.
 *
 * Nothing in here touches the network: the tiles, the styles and the glyphs
 * are all in the app.
 */
class RideMap(private val host: MapHost) : MapEvents {

    /** STYLE_MISSING is the one that still draws a map: this theme's, no; Night's, yes. */
    enum class Problem { NO_TILES, BAD_TILES, NO_STYLE, STYLE_MISSING }

    private var header: PmtilesHeader? = null
    private var cameraSet = false
    private var styleLoaded = false

    var theme = "night"
        private set
    var northUp = false
        private set

    /** Told whenever the camera starts or stops following her. */
    var followingChanged: (Boolean) -> Unit = {}

    /** What stops the map drawing, in words she can act on; null when nothing. */
    var problemChanged: (Problem?) -> Unit = {}

    /** A long press on the map: the point she might want to go to. */
    var longPressed: (LatLon) -> Unit = {}

    fun start(theme: String, bike: String, northUp: Boolean, buildings: Boolean) {
        this.northUp = northUp
        this.buildings = buildings
        host.setEvents(this)
        host.setBike(bike)
        applyTheme(theme)
    }

    fun applyTheme(key: String) {
        theme = key
        val tiles = DataFiles.tiles ?: return problem(Problem.NO_TILES)
        val h = header ?: PmtilesHeader.read(tiles)?.also { header = it } ?: return problem(Problem.BAD_TILES)
        placeCamera(h)
        // A theme with no map style still gets a map - Night's - but SAYS so: a
        // fallback nobody can see is indistinguishable from a feature that works.
        val own = style(key, tiles)
        val raw = own ?: style("night", tiles) ?: return problem(Problem.NO_STYLE)
        problem(if (own == null) Problem.STYLE_MISSING else null)
        val patched = StylePatch.realZoomRange(raw, h.minZoom, h.maxZoom)
        for (change in patched.changes) println("[map] $change (from the archive)")
        styleLoaded = false
        host.loadStyle(withRide(patched.json))
    }

    /** The style JSON for [theme] with its placeholders filled, or null when the app carries no such style. */
    private fun style(theme: String, tiles: String): String? {
        val dir = DataFiles.folder("styles") ?: return null
        val glyphs = DataFiles.folder("glyphs") ?: return null
        val raw = runCatching { Disk.read("$dir/$theme.json".toPath()) { readUtf8() } }.getOrNull() ?: return null
        return raw
            .replace("{TILES}", "pmtiles://file://$tiles")
            .replace("\"{GLYPHS}\"", "\"file://$glyphs/{fontstack}/{range}.pbf\"")
            .replace("{GLYPHS}", "file://$glyphs")
    }

    private fun placeCamera(h: PmtilesHeader) {
        if (cameraSet) return
        cameraSet = true
        host.setLimits(h.south, h.west, h.north, h.east, maxOf(h.minZoom.toDouble(), MIN_ZOOM), MAX_ZOOM)
        host.setCamera(h.centerLat, h.centerLon, maxOf(h.centerZoom.toDouble(), 11.0))
    }

    // ------------------------------------------------------------ the route

    private var buildings = false
    private var buildingsLayer: String? = null
    private var routePoints: List<LatLon>? = null
    private var dest: LatLon? = null
    private var pin: LatLon? = null

    /**
     * The style with the ride's own sources and layers in it, as the style's
     * metadata asks (agedashii:route, written by mapdata/gen_styles.py so every
     * app draws the same): a casing, then the line, both BELOW
     * agedashii:route-below - the first label layer - so street names stay
     * readable on top of the route; and the marks on top of everything. The
     * destination in warn, the offered point in accent and bigger, so the two
     * are never mistaken for each other.
     */
    private fun withRide(json: String): String {
        val style = Json.parseToJsonElement(json).jsonObject
        val meta = style["metadata"] as? JsonObject
        buildingsLayer = meta?.string("agedashii:buildings-layer")
        val routeBelow = meta?.string("agedashii:route-below")
        val look = meta?.get("agedashii:route") as? JsonObject
        val colours = meta?.get("agedashii:palette") as? JsonObject
        fun colour(name: String, fallback: String) = colours?.string(name) ?: fallback
        val width: JsonElement = look?.get("line-width") ?: Json.parseToJsonElement(FALLBACK_WIDTH)
        val casingWidth = widened(width, 2 * ((look?.get("casing-extra-px") as? JsonPrimitive)?.doubleOrNull ?: 3.0))
        val bg = colour("bg", "#06080b")
        val lineColour = look?.string("line-color") ?: colour("road", "#39d0ff")
        val casingColour = look?.string("casing-color") ?: bg
        val accent = colour("accent", "#39d0ff")
        val warn = colour("warn", "#ffb020")
        val empty = Json.parseToJsonElement(EMPTY)

        val sources = JsonObject((style["sources"] as? JsonObject).orEmpty() + mapOf(
            ROUTE_SOURCE to buildJsonObject { put("type", "geojson"); put("data", empty) },
            MARKS_SOURCE to buildJsonObject { put("type", "geojson"); put("data", empty) },
        ))
        fun line(id: String, colour: String, w: JsonElement) = buildJsonObject {
            put("id", id); put("type", "line"); put("source", ROUTE_SOURCE)
            put("layout", buildJsonObject { put("line-cap", "round"); put("line-join", "round") })
            put("paint", buildJsonObject { put("line-color", colour); put("line-width", w) })
        }
        fun byKind(pin: JsonElement, dest: JsonElement) = buildJsonArray {
            add(JsonPrimitive("match")); add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("kind")) })
            add(JsonPrimitive("pin")); add(pin); add(dest)
        }
        val marks = buildJsonObject {
            put("id", MARKS_LAYER); put("type", "circle"); put("source", MARKS_SOURCE)
            put("paint", buildJsonObject {
                put("circle-color", byKind(JsonPrimitive(accent), JsonPrimitive(warn)))
                put("circle-radius", byKind(JsonPrimitive(14), JsonPrimitive(11)))
                put("circle-stroke-color", bg)
                put("circle-stroke-width", byKind(JsonPrimitive(3), JsonPrimitive(2)))
            })
        }
        val layers = (style["layers"] as? JsonArray).orEmpty().toMutableList()
        val at = layers.indexOfFirst { (it as? JsonObject)?.string("id") == routeBelow }
        val route = listOf(line(ROUTE_CASING, casingColour, casingWidth), line(ROUTE_LINE, lineColour, width))
        if (at >= 0) layers.addAll(at, route) else {
            println("[map] no layer '$routeBelow' to put the route under; drawing it on top")
            layers.addAll(route)
        }
        layers.add(marks)
        return JsonObject(style + mapOf("sources" to sources, "layers" to JsonArray(layers))).toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /**
     * [width] with [extra] px added at every stop. A zoom expression may only be
     * the top-level interpolate, so `["+", width, extra]` is refused; the stops
     * themselves have to grow.
     */
    private fun widened(width: JsonElement, extra: Double): JsonElement {
        val a = width as? JsonArray ?: return width
        if ((a.firstOrNull() as? JsonPrimitive)?.content != "interpolate") return width
        return JsonArray(a.mapIndexed { i, v ->
            if (i >= 4 && i % 2 == 0) JsonPrimitive(((v as? JsonPrimitive)?.doubleOrNull ?: 0.0) + extra) else v
        })
    }

    /** Draws [points] as the route, or none. Kept across theme changes. */
    fun showRoute(points: List<LatLon>?) {
        routePoints = points
        host.setSourceData(ROUTE_SOURCE, lineJson(points))
    }

    /** The destination she has, and the point she long-pressed and has not chosen yet. */
    fun showMarks(dest: LatLon?, pin: LatLon?) {
        this.dest = dest
        this.pin = pin
        host.setSourceData(MARKS_SOURCE, marksJson())
    }

    private fun lineJson(points: List<LatLon>?): String {
        if (points == null || points.size < 2) return EMPTY
        val coords = points.joinToString(",") { "[${it.lon},${it.lat}]" }
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}]}"
    }

    private fun marksJson(): String {
        val marks = listOfNotNull(dest?.let { "dest" to it }, pin?.let { "pin" to it })
        val features = marks.joinToString(",") { (kind, p) ->
            "{\"type\":\"Feature\",\"properties\":{\"kind\":\"$kind\"}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lon},${p.lat}]}}"
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
    }

    /**
     * Buildings ship in the tiles as one layer, hidden by default in every
     * style; this only flips its visibility. The layer id comes from the
     * style's own metadata, so every app flips the same thing.
     */
    fun setBuildings(on: Boolean) {
        buildings = on
        buildingsLayer?.let { host.setLayerVisible(it, on) }
    }

    fun setBike(colour: String) = host.setBike(colour)

    fun setNorthUp(on: Boolean) {
        northUp = on
        follow()
    }

    private var hadFix = false

    /**
     * Back to following her after she has panned away. No fix yet: follow, but
     * keep the overview (a negative zoom is "leave the zoom alone") - riding
     * zoom at the map's centre was a blank patch of the Bay that looked broken.
     */
    fun follow() = host.follow(northUp, if (hadFix) RIDING_ZOOM else -1.0)

    /** Moves her bike to [fix]; the camera follows if it is following. */
    fun showFix(fix: Fix) {
        host.showFix(fix.latitude, fix.longitude, fix.bearing ?: -1.0, fix.accuracy ?: -1.0)
        // Following starts before any fix exists, and keeps the overview zoom
        // until there is somewhere to zoom to. Redo it on the first.
        if (!hadFix) {
            hadFix = true
            follow()
        }
    }

    /** Keeps her bike out from under the turn banner: the camera centres below [points]. */
    fun setTopClear(points: Double) = host.setTopInset(points)

    /**
     * Whether [p] is inside the offline map, or null before the map is read.
     * The router's tiles cover the same box, so outside it there is no map AND
     * no route.
     */
    fun covers(p: LatLon): Boolean? = header?.let { h ->
        p.lat in h.south..h.north && p.lon in h.west..h.east
    }

    private fun problem(p: Problem?) = problemChanged(p)

    // ------------------------------------------------------------ MapEvents

    override fun onStyleLoaded() {
        styleLoaded = true
        buildingsLayer?.let { host.setLayerVisible(it, buildings) }
        host.setSourceData(ROUTE_SOURCE, lineJson(routePoints))
        host.setSourceData(MARKS_SOURCE, marksJson())
    }

    override fun onStyleFailed(why: String) {
        println("[map] style failed: $why")
        problem(Problem.NO_STYLE)
    }

    override fun onFollowingChanged(following: Boolean) = followingChanged(following)

    override fun onLongPress(lat: Double, lon: Double) = longPressed(LatLon(lat, lon))

    private companion object {
        const val MIN_ZOOM = 8.0
        // Vector tiles overzoom sharply past the archive's own maxzoom.
        const val MAX_ZOOM = 19.0
        const val RIDING_ZOOM = 16.5
        const val ROUTE_SOURCE = "agedashii-route"
        const val ROUTE_CASING = "agedashii-route-casing"
        const val ROUTE_LINE = "agedashii-route-line"
        const val MARKS_SOURCE = "agedashii-marks"
        const val MARKS_LAYER = "agedashii-marks"
        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        const val FALLBACK_WIDTH = "[\"interpolate\",[\"exponential\",1.5],[\"zoom\"],10,2.75,14,7.5,18,32.5]"
    }
}
