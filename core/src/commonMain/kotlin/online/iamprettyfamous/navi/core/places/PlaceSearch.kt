package online.iamprettyfamous.navi.core.places

import online.iamprettyfamous.navi.core.geo.Geo
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.grouped
import online.iamprettyfamous.navi.core.nfkd
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One row of the places list, as stored (schema 2; schema 1 has no size, notability or towns). */
data class PlaceRow(
    val id: Long,
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val areaM2: Double = 0.0,
    val notable: Boolean = false,
    val a1: Long? = null,
    val a2: Long? = null,
    /**
     * Its street address: "2235 Telegraph Avenue" from OSM, or "off
     * Telegraph Avenue" when only the street it stands on is known, or null.
     * Her words: three Goodwills that differ only in how far away they are
     * are three coin flips.
     */
    val addr: String? = null,
)

/** A place found: its name, where it is, "kind · town", and how far from her. */
data class PlaceHit(val name: String, val lat: Double, val lon: Double, val detail: String, val distM: Double?)

/**
 * The places list as the search needs it. The app reads it with Android's
 * SQLite, the tests with JDBC; both run [PlaceSql]'s statements, so the two
 * cannot ask different questions.
 */
interface PlaceIndex {
    /** How many places the list says it holds (meta 'count'). */
    fun claimedCount(): Int

    /** How many rows it really holds. */
    fun countedRows(): Int

    /** Schema 2: sizes, notability, kinds and towns are searchable. */
    fun hasAreas(): Boolean

    /**
     * Whether `place` carries the `addr` column. A list built before street
     * addresses were added has none, and the app must open it rather than
     * fail: she keeps the unpacked list until a new MANIFEST replaces it.
     */
    fun hasAddr(): Boolean = false

    /** Place ids with a name (or alternative name) word starting [form]. */
    fun namePrefix(form: String): Set<Long>

    /** Kinds with a word starting [form]. */
    fun kindPrefix(form: String): Set<String>

    /** Town and neighbourhood ids with a word starting [form]. */
    fun areaPrefix(form: String): Set<Long>

    fun rows(ids: List<Long>): List<PlaceRow>

    fun areaNames(aids: Collection<Long>): Map<Long, String>
}

/** The statements, once. `?` twice for a prefix: `t >= form AND t < form + U+FFFF`. */
object PlaceSql {
    const val CLAIMED = "SELECT value FROM meta WHERE key = 'count'"
    const val COUNTED = "SELECT count(*) FROM place"
    const val TABLES = "SELECT name FROM sqlite_master WHERE type = 'table'"
    const val NAME_PREFIX = "SELECT id FROM tok WHERE t >= ? AND t < ?"
    const val KIND_PREFIX = "SELECT kind FROM kind_tok WHERE t >= ? AND t < ?"
    const val AREA_PREFIX = "SELECT aid FROM area_tok WHERE t >= ? AND t < ?"
    const val ROWS_V2 = "SELECT id, name, kind, lat, lon, area_m2, notable, a1, a2, addr FROM place WHERE id IN "
    /** Schema 2 without the address column: the same shape, a null in its place. */
    const val ROWS_V2_NO_ADDR = "SELECT id, name, kind, lat, lon, area_m2, notable, a1, a2, NULL FROM place WHERE id IN "
    /** Does `place` carry `addr`? One row per column of the table. */
    const val COLUMNS = "PRAGMA table_info(place)"
    const val ROWS_V1 = "SELECT id, name, kind, lat, lon, 0, 0, NULL, NULL, NULL FROM place WHERE id IN "
    const val AREAS = "SELECT aid, name FROM area WHERE aid IN "

    /** The upper bound of a prefix range. */
    fun until(form: String) = form + '￿'

    /** `(?,?,...)` for [n] values. */
    fun marks(n: Int) = List(n) { "?" }.joinToString(",", "(", ")")

    /** SQLite's older default limit on bound parameters, so an IN (...) never trips it. */
    const val IN_CHUNK = 900
}

/**
 * Finding a named place with no signal: every named museum, park, cafe, fuel
 * station and peak in the extract, from the list tools/build_places.py makes.
 * Ported from places.py:343-558 (NVII-DASH e481d24) - the query and the
 * ranking are minion 4's, measured against her seven real searches, and are
 * kept, not redesigned.
 *
 * Every word typed must begin a word of the place's name, its kind or its
 * town, and at least one must be in the NAME: "berkeley stadium" finds
 * California Memorial Stadium, and "berkeley" alone does not return every
 * place in Berkeley.
 *
 * RANKING. Cost = how well the name answers the words + how far away it is;
 * lowest first. Distance costs [DIST_WEIGHT] per doubling of (1 + km) and the
 * name score is clamped to [Q_RANGE], whose width (5.5) is less than the
 * distance cost between 1 km and 100 km (8.5) - so 100 km can never beat 1 km
 * for the same words. Within a similar distance the name decides: the beach
 * before the Pacifica Beach HOTEL, IKEA before its car park.
 */
object PlaceSearch {
    const val FILENAME = "places.sqlite"

    /** Words that carry no meaning in a place search ("Chabot Space AND Science"). */
    val STOP_WORDS = setOf("and", "the", "of", "at", "on", "in")

    /** A typed word also matches its long or short form: "mt diablo" is OSM's "Mount Diablo". */
    val SAME_WORD = mapOf(
        "mt" to listOf("mount"), "mount" to listOf("mt"), "ft" to listOf("fort"), "fort" to listOf("ft"),
        "pt" to listOf("point"), "point" to listOf("pt"),
    )
    private val CANON = mapOf("mt" to "mount", "ft" to "fort", "pt" to "point")

    /** One letter prefixes a quarter of the list; two letters are a search. */
    const val MIN_WORD = 2

    const val DIST_WEIGHT = 1.5
    val Q_RANGE = -2.5 to 3.0
    const val NOT_EXACT = 1.0
    const val KIND_HIT = 1.5
    const val AREA_WORD = 1.0
    const val KIND_WORD = 0.5
    const val EXTRA_WORD = 0.1
    const val ALT_WORD = 0.75
    const val SIZE = 0.25
    const val NOTABLE = 0.5

    /** A town is one point but stands for the whole town: "berkeley" is Berkeley before Berkeley Yeast. */
    val TOWN_SIZE = mapOf("city" to 1e8, "town" to 1e7, "village" to 1e6)

    /** Parts of a place rather than places: below the place they serve, and no credit for size. */
    val PART_OF = setOf("parking", "parking space", "taxi", "building", "bicycle parking", "motorcycle parking")
    const val PART_OF_COST = 1.0

    private val MARKS = Regex("\\p{Mn}+")
    private val SPLIT = Regex("[^0-9a-z]+")

    /**
     * Lowercase words, accents stripped: "Café & Bar" -> [cafe, bar]. The same
     * split as the builder's norm_tokens, or what is stored and what is
     * searched would not meet.
     */
    fun normTokens(text: String?): List<String> {
        val bare = MARKS.replace(nfkd(text ?: ""), "")
        return bare.lowercase().split(SPLIT).filter { it.isNotEmpty() }
    }


    /**
     * What she wrote on an envelope, pulled apart. Her report, 2026-10-12: "I
     * TRIED LITERALLY PUTTING IN AN ADDRESS AND THE APP WOULDN'T TAKE ME THERE
     * -- I COULDN'T FIGURE OUT WHICH GOODWILL WAS THE ONE I WANTED SO I PUT IN
     * THE ADDRESS AND IT WOULDNT' LET ME DO THAT".
     *
     * [number] is remembered and cannot be found: the places list holds
     * streets, not doors. [label] is for showing; [street] and [town] are what
     * the search is given, spelled the way OSM spells them.
     */
    data class Address(val number: String, val street: String, val town: String, val label: String)

    /** Envelope spelling to OSM spelling: Ave -> avenue, N -> north. */
    val SUFFIX = mapOf(
        "ave" to "avenue", "av" to "avenue", "st" to "street", "str" to "street",
        "rd" to "road", "blvd" to "boulevard", "blv" to "boulevard", "dr" to "drive",
        "ln" to "lane", "ct" to "court", "pl" to "place", "plz" to "plaza",
        "hwy" to "highway", "pkwy" to "parkway", "pky" to "parkway", "ter" to "terrace",
        "trl" to "trail", "cir" to "circle", "sq" to "square", "expy" to "expressway",
        "wy" to "way", "aly" to "alley", "byp" to "bypass", "cres" to "crescent",
        "xing" to "crossing", "mnr" to "manor", "gln" to "glen", "hts" to "heights",
    )
    private val DIRECTION = mapOf(
        "n" to "north", "s" to "south", "e" to "east", "w" to "west",
        "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
    )

    /** The state, the ZIP, the country: after the street, and not a town. */
    private val NOT_A_TOWN = Regex("^(?:[a-z]{2}|\\d{5}(?:-\\d{4})?|usa|us|united states)$")

    /**
     * The unit AND EVERYTHING AFTER IT. Cut as a phrase: dropping only "Apt"
     * leaves a stray "4" welded to the end of the street name, and nothing
     * matches "grand avenue 4".
     */
    private val UNIT = Regex(
        "[\\s,]+(?:apt|apartment|unit|ste|suite|bldg|building|fl|floor|rm|room|no\\.?|#)\\b.*$",
        RegexOption.IGNORE_CASE)
    private val HOUSE = Regex("^(\\d+[a-z]?(?:\\s*-\\s*\\d+[a-z]?)?)\\s+(?=\\S)", RegexOption.IGNORE_CASE)

    private fun expand(words: List<String>): List<String> = words.mapIndexed { i, w ->
        when {
            i == 0 && words.size > 1 && w in DIRECTION -> DIRECTION.getValue(w)
            i == words.size - 1 && w in SUFFIX -> SUFFIX.getValue(w)
            else -> w
        }
    }

    /**
     * [text] as an address, or null when it is not one - and then the ordinary
     * place search has it, so "goodwill" and "Chabot Space Science Center" are
     * untouched. Address-shaped means a house number in front of a name, or a
     * name that ends in a street suffix.
     */
    fun parseAddress(text: String?): Address? {
        val raw = (text ?: "").trim()
        if (raw.length < 4) return null
        val low = raw.lowercase()
        if (low.startsWith("geo:") || low.startsWith("http")) return null
        val chunks = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.isEmpty()) return null

        val head = chunks[0]
        val found = HOUSE.find(head)
        val number = found?.groupValues?.get(1)?.replace(" ", "") ?: ""
        val streetRaw = UNIT.replace(if (found != null) head.substring(found.value.length) else head, "")
        val words = normTokens(streetRaw)
        if (words.isEmpty() || words.all { w -> w.all { it.isDigit() } }) return null
        val last = words.last()
        val suffixed = last in SUFFIX || last in SUFFIX.values
        if (number.isEmpty() && !suffixed) return null
        val street = expand(words).joinToString(" ")

        var town = ""
        for (c in chunks.drop(1)) {
            val tokens = normTokens(c).filter { !NOT_A_TOWN.matches(it) }
            if (tokens.isNotEmpty()) {
                town = tokens.joinToString(" ")
                break
            }
        }
        // Shown with her own capitals kept: title() alone says "Macarthur".
        val typed = streetRaw.split(Regex("[^0-9A-Za-z]+")).filter { it.isNotEmpty() }
        val longer = expand(typed.map { it.lowercase() })
        val shown = typed.zip(longer).joinToString(" ") { (short, long) ->
            when {
                long != short.lowercase() -> long.replaceFirstChar { it.uppercase() }
                short.all { it.isLowerCase() || !it.isLetter() } && !short[0].isDigit() ->
                    short.replaceFirstChar { it.uppercase() }
                else -> short
            }
        }
        return Address(number, street, town, listOf(number, shown).filter { it.isNotEmpty() }.joinToString(" "))
    }

    /** The places whose name answers [text], best first, at most [limit]. */
    fun search(index: PlaceIndex, text: String, near: LatLon?, limit: Int = 12): List<PlaceHit> {
        // An ADDRESS is asked as its street and its town, which the list does
        // hold (kind "street", one row per street AND town). The house number is
        // carried back onto the row and said to be an estimate: the list has
        // street geometry and no doors.
        val posted = parseAddress(text)
        val asked = if (posted != null) listOf(posted.street, posted.town)
            .filter { it.isNotEmpty() }.joinToString(" ") else text
        val words = normTokens(asked).filter { it !in STOP_WORDS && it.length >= MIN_WORD }.distinct()
        if (words.isEmpty()) return emptyList()
        val v2 = index.hasAreas()
        val byName = words.map { w -> forms(w).flatMapTo(HashSet()) { index.namePrefix(it) } }
        val kindsFor = words.map { w -> if (v2) forms(w).flatMapTo(HashSet()) { index.kindPrefix(it) } else emptySet() }
        val areasFor = words.map { w -> if (v2) forms(w).flatMapTo(HashSet()) { index.areaPrefix(it) } else emptySet() }
        // At least one word is in the name.
        val pool = byName.flatMapTo(HashSet()) { it }.sorted()
        val rows = index.rows(pool).sortedBy { it.id }

        class Kept(val dcost: Double, val dist: Double?, val row: PlaceRow, val srcs: List<String>)
        val kept = ArrayList<Kept>()
        for (row in rows) {
            val srcs = ArrayList<String>()
            for (i in words.indices) {
                srcs += when {
                    row.id in byName[i] -> "name"
                    row.kind in kindsFor[i] -> "kind"
                    (row.a1 != null && row.a1 in areasFor[i]) || (row.a2 != null && row.a2 in areasFor[i]) -> "area"
                    else -> break
                }
            }
            if (srcs.size < words.size) continue
            val dist = near?.let { Geo.haversineM(it, LatLon(row.lat, row.lon)) }
            val dcost = if (dist != null) DIST_WEIGHT * log2(1.0 + dist / 1000.0) else 0.0
            kept += Kept(dcost, dist, row, srcs)
        }
        kept.sortBy { it.dcost } // stable, as Python's sort

        // Compare on canonical words, so "mt diablo" scores "Mount Diablo" as exact.
        val typed = words.map { CANON[it] ?: it }
        val want = typed.joinToString(" ")
        val kindWords = HashMap<String, List<String>>()
        class Scored(val cost: Double, val name: String, val dist: Double?, val row: PlaceRow)
        val order = compareBy<Scored>({ it.cost }, { it.name }, { it.dist ?: -1.0 })
        val best = ArrayList<Scored>()
        for (k in kept) {
            // Nearest first: once distance alone costs more than the last kept
            // result could, nothing further can make the list.
            if (best.size >= limit && Q_RANGE.first + k.dcost > best[limit - 1].cost) break
            val row = k.row
            val have = normTokens(row.name).filter { it !in STOP_WORDS }.map { CANON[it] ?: it }
            var q = if (have.joinToString(" ") == want) 0.0 else NOT_EXACT
            val kw = kindWords.getOrPut(row.kind) { normTokens(row.kind) }
            if (typed.any { w -> kw.any { it.startsWith(w) } }) q -= KIND_HIT
            for ((w, src) in typed.zip(k.srcs)) {
                q += when {
                    src == "area" -> AREA_WORD
                    src == "kind" -> KIND_WORD
                    // In an alternative name only, not the one shown.
                    have.none { it.startsWith(w) } -> ALT_WORD
                    else -> 0.0
                }
            }
            q += EXTRA_WORD * have.count { h -> typed.none { h.startsWith(it) } }
            q += if (row.kind in PART_OF) PART_OF_COST
            else -SIZE * log10(1.0 + max(row.areaM2, TOWN_SIZE[row.kind] ?: 0.0))
            if (row.notable) q -= NOTABLE
            q = min(max(q, Q_RANGE.first), Q_RANGE.second)
            val s = Scored(q + k.dcost, row.name, k.dist, row)
            // bisect.insort: after any equal entry.
            var at = best.binarySearch(s, order).let { if (it < 0) -it - 1 else it + 1 }
            while (at < best.size && order.compare(best[at], s) == 0) at++
            best.add(at, s)
            if (best.size > limit) best.removeAt(best.size - 1)
        }
        val towns = if (v2) index.areaNames(best.mapNotNull { it.row.a1 }.distinct()) else emptyMap()
        return best.map { s ->
            // The ADDRESS is what tells two shops of one name apart, so it is
            // on the row. A real one ("2235 Telegraph Avenue") stands in for the
            // town, which it already implies and which would push the line past
            // the width of a phone; a fallback ("off Telegraph Avenue") does not,
            // so that keeps the town beside it.
            val addr = s.row.addr
            val place = if (addr != null && !addr.startsWith("off ")) addr
                else listOfNotNull(addr, s.row.a1?.let { towns[it] }).joinToString(" · ")
            val detail = listOfNotNull(s.row.kind.ifEmpty { null }, place.ifEmpty { null })
                .joinToString(" · ")
            // Her number back on the row, and the row saying what it is not. The
            // street is where the list can take her; the door is not in it.
            if (posted != null && s.row.kind == "street") {
                val named = listOf(posted.number, s.name).filter { it.isNotEmpty() }.joinToString(" ")
                PlaceHit(named, s.row.lat, s.row.lon,
                    if (posted.number.isEmpty()) detail else "$detail · no house numbers offline", s.dist)
            } else {
                PlaceHit(s.name, s.row.lat, s.row.lon, detail, s.dist)
            }
        }
    }

    /**
     * (count, one line for the screen): whether the list is usable, said
     * before any search so "not installed" never passes for "no results".
     * The count is COUNTED and must agree with the list's own claim.
     */
    fun status(index: PlaceIndex?): Pair<Int?, String> {
        if (index == null) {
            return null to "Places search is not on this phone - without signal only map points and " +
                "pasted coordinates can be found. Load the map data (☰ Settings) to add it."
        }
        val (rows, claimed) = try {
            index.countedRows() to index.claimedCount()
        } catch (e: Exception) {
            return null to "The places list on this phone could not be read - load the map data again."
        }
        if (rows == 0 || rows != claimed) {
            return null to "The places list on this phone is damaged ($rows rows, it says $claimed) - load the map data again."
        }
        return rows to "${grouped(rows.toLong())} places on this phone, searchable without signal"
    }

    private fun forms(word: String): List<String> = listOf(word) + (SAME_WORD[word] ?: emptyList())

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
