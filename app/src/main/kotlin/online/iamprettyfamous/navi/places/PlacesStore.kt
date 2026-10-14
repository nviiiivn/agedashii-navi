package online.iamprettyfamous.navi.places

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.places.PlaceHit
import online.iamprettyfamous.navi.core.places.PlaceIndex
import online.iamprettyfamous.navi.core.places.PlaceRow
import online.iamprettyfamous.navi.core.places.PlaceSearch
import online.iamprettyfamous.navi.core.places.PlaceSql
import online.iamprettyfamous.navi.core.places.Places
import java.io.File

/** [PlaceIndex] on Android's SQLite, read-only, running core's [PlaceSql]. */
class AndroidPlaceIndex(file: File) : PlaceIndex {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    private val tables = db.rawQuery(PlaceSql.TABLES, null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    override fun claimedCount() = db.rawQuery(PlaceSql.CLAIMED, null).use { it.moveToFirst(); it.getString(0).toInt() }
    override fun countedRows() = db.rawQuery(PlaceSql.COUNTED, null).use { it.moveToFirst(); it.getInt(0) }
    override fun hasAreas() = tables.containsAll(listOf("area", "area_tok", "kind_tok"))
    private val columns = db.rawQuery(PlaceSql.COLUMNS, null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(1)) }
    }
    override fun hasAddr() = "addr" in columns
    override fun namePrefix(form: String) = prefix(PlaceSql.NAME_PREFIX, form) { getLong(0) }
    override fun kindPrefix(form: String) = prefix(PlaceSql.KIND_PREFIX, form) { getString(0) }
    override fun areaPrefix(form: String) = prefix(PlaceSql.AREA_PREFIX, form) { getLong(0) }

    private fun <T> prefix(sql: String, form: String, read: android.database.Cursor.() -> T): Set<T> =
        db.rawQuery(sql, arrayOf(form, PlaceSql.until(form))).use { c -> buildSet { while (c.moveToNext()) add(c.read()) } }

    override fun rows(ids: List<Long>): List<PlaceRow> = ids.chunked(PlaceSql.IN_CHUNK).flatMap { chunk ->
        val sql = (if (!hasAreas()) PlaceSql.ROWS_V1
            else if (hasAddr()) PlaceSql.ROWS_V2 else PlaceSql.ROWS_V2_NO_ADDR) +
            PlaceSql.marks(chunk.size)
        db.rawQuery(sql, chunk.map { it.toString() }.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    PlaceRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4),
                        c.getDouble(5), c.getInt(6) != 0,
                        if (c.isNull(7)) null else c.getLong(7), if (c.isNull(8)) null else c.getLong(8),
                        if (c.isNull(9)) null else c.getString(9),
                    ),
                )
            }
        }
    }

    override fun areaNames(aids: Collection<Long>): Map<Long, String> {
        if (aids.isEmpty()) return emptyMap()
        return db.rawQuery(PlaceSql.AREAS + PlaceSql.marks(aids.size), aids.map { it.toString() }.toTypedArray()).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
    }

    fun close() = db.close()
}

/**
 * Where-to's search, off the main thread. The newest query wins: one typed
 * while another runs replaces it, and a stale answer is dropped.
 */
object PlacesStore {
    private const val TAG = "NaviPlaces"

    /** A row she can pick: a place from the list, or the point she pasted. */
    data class Hit(val name: String, val at: LatLon, val detail: String, val distM: Double?)

    data class State(
        val query: String = "",
        val hits: List<Hit> = emptyList(),
        /** "127,564 places on this phone..." or why there are none - shown before any search. */
        val status: String = "",
        val usable: Boolean = false,
        val ms: Long? = null,
        /** The query [hits] answer - behind [query] while a search runs. */
        val hitsFor: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val handler: Handler by lazy { Handler(HandlerThread("places").apply { start() }.looper) }
    private var index: AndroidPlaceIndex? = null
    @Volatile private var latest = 0

    fun file(context: Context): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "places"), PlaceSearch.FILENAME)

    /** Open (or reopen, after new data is loaded) and say whether the list is usable. */
    fun open(context: Context) {
        val f = file(context.applicationContext)
        handler.post {
            index?.close()
            index = if (f.isFile) {
                runCatching { AndroidPlaceIndex(f) }.onFailure { Log.w(TAG, "could not open $f", it) }.getOrNull()
            } else {
                null
            }
            val (count, words) = when {
                !f.isFile -> PlaceSearch.status(null)
                index == null -> null to "The places list on this phone could not be read - load the map data again."
                else -> PlaceSearch.status(index)
            }
            _state.value = _state.value.copy(status = words, usable = count != null)
            Log.i(TAG, words)
        }
    }

    /** Search for [text] near [near]. The answer arrives in [state]. */
    fun search(text: String, near: LatLon?) {
        val ticket = ++latest
        _state.value = _state.value.copy(query = text)
        handler.post {
            if (ticket != latest) return@post
            val t0 = SystemClock.elapsedRealtime()
            val hits = ArrayList<Hit>()
            // A pasted point comes first: it is exactly what she asked for.
            Places.parsePoint(text)?.let { p ->
                val at = LatLon(p.lat, p.lon)
                hits += Hit("%.5f, %.5f".format(p.lat, p.lon), at, "point, ${p.label}", near?.let { Geo.haversineM(it, at) })
            }
            val idx = index
            if (idx != null && _state.value.usable) {
                val found: List<PlaceHit> = runCatching { PlaceSearch.search(idx, text, near) }
                    .onFailure { Log.w(TAG, "search failed", it) }.getOrDefault(emptyList())
                found.mapTo(hits) { Hit(it.name, LatLon(it.lat, it.lon), it.detail, it.distM) }
            }
            if (ticket != latest) return@post
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "'$text': ${hits.size} in $ms ms" + (hits.firstOrNull()?.let { " - first ${it.name} (${it.detail})" } ?: ""))
            _state.value = _state.value.copy(query = text, hits = hits, ms = ms, hitsFor = text)
        }
    }
}
