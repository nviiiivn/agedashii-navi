package online.iamprettyfamous.navi.map

import android.content.res.AssetManager
import java.io.File

/**
 * One MapLibre style per app theme, shipped in the APK at
 * `assets/styles/<theme>.json`, so the map wears the same palette as the chrome.
 *
 * The styles are written with two placeholders so that nothing in them names a
 * path on a particular phone:
 *  - `{TILES}`  becomes `pmtiles://file://<absolute path of the archive>`
 *  - `{GLYPHS}` becomes the glyph folder in the APK. It may stand for the whole
 *    glyphs URL or only its base, with `/{fontstack}/{range}.pbf` after it.
 */
object MapStyles {
    private const val GLYPHS_BASE = "asset://glyphs"
    private const val GLYPHS_URL = "$GLYPHS_BASE/{fontstack}/{range}.pbf"

    fun available(assets: AssetManager): Set<String> =
        assets.list("styles").orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .toSet()

    /** The style JSON for [theme], or null when the APK carries no such style. */
    fun load(assets: AssetManager, theme: String, tiles: File): String? {
        val raw = runCatching {
            assets.open("styles/$theme.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return raw
            .replace("{TILES}", "pmtiles://file://${tiles.absolutePath}")
            .replace("\"{GLYPHS}\"", "\"$GLYPHS_URL\"")
            .replace("{GLYPHS}", GLYPHS_BASE)
    }
}
