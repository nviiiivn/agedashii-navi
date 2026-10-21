package online.iamprettyfamous.navi.core.places

import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.pythonRegex

/** A destination and how it was given: "typed", "map link", "shared pin" or "gpx". */
data class Place(val lat: Double, val lon: Double, val label: String)

/** What a GPX file held: its first name, and its track, route or waypoints. */
data class Gpx(val name: String, val points: List<LatLon>)

/**
 * Where to ride to, from text. The offline half of places.py; the street search
 * reads the router's graph and the POI search needs signal, so neither is here.
 *
 * Ported from apps/navi/agedashii_navi/places.py:57-133 and 291-319. Both
 * functions are total: nonsense in is null or an empty list out, never an
 * exception. Regexes are the Python's, compiled with Unicode classes so `\d` and
 * `\s` mean what they meant there.
 */
object Places {
    /** A decimal degree pair, with an optional sign and either separator. places.py:58. */
    private val PAIR: Regex = pythonRegex("""(-?\d{1,3}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)""")

    /** Degrees/minutes/seconds with a hemisphere letter. places.py:60-61. */
    private val DMS: Regex = pythonRegex(
        """(\d{1,3})[^\d]+(\d{1,2})[^\d]+([\d.]+)[^\dNSEW]*([NSEW])""",
        ignoreCase = true,
    )

    /**
     * places.py:293-300. EITHER ORDER: XML attribute order carries no meaning and
     * plenty of tools write lon first. This once demanded lat first and returned
     * an empty list for such a file - no error, just an import that did nothing.
     * Found by this port; fixed in the Python first (places.py 5baa7e78).
     */
    private val GPX_TAG: Regex = pythonRegex("""<(?:trkpt|rtept|wpt)\b([^>]*?)/?>""", ignoreCase = true, dotAll = true)
    private val GPX_LAT: Regex = pythonRegex("""\blat\s*=\s*["'](-?[\d.]+)["']""", ignoreCase = true)
    private val GPX_LON: Regex = pythonRegex("""\blon\s*=\s*["'](-?[\d.]+)["']""", ignoreCase = true)
    private val GPX_NAME: Regex = pythonRegex("""<name>\s*(.*?)\s*</name>""", ignoreCase = true, dotAll = true)

    /** The query keys a maps link may carry its pair in, in the order tried. */
    private val QUERY_KEYS = listOf("q", "query", "daddr", "destination", "ll")

    /**
     * (lat, lon, label) from whatever she pasted, or null. The shapes are tried
     * in order of how unambiguous they are, so a maps URL with numbers elsewhere
     * in its query string is not read as a bare pair from the wrong part.
     */
    fun parsePoint(text: String?): Place? {
        if (text.isNullOrEmpty()) return null
        val raw = text.trim()

        // geo:37.8044,-122.2712 - what an Android share sheet hands over.
        if (raw.lowercase().startsWith("geo:")) {
            val found = PAIR.find(raw.substring(4))
            if (found != null) return checked(found.groupValues[1], found.groupValues[2], "shared pin")
        }

        if ("http" in raw.lowercase()) {
            // A maps URL: the authoritative pair is the one after the @.
            val at = raw.indexOf('@')
            if (at >= 0) {
                val found = PAIR.find(raw.substring(at + 1))
                if (found != null) return checked(found.groupValues[1], found.groupValues[2], "map link")
            }
            // ?q=lat,lon and its aliases, the other maps-link shape.
            val query = queryOf(raw)
            for (key in QUERY_KEYS) {
                for (candidate in query[key].orEmpty()) {
                    val found = PAIR.find(candidate)
                    if (found != null) return checked(found.groupValues[1], found.groupValues[2], "map link")
                }
            }
        }

        // Degrees, minutes, seconds - two of them, in either order.
        val dms = findAll(DMS, raw)
        if (dms.size >= 2) {
            val first = dmsValue(dms[0])
            val second = dmsValue(dms[1])
            if (first != null && second != null) {
                // A leading E or W means the longitude was written first.
                return if (dms[0][3].uppercase() in setOf("E", "W")) checked(second, first, "typed") else checked(first, second, "typed")
            }
        }

        // A bare pair, last, because it matches inside almost anything.
        val whole = PAIR.matchEntire(raw)
        if (whole != null) return checked(whole.groupValues[1], whole.groupValues[2], "typed")
        val found = PAIR.find(raw)
        if (found != null) return checked(found.groupValues[1], found.groupValues[2], "typed")
        return null
    }

    /**
     * Name and points from GPX text. Forgiving by design: trkpt wins over rtept
     * wins over wpt, because a recorded track with a few waypoints is a ride and
     * the waypoints are notes on it; and a file that is not GPX is an empty list,
     * so the caller says "no points in that file" rather than showing a stack trace.
     */
    fun parseGpx(text: String?): Gpx {
        if (text.isNullOrEmpty()) return Gpx("", emptyList())
        val byKind = linkedMapOf("trkpt" to mutableListOf<LatLon>(), "rtept" to mutableListOf(), "wpt" to mutableListOf())
        for (tag in GPX_TAG.findAll(text)) {
            // `match.group(0)[1:].split(None, 1)[0].rstrip("/>").lower()`: a
            // prefixed name such as trkpt:x is not a point.
            val afterBracket = tag.value.substring(1)
            val kind = afterBracket.takeWhile { !it.isWhitespace() }.trimEnd('/', '>').lowercase()
            val bucket = byKind[kind] ?: continue
            val attrs = tag.groupValues[1]
            // A tag missing either is skipped, never guessed.
            val lat = GPX_LAT.find(attrs) ?: continue
            val lon = GPX_LON.find(attrs) ?: continue
            val place = checked(lat.groupValues[1], lon.groupValues[1], "gpx") ?: continue
            bucket.add(LatLon(place.lat, place.lon))
        }
        val chosen = byKind.getValue("trkpt").ifEmpty { byKind.getValue("rtept").ifEmpty { byKind.getValue("wpt") } }
        val name = (GPX_NAME.find(text)?.groupValues?.get(1) ?: "").trim()
        return Gpx(name, chosen.toList())
    }

    private fun findAll(regex: Regex, text: String): List<List<String>> =
        regex.findAll(text).map { m -> (1 until m.groups.size).map { m.groups[it]?.value ?: "" } }.toList()

    private fun dmsValue(parts: List<String>): Double? {
        val (deg, minutes, secs, hemisphere) = parts
        val value = (number(deg) ?: return null) + (number(minutes) ?: return null) / 60.0 + (number(secs) ?: return null) / 3600.0
        return if (hemisphere.uppercase() in setOf("S", "W")) -value else value
    }

    private fun checked(lat: String, lon: String, label: String): Place? {
        return checked(number(lat) ?: return null, number(lon) ?: return null, label)
    }

    /** Reject a pair that is not on Earth rather than routing to nowhere. */
    private fun checked(lat: Double, lon: Double, label: String): Place? {
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Place(lat, lon, label)
    }

    /** Python's float(): any Unicode decimal digit counts, as `\d` matched it. */
    private fun number(text: String): Double? {
        val ascii = buildString(text.length) {
            for (c in text) append(if (c.isDigit()) '0' + c.digitToInt() else c)
        }
        return ascii.toDoubleOrNull()
    }

    /**
     * `parse_qs(urlparse(raw).query)`: the part after the first '?' and before
     * any '#', split on '&', blank values dropped, '+' and %XX decoded.
     */
    private fun queryOf(raw: String): Map<String, List<String>> {
        val url = raw.trimStart { it <= ' ' }.replace("\t", "").replace("\r", "").replace("\n", "")
        val beforeFragment = url.substringBefore('#')
        val mark = beforeFragment.indexOf('?')
        if (mark < 0) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (field in beforeFragment.substring(mark + 1).split('&')) {
            if (field.isEmpty()) continue
            val eq = field.indexOf('=')
            if (eq < 0) continue
            val value = field.substring(eq + 1)
            if (value.isEmpty()) continue
            out.getOrPut(unquotePlus(field.substring(0, eq))) { mutableListOf() }.add(unquotePlus(value))
        }
        return out
    }

    /** `unquote_plus`: '+' is a space, %XX a UTF-8 byte, and a bad escape stays as written. */
    private fun unquotePlus(text: String): String {
        val source = text.replace('+', ' ')
        if ('%' !in source) return source
        val out = StringBuilder()
        val bytes = okio.Buffer()
        fun flush() {
            if (bytes.size > 0) out.append(bytes.readUtf8())
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '%') {
                val hex = source.substring(i + 1, minOf(i + 3, source.length))
                if (hex.length == 2 && hex.all { it.isAsciiHex() }) {
                    bytes.writeByte(hex.toInt(16))
                    i += 3
                    continue
                }
            }
            if (c.code < 0x80) {
                bytes.writeByte(c.code)
            } else {
                flush()
                out.append(c)
            }
            i += 1
        }
        flush()
        return out.toString()
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
