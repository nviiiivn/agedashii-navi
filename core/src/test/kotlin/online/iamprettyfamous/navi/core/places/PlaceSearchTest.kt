package online.iamprettyfamous.navi.core.places

import online.iamprettyfamous.navi.core.geo.LatLon
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PlaceIndex] over JDBC, running the same [PlaceSql] the app runs on Android's SQLite. */
class JdbcPlaceIndex(path: String) : PlaceIndex, AutoCloseable {
    private val con: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val tables = con.createStatement().executeQuery(PlaceSql.TABLES).let { rs ->
        buildSet { while (rs.next()) add(rs.getString(1)) }
    }

    override fun claimedCount() = con.createStatement().executeQuery(PlaceSql.CLAIMED).let { it.next(); it.getString(1).toInt() }
    override fun countedRows() = con.createStatement().executeQuery(PlaceSql.COUNTED).let { it.next(); it.getInt(1) }
    override fun hasAreas() = tables.containsAll(listOf("area", "area_tok", "kind_tok"))
    private val columns = con.createStatement().executeQuery(PlaceSql.COLUMNS).let { rs ->
        buildSet { while (rs.next()) add(rs.getString(2)) }
    }
    override fun hasAddr() = "addr" in columns
    override fun namePrefix(form: String) = prefix(PlaceSql.NAME_PREFIX, form) { getLong(1) }
    override fun kindPrefix(form: String) = prefix(PlaceSql.KIND_PREFIX, form) { getString(1) }
    override fun areaPrefix(form: String) = prefix(PlaceSql.AREA_PREFIX, form) { getLong(1) }

    private fun <T> prefix(sql: String, form: String, read: java.sql.ResultSet.() -> T): Set<T> {
        val st = con.prepareStatement(sql)
        st.setString(1, form)
        st.setString(2, PlaceSql.until(form))
        val rs = st.executeQuery()
        return buildSet { while (rs.next()) add(rs.read()) }
    }

    override fun rows(ids: List<Long>): List<PlaceRow> = ids.chunked(PlaceSql.IN_CHUNK).flatMap { chunk ->
        val st = con.prepareStatement((if (!hasAreas()) PlaceSql.ROWS_V1
            else if (hasAddr()) PlaceSql.ROWS_V2 else PlaceSql.ROWS_V2_NO_ADDR) +
            PlaceSql.marks(chunk.size))
        chunk.forEachIndexed { i, id -> st.setLong(i + 1, id) }
        val rs = st.executeQuery()
        buildList {
            while (rs.next()) add(
                PlaceRow(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5),
                    rs.getDouble(6), rs.getInt(7) != 0,
                    rs.getLong(8).takeUnless { rs.wasNull() }, rs.getLong(9).takeUnless { rs.wasNull() },
                ),
            )
        }
    }

    override fun areaNames(aids: Collection<Long>): Map<Long, String> {
        if (aids.isEmpty()) return emptyMap()
        val st = con.prepareStatement(PlaceSql.AREAS + PlaceSql.marks(aids.size))
        aids.forEachIndexed { i, id -> st.setLong(i + 1, id) }
        val rs = st.executeQuery()
        return buildMap { while (rs.next()) put(rs.getLong(1), rs.getString(2)) }
    }

    override fun close() = con.close()
}

/**
 * A list built the way tools/build_places.py write_db builds one - same
 * tables, same tokens, the same place drawn twice kept once - so the rule
 * tests below read the reason off a list small enough to see it.
 */
private data class Src(
    val name: String, val kind: String, val lat: Double, val lon: Double,
    val alts: List<String> = emptyList(), val size: Double = 0.0, val notable: Boolean = false,
    val areas: List<String?> = emptyList(),
)

private fun writeList(rows: List<Src>, path: String): Int =
    DriverManager.getConnection("jdbc:sqlite:$path").use { con ->
        con.createStatement().use { st ->
            for (sql in listOf(
                "CREATE TABLE place (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL, " +
                    "lon REAL NOT NULL, area_m2 REAL NOT NULL, notable INTEGER NOT NULL, a1 INTEGER, a2 INTEGER)",
                "CREATE TABLE tok (t TEXT NOT NULL, id INTEGER NOT NULL)",
                "CREATE TABLE area (aid INTEGER PRIMARY KEY, name TEXT NOT NULL)",
                "CREATE TABLE area_tok (t TEXT NOT NULL, aid INTEGER NOT NULL)",
                "CREATE TABLE kind_tok (t TEXT NOT NULL, kind TEXT NOT NULL)",
                "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
            )) st.execute(sql)
        }
        fun exec(sql: String, vararg args: Any?) = con.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
        val areaIds = HashMap<String, Int>()
        fun aid(area: String?): Int? {
            area ?: return null
            return areaIds.getOrPut(area) {
                val id = areaIds.size + 1
                exec("INSERT INTO area VALUES (?, ?)", id, area)
                for (w in PlaceSearch.normTokens(area).toSet()) exec("INSERT INTO area_tok VALUES (?, ?)", w, id)
                id
            }
        }
        val seen = HashMap<List<Any>, Int>()
        val kinds = HashSet<String>()
        var n = 0
        for (r in rows) {
            val key = listOf(PlaceSearch.normTokens(r.name).joinToString(" "), r.kind,
                (r.lat / 0.003).roundToLong(), (r.lon / 0.003).roundToLong())
            val pid = seen[key]
            if (pid != null) {
                exec("UPDATE place SET area_m2 = max(area_m2, ?), notable = max(notable, ?) WHERE id = ?",
                    r.size.roundToLong(), if (r.notable) 1 else 0, pid)
                continue
            }
            n += 1
            seen[key] = n
            val (town, hood) = (r.areas + listOf(null, null)).take(2)
            exec("INSERT INTO place VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", n, r.name, r.kind, r.lat, r.lon,
                r.size.roundToLong(), if (r.notable) 1 else 0, aid(town), aid(hood))
            if (kinds.add(r.kind)) for (w in PlaceSearch.normTokens(r.kind).toSet()) exec("INSERT INTO kind_tok VALUES (?, ?)", w, r.kind)
            val words = (listOf(r.name) + r.alts).flatMap { PlaceSearch.normTokens(it) }.toSet()
            for (w in words) exec("INSERT INTO tok VALUES (?, ?)", w, n)
        }
        exec("INSERT INTO meta VALUES ('count', ?)", n.toString())
        exec("INSERT INTO meta VALUES ('schema', '2')")
        n
    }

/**
 * The offline places search, ported from tests/test_places_offline.py
 * (NVII-DASH e481d24): the list, the rules, and her seven real searches.
 */
class PlaceSearchTest {
    private val dir = createTempDirectory("places").toFile()
    private val path = File(dir, PlaceSearch.FILENAME).path
    private val open = ArrayList<JdbcPlaceIndex>()

    @AfterTest
    fun tearDown() {
        open.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun index(p: String = path) = JdbcPlaceIndex(p).also { open += it }

    private fun names(text: String, near: LatLon = OAKLAND) = PlaceSearch.search(index(), text, near).map { it.name }

    // ---------------------------------------------------------- the list

    private fun small(): Int = writeList(ROWS, path)

    @Test
    fun `chabot is found with no signal and the museum ranks first`() {
        small()
        val top = PlaceSearch.search(index(), "chabot space science", OAKLAND)[0]
        assertEquals("Chabot Space and Science Center", top.name)
        assertEquals("museum", top.detail)
        assertEquals(37.8188, top.lat, 1e-4)
    }

    @Test
    fun `the ampersand spelling finds it too`() {
        small()
        assertEquals("Chabot Space and Science Center", names("Chabot Space & Science Center")[0])
    }

    @Test
    fun `a park is found`() {
        small()
        assertEquals("Tilden Regional Park", names("tilden")[0])
    }

    @Test
    fun `mt finds mount and ranks the peak first`() {
        small()
        assertEquals("Mount Diablo", names("mt diablo")[0])
    }

    @Test
    fun `a brand finds the branch`() {
        small()
        assertEquals("Starbucks Coffee", names("starbucks")[0])
    }

    @Test
    fun `accents do not matter`() {
        small()
        assertEquals("Café Rouge", names("cafe rouge")[0])
    }

    @Test
    fun `one letter is not a search`() {
        small()
        assertEquals(emptyList(), names("c"))
    }

    @Test
    fun `the same place drawn twice is one place`() {
        assertEquals(ROWS.size - 1, small())
        assertEquals(1, names("chabot space and science center").count { it == "Chabot Space and Science Center" })
    }

    // ---------------------------------------------------------- the rules

    @Test
    fun `a perfect name 100 km away never beats a poor one 1 km away`() {
        writeList(listOf(
            Src("Rose Garden", "garden", 38.7044, -122.2712, size = 5e6, notable = true, areas = listOf("Far Town")),
            Src("Old Rose Garden Tea Room", "cafe", 37.8134, -122.2712),
        ), path)
        assertEquals("Old Rose Garden Tea Room", names("rose garden")[0])
    }

    @Test
    fun `a town word needs a name word too`() {
        writeList(listOf(Src("Memorial Stadium", "stadium", 37.87, -122.25, size = 30000.0, notable = true, areas = listOf("Berkeley"))), path)
        assertEquals(listOf("Memorial Stadium"), names("berkeley stadium"))
        assertEquals(emptyList(), names("berkeley"))
    }

    @Test
    fun `a word only in an alternative name counts for less`() {
        writeList(listOf(
            Src("Spees Building", "building", 37.8187, -122.1805, alts = listOf("Chabot Science Center Spees Building"), size = 3000.0),
            Src("Chabot Science Center", "museum", 37.8188, -122.1804),
        ), path)
        assertEquals("Chabot Science Center", names("chabot science")[0])
    }

    @Test
    fun `the kind she names wins a near tie`() {
        writeList(listOf(
            Src("Pacifica Beach Hotel", "hotel", 37.62, -122.49, size = 9000.0, areas = listOf("Pacifica")),
            Src("Pacifica State Beach", "beach", 37.62, -122.49, size = 9000.0, areas = listOf("Pacifica")),
        ), path)
        assertEquals("Pacifica State Beach", names("pacifica beach")[0])
    }

    // ---------------------------------------------------------- the status line

    @Test
    fun `an installed list gives its real count`() {
        small()
        val (count, words) = PlaceSearch.status(index())
        assertEquals(ROWS.size - 1, count)
        assertTrue("${ROWS.size - 1} places on this phone" in words, words)
    }

    @Test
    fun `no list says what that means in words she can act on`() {
        val (count, words) = PlaceSearch.status(null)
        assertNull(count)
        assertTrue("pasted coordinates" in words && ".sqlite" !in words, words)
    }

    @Test
    fun `a list whose rows disagree with its own count is damaged`() {
        small()
        DriverManager.getConnection("jdbc:sqlite:$path").use { it.createStatement().execute("DELETE FROM place WHERE id > 2") }
        val (count, words) = PlaceSearch.status(index())
        assertNull(count)
        assertTrue("damaged" in words, words)
    }

    // ---------------------------------------------------------- her seven searches

    /**
     * The seven queries she typed on the phone, against the REAL list, from
     * Oakland. NAVI_PLACES_DB points at it (NVII-DASH/assets/maps/places.sqlite).
     */
    private fun real(): JdbcPlaceIndex {
        val db = System.getenv("NAVI_PLACES_DB")
        assumeTrue(db != null && File(db).isFile, "set NAVI_PLACES_DB to the real places.sqlite to run her searches")
        return index(db!!)
    }

    private fun first(text: String, n: Int = 1): List<PlaceHit> {
        val rows = PlaceSearch.search(real(), text, OAKLAND)
        assertTrue(rows.isNotEmpty(), "'$text' found nothing")
        return rows.take(n)
    }

    @Test
    fun `her searches - japanese garden is lakesides first`() {
        val top = first("japanese garden")[0]
        assertTrue(top.distM!! < 1500, "$top") // Lakeside Park, 0.7 mi - not one 93 km out
    }

    @Test
    fun `her searches - de young museum`() = assertEquals("de Young Museum", first("de young museum")[0].name)

    @Test
    fun `her searches - pacifica beach is the beach not the hotel`() =
        assertEquals("Pacifica State Beach", first("pacifica beach")[0].name)

    @Test
    fun `her searches - pyramid to moses`() = assertEquals("Pyramid to Moses", first("pyramid to moses")[0].name)

    @Test
    fun `her searches - ikea is emeryville first and the store before its car park`() {
        val two = first("ikea", 2)
        assertEquals("ikea", two[0].name.lowercase())
        assertTrue(two[0].distM!! < 4000, "${two[0]}")
        assertTrue(two.none { it.name == "Ikea Parking Lot" }, "$two")
    }

    @Test
    fun `her searches - uss hornet`() = assertEquals("USS Hornet (CV 12)", first("uss hornet")[0].name)

    @Test
    fun `her searches - berkeley stadium finds memorial stadium`() {
        assertTrue("California Memorial Stadium" in first("berkeley stadium", 3).map { it.name })
    }

    @Test
    fun `her searches - chabot is the museum not its car park`() =
        assertEquals("Chabot Space and Science Center", first("chabot space science")[0].name)

    @Test
    fun `her searches - a city name is the city`() {
        val top = first("berkeley")[0]
        assertEquals("Berkeley" to "city", top.name to top.detail)
    }

    @Test
    fun `her searches - the real list counts what it claims`() {
        val (count, _) = PlaceSearch.status(real())
        assertNotNull(count)
        assertTrue(count > 100_000, "$count")
    }

    private companion object {
        val OAKLAND = LatLon(37.8044, -122.2712)
        val ROWS = listOf(
            Src("Chabot Space & Science Center Amphitheater", "theatre", 37.8187, -122.1803),
            Src("Chabot Space and Science Center", "museum", 37.8188, -122.1805),
            // The same museum drawn a second time, as a building outline 30 m away.
            Src("Chabot Space and Science Center", "museum", 37.8190, -122.1807),
            Src("Mt. Diablo Coal Field", "memorial", 37.9500, -121.8500),
            Src("Mount Diablo", "peak", 37.8816, -121.9142),
            Src("Starbucks Coffee", "cafe", 37.8000, -122.2700, alts = listOf("Starbucks")),
            Src("Café Rouge", "restaurant", 37.8700, -122.2700),
            Src("Tilden Regional Park", "protected area", 37.8950, -122.2450),
        )
    }
    @Test
    fun `an envelope address is pulled into its street and its town`() {
        val cases = listOf(
            Triple("4919 Telegraph Ave, Oakland", "telegraph avenue", "oakland"),
            Triple("2201 Broadway, Oakland, CA 94612", "broadway", "oakland"),
            Triple("500 N First St, San Jose", "north first street", "san jose"),
            // The unit and all after it go as a phrase, or a stray "4" is welded on.
            Triple("1234 Grand Ave Apt 4, Alameda", "grand avenue", "alameda"),
            Triple("1500 SE 12th Pl, Renton WA 98055", "southeast 12th place", "renton"),
            Triple("MacArthur Blvd", "macarthur boulevard", ""),
        )
        for ((typed, street, town) in cases) {
            val a = PlaceSearch.parseAddress(typed)
            assertNotNull(a, typed)
            assertEquals(street, a.street, typed)
            assertEquals(town, a.town, typed)
        }
        // Her own capitals survive; uppercasing the first letter alone would
        // leave "Macarthur".
        assertEquals("MacArthur Boulevard", PlaceSearch.parseAddress("MacArthur Blvd")?.label)
        assertEquals("1500 Southeast 12th Place",
            PlaceSearch.parseAddress("1500 SE 12th Pl, Renton WA 98055")?.label)
    }

    @Test
    fun `what is not an address is left to the ordinary place search`() {
        for (typed in listOf("goodwill", "Chabot Space Science Center", "ikea emeryville",
                "37.8044,-122.2712", "geo:37.8,-122.2", "88", "", "   ",
                "https://maps.example/@37.8,-122.2")) {
            assertNull(PlaceSearch.parseAddress(typed), typed)
        }
    }

}
