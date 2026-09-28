package online.iamprettyfamous.navi.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StylePatchTest {
    private fun style(maxzoom: String, attribution: String? = null) = """
        {"version": 8, "name": "t",
         "sources": {
           "omt": {"type": "vector", "url": "pmtiles://file:///x/bayarea.pmtiles", "maxzoom": $maxzoom
                   ${if (attribution != null) ", \"attribution\": \"$attribution\"" else ""}},
           "route": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}}
         },
         "layers": [{"id": "background", "type": "background"},
                    {"id": "roads", "type": "line", "source": "omt", "source-layer": "transportation"}]}
    """.trimIndent()

    private fun source(json: String, name: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject["sources"]!!.jsonObject[name]!!.jsonObject

    // The Flet bug, and minion 3's A/B: a style claiming more zoom than the archive
    // holds must be pulled down to the archive's, or the map goes blank.
    @Test
    fun aStyleClaimingTooMuchZoomIsOverruledByTheArchive() {
        val out = StylePatch.realZoomRange(style("16"), minZoom = 0, maxZoom = 14)
        assertEquals(14, source(out.json, "omt")["maxzoom"]!!.jsonPrimitive.int)
        assertEquals(0, source(out.json, "omt")["minzoom"]!!.jsonPrimitive.int)
        assertEquals(listOf("source omt maxzoom 16 -> 14"), out.changes)
    }

    @Test
    fun aStyleClaimingTooLittleIsRaisedToo() {
        val out = StylePatch.realZoomRange(style("12"), minZoom = 0, maxZoom = 14)
        assertEquals(14, source(out.json, "omt")["maxzoom"]!!.jsonPrimitive.int)
        assertEquals(1, out.changes.size)
    }

    @Test
    fun aStyleThatAlreadyAgreesIsNotReportedEvenWrittenAsADouble() {
        assertEquals(emptyList(), StylePatch.realZoomRange(style("14"), 0, 14).changes)
        assertEquals(emptyList(), StylePatch.realZoomRange(style("14.0"), 0, 14).changes)
    }

    @Test
    fun aStyleWithNoMaxzoomGetsTheArchives() {
        val noMax = style("14").replace(", \"maxzoom\": 14", "")
        val out = StylePatch.realZoomRange(noMax, 0, 14)
        assertEquals(14, source(out.json, "omt")["maxzoom"]!!.jsonPrimitive.int)
    }

    @Test
    fun onlyPmtilesSourcesAreTouched() {
        val out = StylePatch.realZoomRange(style("16"), 0, 14)
        val route = source(out.json, "route")
        assertTrue("maxzoom" !in route && "attribution" !in route)
    }

    @Test
    fun theOsmCreditIsAddedWhenMissingAndKeptWhenPresent() {
        val added = StylePatch.realZoomRange(style("14"), 0, 14)
        assertEquals(StylePatch.OSM_CREDIT, source(added.json, "omt")["attribution"]!!.jsonPrimitive.content)
        val theirs = "<a>© OpenStreetMap</a> and friends"
        val kept = StylePatch.realZoomRange(style("14", theirs), 0, 14)
        assertEquals(theirs, source(kept.json, "omt")["attribution"]!!.jsonPrimitive.content)
    }

    @Test
    fun layersKeepTheirOrder() {
        val out = StylePatch.realZoomRange(style("16"), 0, 14)
        val ids = Json.parseToJsonElement(out.json).jsonObject["layers"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("background", "roads"), ids)
    }
}
