package online.iamprettyfamous.navi.data

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private data class Entry(val name: String, val size: Long, val sha256: String)

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
            val manifest = try {
                app.assets.open("data/MANIFEST").bufferedReader().use { it.readLines() }
            } catch (e: FileNotFoundException) {
                return@post // this build carries no data: the screens say what is missing
            }
            val version = manifest.first().removePrefix("version ").trim()
            val entries = manifest.drop(1).filter { it.isNotBlank() }.map { line ->
                val (name, size, sha) = line.split("\t")
                Entry(name, size.toLong(), sha)
            }
            val prefs = app.getSharedPreferences("navi", Context.MODE_PRIVATE)
            val inPlace = prefs.getString(PREF, null) == version &&
                entries.all { e -> target(app, e.name)?.let { it.isFile && it.length() == e.size } == true }
            if (inPlace) {
                _state.value = State(version = version)
                return@post
            }
            val total = entries.sumOf { it.size }
            val room = (app.getExternalFilesDir(null) ?: app.filesDir).usableSpace
            if (room < total + SPARE_BYTES) {
                _state.value = State(
                    error = "Not enough space for the map: it needs %,d MB free and the phone has %,d MB"
                        .format((total + SPARE_BYTES) / 1_000_000, room / 1_000_000),
                )
                return@post
            }
            Log.i(TAG, "unpacking $version: ${entries.size} files, ${total / 1_000_000} MB")
            val t0 = System.currentTimeMillis()
            var done = 0L
            _state.value = State(busy = true, totalBytes = total)
            for (e in entries) {
                val dst = target(app, e.name) ?: continue
                dst.parentFile?.mkdirs()
                val part = File(dst.path + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    app.assets.open("data/${e.name}").use { input ->
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
                    }
                } catch (ex: Exception) {
                    part.delete()
                    Log.w(TAG, "unpacking ${e.name} failed", ex)
                    _state.value = State(error = "The map could not be unpacked (${e.name}: ${ex.message}) - free some space and open xnav.k again")
                    return@post
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (part.length() != e.size || sha != e.sha256) {
                    part.delete()
                    _state.value = State(error = "The map data in this app is damaged (${e.name}) - download the app again")
                    return@post
                }
                if (!part.renameTo(dst)) {
                    part.delete()
                    _state.value = State(error = "The map could not be put in place (${e.name}) - open xnav.k again")
                    return@post
                }
            }
            prefs.edit().putString(PREF, version).apply()
            Log.i(TAG, "unpacked $version in ${System.currentTimeMillis() - t0} ms")
            _state.value = State(version = version)
            main.post(changed)
        }
    }
}
