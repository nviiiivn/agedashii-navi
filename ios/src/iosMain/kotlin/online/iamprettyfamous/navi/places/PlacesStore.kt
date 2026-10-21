package online.iamprettyfamous.navi.places

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.nowSeconds
import online.iamprettyfamous.navi.core.places.PlaceHit
import online.iamprettyfamous.navi.core.places.PlaceIndex
import online.iamprettyfamous.navi.core.places.PlaceRow
import online.iamprettyfamous.navi.core.places.PlaceSearch
import online.iamprettyfamous.navi.core.places.PlaceSql
import online.iamprettyfamous.navi.core.places.Places
import online.iamprettyfamous.navi.data.DataFiles
import online.iamprettyfamous.navi.ui.format
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.sqlite3.SQLITE_NULL
import platform.sqlite3.SQLITE_OK
import platform.sqlite3.SQLITE_OPEN_READONLY
import platform.sqlite3.SQLITE_ROW
import platform.sqlite3.sqlite3
import platform.sqlite3.sqlite3_bind_text
import platform.sqlite3.sqlite3_close
import platform.sqlite3.sqlite3_column_double
import platform.sqlite3.sqlite3_column_int64
import platform.sqlite3.sqlite3_column_text
import platform.sqlite3.sqlite3_column_type
import platform.sqlite3.sqlite3_errmsg
import platform.sqlite3.sqlite3_finalize
import platform.sqlite3.sqlite3_open_v2
import platform.sqlite3.sqlite3_prepare_v2
import platform.sqlite3.sqlite3_step
import platform.sqlite3.sqlite3_stmt

/**
 * [PlaceIndex] on the iPhone's own SQLite (the C library, read-only), running
 * core's [PlaceSql] - the Android app's AndroidPlaceIndex, query for query.
 * Arguments are bound as text, as Android's rawQuery binds them.
 */
class IosPlaceIndex(path: String) : PlaceIndex {
    private val db: CPointer<sqlite3> = memScoped {
        val out = alloc<CPointerVar<sqlite3>>()
        val rc = sqlite3_open_v2(path, out.ptr, SQLITE_OPEN_READONLY, null)
        val handle = out.value
        if (rc != SQLITE_OK || handle == null) {
            val why = handle?.let { sqlite3_errmsg(it)?.toKString() } ?: "code $rc"
            handle?.let { sqlite3_close(it) }
            error("cannot open $path: $why")
        }
        handle
    }

    /** One statement: bind [args], read each row with [read], always finalized. */
    private fun <T> query(sql: String, args: List<String>, read: Row.() -> T): List<T> = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(db, sql, -1, out.ptr, null) != SQLITE_OK) {
            error("sqlite: ${sqlite3_errmsg(db)?.toKString()} in $sql")
        }
        val stmt = out.value ?: error("sqlite: no statement for $sql")
        try {
            args.forEachIndexed { i, a -> sqlite3_bind_text(stmt, i + 1, a, -1, TRANSIENT) }
            val row = Row(stmt)
            buildList { while (sqlite3_step(stmt) == SQLITE_ROW) add(row.read()) }
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    /** The current row of a statement, read the way android.database.Cursor reads one. */
    class Row(private val stmt: CPointer<sqlite3_stmt>) {
        fun getString(i: Int): String =
            sqlite3_column_text(stmt, i)?.reinterpret<ByteVar>()?.toKString() ?: ""
        fun getLong(i: Int): Long = sqlite3_column_int64(stmt, i)
        fun getInt(i: Int): Int = sqlite3_column_int64(stmt, i).toInt()
        fun getDouble(i: Int): Double = sqlite3_column_double(stmt, i)
        fun isNull(i: Int): Boolean = sqlite3_column_type(stmt, i) == SQLITE_NULL
    }

    private val tables = query(PlaceSql.TABLES, emptyList()) { getString(0) }.toSet()

    override fun claimedCount() = query(PlaceSql.CLAIMED, emptyList()) { getString(0).toInt() }.first()
    override fun countedRows() = query(PlaceSql.COUNTED, emptyList()) { getInt(0) }.first()
    override fun hasAreas() = tables.containsAll(listOf("area", "area_tok", "kind_tok"))
    private val columns = query(PlaceSql.COLUMNS, emptyList()) { getString(1) }.toSet()
    override fun hasAddr() = "addr" in columns
    override fun namePrefix(form: String) = query(PlaceSql.NAME_PREFIX, listOf(form, PlaceSql.until(form))) { getLong(0) }.toSet()
    override fun kindPrefix(form: String) = query(PlaceSql.KIND_PREFIX, listOf(form, PlaceSql.until(form))) { getString(0) }.toSet()
    override fun areaPrefix(form: String) = query(PlaceSql.AREA_PREFIX, listOf(form, PlaceSql.until(form))) { getLong(0) }.toSet()

    override fun rows(ids: List<Long>): List<PlaceRow> = ids.chunked(PlaceSql.IN_CHUNK).flatMap { chunk ->
        val sql = (if (!hasAreas()) PlaceSql.ROWS_V1
            else if (hasAddr()) PlaceSql.ROWS_V2 else PlaceSql.ROWS_V2_NO_ADDR) +
            PlaceSql.marks(chunk.size)
        query(sql, chunk.map { it.toString() }) {
            PlaceRow(
                getLong(0), getString(1), getString(2), getDouble(3), getDouble(4),
                getDouble(5), getInt(6) != 0,
                if (isNull(7)) null else getLong(7), if (isNull(8)) null else getLong(8),
                if (isNull(9)) null else getString(9),
            )
        }
    }

    override fun areaNames(aids: Collection<Long>): Map<Long, String> {
        if (aids.isEmpty()) return emptyMap()
        return query(PlaceSql.AREAS + PlaceSql.marks(aids.size), aids.map { it.toString() }) { getLong(0) to getString(1) }.toMap()
    }

    fun close() {
        sqlite3_close(db)
    }

    private companion object {
        // SQLITE_TRANSIENT, a C macro cinterop cannot see: "copy this string now".
        val TRANSIENT = (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()
    }
}

/**
 * Where-to's search, off the main thread. The newest query wins: one typed
 * while another runs replaces it, and a stale answer is dropped. The Android
 * app's PlacesStore, with the same State and Hit, so WhereTo.kt is shared.
 */
object PlacesStore {
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

    private val queue = dispatch_queue_create("places", null)
    private var index: IosPlaceIndex? = null
    private var latest = 0

    /** Open (or reopen) and say whether the list is usable. */
    fun open() {
        val path = DataFiles.places
        dispatch_async(queue) {
            index?.close()
            index = path?.let { p ->
                runCatching { IosPlaceIndex(p) }.onFailure { println("[places] could not open $p: $it") }.getOrNull()
            }
            val (count, words) = when {
                path == null -> PlaceSearch.status(null)
                index == null -> null to "The places list on this phone could not be read."
                else -> PlaceSearch.status(index)
            }
            _state.value = _state.value.copy(status = words, usable = count != null)
            println("[places] $words")
        }
    }

    /** Search for [text] near [near]. The answer arrives in [state]. */
    fun search(text: String, near: LatLon?) {
        val ticket = ++latest
        _state.value = _state.value.copy(query = text)
        dispatch_async(queue) {
            if (ticket != latest) return@dispatch_async
            val t0 = nowSeconds()
            val hits = ArrayList<Hit>()
            // A pasted point comes first: it is exactly what she asked for.
            Places.parsePoint(text)?.let { p ->
                val at = LatLon(p.lat, p.lon)
                hits += Hit("%.5f, %.5f".format(p.lat, p.lon), at, "point, ${p.label}", near?.let { Geo.haversineM(it, at) })
            }
            val idx = index
            if (idx != null && _state.value.usable) {
                val found: List<PlaceHit> = runCatching { PlaceSearch.search(idx, text, near) }
                    .onFailure { println("[places] search failed: $it") }.getOrDefault(emptyList())
                found.mapTo(hits) { Hit(it.name, LatLon(it.lat, it.lon), it.detail, it.distM) }
            }
            if (ticket != latest) return@dispatch_async
            val ms = ((nowSeconds() - t0) * 1000).toLong()
            dispatch_async(dispatch_get_main_queue()) {
                if (ticket == latest) _state.value = _state.value.copy(query = text, hits = hits, ms = ms, hitsFor = text)
            }
        }
    }
}
