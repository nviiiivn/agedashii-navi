package online.iamprettyfamous.navi.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Makes a MapLibre style tell the truth about the tiles under it.
 *
 * Every `pmtiles://` source gets the archive's REAL zoom range, whatever the
 * style file claims. This is load-bearing, not tidiness: MapLibre 13.6.1 lets a
 * style's maxzoom override the range it reads from the archive, and a maxzoom
 * above the data's asks for tiles that do not exist, so the map draws nothing
 * (minion 3's A/B build, 2026-09-27: style 16 on a z14 archive went blank
 * without this, and drew streets with it). The Flet map's blank-above-z15 bug,
 * which cost two days, was this same mistake.
 *
 * Also makes sure the OpenStreetMap credit is on the source, since the tiles
 * are OSM data under the ODbL.
 */
object StylePatch {
    const val OSM_CREDIT = "© OpenStreetMap contributors"

    /** The patched style, and one line per source whose zoom range the style had wrong. */
    data class Result(val json: String, val changes: List<String>)

    fun realZoomRange(styleJson: String, minZoom: Int, maxZoom: Int): Result {
        val root = Json.parseToJsonElement(styleJson).jsonObject
        val sources = root["sources"] as? JsonObject ?: return Result(styleJson, emptyList())
        val changes = ArrayList<String>()
        val patched = LinkedHashMap<String, JsonElement>()
        for ((name, element) in sources) {
            val src = element as? JsonObject
            val url = (src?.get("url") as? JsonPrimitive)?.contentOrNull
            if (src == null || url == null || !url.startsWith("pmtiles://")) {
                patched[name] = element
                continue
            }
            // Compared as numbers: 14 and 14.0 are the same claim.
            val said = (src["maxzoom"] as? JsonPrimitive)?.doubleOrNull
            if (said != maxZoom.toDouble()) changes += "source $name maxzoom ${src["maxzoom"]} -> $maxZoom"
            val fields = LinkedHashMap<String, JsonElement>(src)
            fields["minzoom"] = JsonPrimitive(minZoom)
            fields["maxzoom"] = JsonPrimitive(maxZoom)
            val credit = (src["attribution"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (!credit.contains("OpenStreetMap")) fields["attribution"] = JsonPrimitive(OSM_CREDIT)
            patched[name] = JsonObject(fields)
        }
        val out = LinkedHashMap<String, JsonElement>(root)
        out["sources"] = JsonObject(patched)
        return Result(JsonObject(out).toString(), changes)
    }
}
