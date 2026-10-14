package online.iamprettyfamous.navi.data

import android.net.Uri
import java.util.zip.ZipInputStream
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.places.PlaceSearch
import online.iamprettyfamous.navi.map.MapFiles
import online.iamprettyfamous.navi.places.PlacesStore
import online.iamprettyfamous.navi.routing.Router
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Her map data, carried inside the APK (built with -PnaviData, see
 * tools/make-datapack.py) and unpacked to app storage on first start: the
 * map, the router's tiles and the places list all need a real file path, and
 * the phone then needs one download and no cable.
 *
 * Unpacked again only when a new APK carries a different MANIFEST version,
 * so an update with the same map costs no copy. Every file is checked against
 * the MANIFEST's size and sha256 before it replaces anything; a damaged copy
 * is said, and the old file stays.
 */
object DataPack {
    private const val TAG = "NaviData"
    private const val PREF = "data_version"
    private const val PREF_REGION = "data_region"

    /** What a pack file is called, for the picker and the error messages. */
    const val PACK_SUFFIX = ".navipack"

    /** Room left over after the unpack: the ride logs and the system need some. */
    private const val SPARE_BYTES = 200_000_000L

    data class State(
        val busy: Boolean = false,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        /** Why the data could not be unpacked, in words she can act on. */
        val error: String = "",
        /** The MANIFEST version now in place, or "" when this APK carries none. */
        val version: String = "",
        /** Which region is on the phone, from the MANIFEST's `region` line. */
        val region: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private data class Entry(val name: String, val size: Long, val sha256: String)

    /**
     * A MANIFEST read: its version, the region it names, and one entry per
     * file. Lines without tabs are headers (`version`, `region`), so an older
     * pack with no region line still reads.
     */
    private class Listing(lines: List<String>) {
        val version: String = lines.firstOrNull { it.startsWith("version ") }
            ?.removePrefix("version ")?.trim() ?: ""
        val region: String = lines.firstOrNull { it.startsWith("region ") }
            ?.removePrefix("region ")?.trim() ?: ""
        val entries: List<Entry> = lines.filter { it.contains('\t') }.map { line ->
            val (name, size, sha) = line.split("\t")
            Entry(name, size.toLong(), sha)
        }
    }

    private val handler: Handler by lazy { Handler(HandlerThread("data").apply { start() }.looper) }
    private val main = Handler(Looper.getMainLooper())

    /** Where each file the MANIFEST names belongs. */
    private fun target(context: Context, name: String): File? {
        val ext = context.getExternalFilesDir(null) ?: context.filesDir
        return when (name) {
            MapFiles.TILES_NAME -> File(File(ext, "map"), name)
            Router.TILES_NAME -> Router.tilesFile(context)
            PlaceSearch.FILENAME -> PlacesStore.file(context)
            else -> null
        }
    }

    /** Unpack if this APK's data is not in place yet; [changed] runs on the main thread after. */
    fun ensure(context: Context, changed: () -> Unit) {
        val app = context.applicationContext
        handler.post {
            val lines = try {
                app.assets.open("data/MANIFEST").bufferedReader().use { it.readLines() }
            } catch (e: FileNotFoundException) {
                return@post // this build carries no data: the screens say what is missing
            }
            val listing = Listing(lines)
            val prefs = app.getSharedPreferences("navi", Context.MODE_PRIVATE)
            val inPlace = prefs.getString(PREF, null) == listing.version &&
                listing.entries.all { e -> target(app, e.name)?.let { it.isFile && it.length() == e.size } == true }
            if (inPlace) {
                _state.value = State(version = listing.version, region = prefs.getString(PREF_REGION, "") ?: "")
                return@post
            }
            // The APK's own data, read in the MANIFEST's order.
            val left = listing.entries.toMutableList()
            install(app, listing, changed, damaged = "download the app again") {
                left.removeFirstOrNull()?.let { e -> e.name to app.assets.open("data/${e.name}") }
            }
        }
    }

    /**
     * A map pack she picked, swapped in for whatever region is on the phone.
     *
     * Her request: "miami/keys/ft lauderdale, greater seattle area (redmond to
     * tacoma?), bay area, NYC area ... premade packs for others to use". A pack
     * (tools/make_pack.py) is a zip whose FIRST entry is the MANIFEST, so the
     * region's name and every size and hash are known before a byte of the 200
     * MB behind them is written, and the file never has to be copied somewhere
     * seekable first. Each file is verified and renamed into place exactly as
     * the APK's own data is, through the same code below.
     */
    fun importPack(context: Context, uri: Uri, changed: () -> Unit) {
        val app = context.applicationContext
        handler.post {
            val stream = try {
                app.contentResolver.openInputStream(uri)
            } catch (ex: Exception) {
                Log.w(TAG, "opening the pack failed", ex)
                null
            }
            if (stream == null) {
                _state.value = State(error = "That map pack could not be opened - pick the $PACK_SUFFIX file again")
                return@post
            }
            stream.use { raw ->
                val zip = ZipInputStream(raw.buffered())
                val first = zip.nextEntry
                if (first == null || first.name.substringAfterLast('/') != "MANIFEST") {
                    _state.value = State(
                        error = "That file is not a map pack - a pack is a $PACK_SUFFIX whose first entry is its MANIFEST",
                    )
                    return@post
                }
                val listing = Listing(zip.readBytes().decodeToString().lines())
                if (listing.entries.isEmpty()) {
                    _state.value = State(error = "That map pack names no files - it may be incomplete")
                    return@post
                }
                Log.i(TAG, "importing ${listing.region.ifEmpty { "a pack" }} ${listing.version}")
                install(app, listing, changed, damaged = "ask for the pack again") {
                    // The entries after the MANIFEST, in whatever order the pack
                    // holds them; install matches each to the MANIFEST by name.
                    var entry = zip.nextEntry
                    while (entry != null && entry.isDirectory) entry = zip.nextEntry
                    entry?.let { it.name.substringAfterLast('/') to zip }
                }
            }
        }
    }

    /**
     * Put a listing's files in place from [next], which yields one (name,
     * bytes) at a time and null when there are no more. The stream it hands
     * back is NOT closed here: a zip hands back the archive itself.
     *
     * Every file is checked against the MANIFEST's size and sha256 before it
     * replaces anything, and written beside its target as .part first, so a
     * download that dies halfway leaves the working map alone.
     */
    private fun install(
        app: Context,
        listing: Listing,
        changed: () -> Unit,
        damaged: String,
        next: () -> Pair<String, java.io.InputStream>?,
    ) {
        val total = listing.entries.sumOf { it.size }
        val room = (app.getExternalFilesDir(null) ?: app.filesDir).usableSpace
        if (room < total + SPARE_BYTES) {
            _state.value = State(
                error = "Not enough space for the map: it needs %,d MB free and the phone has %,d MB"
                    .format((total + SPARE_BYTES) / 1_000_000, room / 1_000_000),
            )
            return
        }
        Log.i(TAG, "unpacking ${listing.version}: ${listing.entries.size} files, ${total / 1_000_000} MB")
        val t0 = System.currentTimeMillis()
        var done = 0L
        val seen = HashSet<String>()
        _state.value = State(busy = true, totalBytes = total)
        while (true) {
            val (name, input) = next() ?: break
            val e = listing.entries.firstOrNull { it.name == name }
            if (e == null) {
                Log.i(TAG, "$name is not in the MANIFEST: skipped")
                continue
            }
            val dst = target(app, e.name) ?: continue
            dst.parentFile?.mkdirs()
            val part = File(dst.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var shown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - shown > 8_000_000) {
                            shown = done
                            _state.value = State(busy = true, doneBytes = done, totalBytes = total)
                        }
                    }
                }
            } catch (ex: Exception) {
                part.delete()
                Log.w(TAG, "unpacking ${e.name} failed", ex)
                _state.value = State(error = "The map could not be unpacked (${e.name}: ${ex.message}) - free some space and open Navi again")
                return
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != e.size || sha != e.sha256) {
                part.delete()
                _state.value = State(error = "The map data is damaged (${e.name}) - $damaged")
                return
            }
            if (!part.renameTo(dst)) {
                part.delete()
                _state.value = State(error = "The map could not be put in place (${e.name}) - open Navi again")
                return
            }
            seen += e.name
        }
        val missing = listing.entries.map { it.name }.filterNot { it in seen }
        if (missing.isNotEmpty()) {
            _state.value = State(error = "That map pack is incomplete (no ${missing.first()}) - ask for it again")
            return
        }
        app.getSharedPreferences("navi", Context.MODE_PRIVATE).edit()
            .putString(PREF, listing.version).putString(PREF_REGION, listing.region).apply()
        Log.i(TAG, "unpacked ${listing.version} in ${System.currentTimeMillis() - t0} ms")
        _state.value = State(version = listing.version, region = listing.region)
        main.post(changed)
    }
}
