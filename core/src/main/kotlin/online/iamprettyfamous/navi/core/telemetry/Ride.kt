package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.PyJson
import online.iamprettyfamous.navi.core.geo.LatLon
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

/** What a ride log held: its header, samples, incidents and footer. */
data class RideLog(
    val header: Map<String, Any?>,
    val samples: List<MutableMap<String, Any?>>,
    val incidents: List<Map<String, Any?>>,
    val footer: Map<String, Any?>,
)

/**
 * One recording: append-only on disk, a bounded window in memory.
 *
 * Ported from apps/navi/agedashii_navi/telemetry.py (md5 f3a7163e). Navigation
 * and telemetry are one log (spec rule 2): each GPS fix is a row, its derived
 * channels computed from the fix before, and the sensors and microphone merged
 * into it rather than making rows of their own.
 *
 * CRASH SAFETY. Rows go to a JSON-lines file as they arrive, in append mode, so
 * a battery death or a low-memory kill costs at most [FLUSH_EVERY] rows plus the
 * one held back below - and a torn file is still a readable file.
 *
 * ONE FIX LATE, ON PURPOSE. A row is written when the NEXT fix arrives, or at
 * [stop], not when it is made. The sensors are merged into the newest row by
 * [feedExtra] AFTER [addFix] returns; while rows were written at once, that
 * merge only reached memory and every log on the phone was missing every sensor
 * channel.
 *
 * ALTITUDE. `alt_m`, and the GPX `<ele>` written from it, is what the GPS
 * reported - on Android height above the WGS84 ellipsoid, about 32 m below sea
 * level around the Bay Area. Left as recorded, and the GPX says so.
 *
 * Owned by one thread - the recording service's. Time is passed in as unix
 * seconds; [clock] supplies it only where the Python defaulted to "now".
 */
class Ride(
    val path: String? = null,
    name: String = "",
    val hz: Int = 1,
    /** Which motorcycle, recorded in the header: exhaust_hz and rpm_est mean nothing without it. */
    val machine: Map<String, Any?>? = null,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    var name: String = name.ifEmpty { NAME_FORMAT.format(Instant.ofEpochMilli((clock() * 1000).toLong()).atZone(zone)) }
    var started: Double? = null
        private set
    var stopped: Double? = null
        private set

    private val window = ArrayList<MutableMap<String, Any?>>()

    /** The in-memory window, newest last. Rows are live: the newest one still takes merges. */
    val samples: List<Map<String, Any?>> get() = window

    /** Every sample ever, the ones that left the window included. */
    var count: Int = 0
        private set
    var distanceM: Double = 0.0
        private set
    var maxSpeedMs: Double = 0.0
        private set
    var maxLeanDeg: Double = 0.0
        private set
    var maxGLat: Double = 0.0
        private set
    var maxGLong: Double = 0.0
        private set
    var minGLong: Double = 0.0
        private set
    var climbM: Double = 0.0
        private set
    var descentM: Double = 0.0
        private set

    private val incidentList = ArrayList<Map<String, Any?>>()
    val incidents: List<Map<String, Any?>> get() = incidentList

    // Sensor extremes: they arrive by a different door and may be absent
    // entirely on a phone without the hardware.
    var maxGVert: Double = 0.0
        private set
    var maxGBump: Double = 0.0
        private set
    var maxClimbMps: Double = 0.0
        private set
    var minClimbMps: Double = 0.0
        private set
    var maxRpmEst: Double = 0.0
        private set

    private var prev: MutableMap<String, Any?>? = null

    /** The newest row, held off the disk until it is complete, or null. */
    var pending: MutableMap<String, Any?>? = null
        private set

    private var stream: FileOutputStream? = null
    private var writer: Writer? = null
    private var sinceFlush = 0
    private val errorList = ArrayList<String>()

    /** What went wrong with the log, for the UI. Losing the log never stops the ride. */
    val errors: List<String> get() = errorList.toList()

    val recording: Boolean get() = started != null && stopped == null

    // ---------------------------------------------------------- lifecycle

    fun start(t: Double? = null): Double {
        val at = t ?: clock()
        started = at
        open()
        return at
    }

    private fun open() {
        val p = path ?: return
        try {
            val file = File(p)
            val folder = file.absoluteFile.parentFile
            if (folder != null && !folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
                throw IOException("cannot create ${folder.path}")
            }
            // Append, never truncate: a restart after a crash extends the ride.
            val out = FileOutputStream(file, true)
            stream = out
            writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
            val header = linkedMapOf<String, Any?>(
                "kind" to "header", "name" to name, "started" to started, "hz" to hz,
                "channels" to Channels.ORDER, "app" to "agedashii-navi", "version" to 1,
            )
            if (!machine.isNullOrEmpty()) header.putAll(machine)
            write(header)
        } catch (e: IOException) {
            closeQuietly()
            errorList.add("cannot write the ride log: ${e.message}")
        }
    }

    fun stop(t: Double? = null): Double {
        val at = t ?: clock()
        stopped = at
        // The last row is complete now - nothing more will be merged into it.
        writePending()
        if (writer != null) {
            try {
                write(linkedMapOf("kind" to "footer", "stopped" to at, "samples" to count, "distance_m" to Py.round(distanceM, 1)))
                writer?.flush()
                stream?.fd?.sync()
            } catch (e: IOException) {
                errorList.add("cannot close the ride log: ${e.message}")
            } finally {
                closeQuietly()
            }
        }
        return at
    }

    /** Seconds from start to stop, or to [now] while recording. */
    fun durationS(now: Double? = null): Double {
        val begun = started ?: return 0.0
        val end = stopped?.takeIf { it != 0.0 } ?: now ?: clock()
        return end - begun
    }

    // -------------------------------------------------------------- input

    /**
     * One GPS fix: derive from it, log it, return the row - or null when it is
     * rejected, which is not an error. A fix worse than [maxAccM] under a bridge
     * otherwise adds a kilometre of phantom distance and a corner that never
     * happened; a repeated or out-of-order fix would divide by zero.
     */
    fun addFix(
        lat: Double?,
        lon: Double?,
        t: Double? = null,
        speedMs: Double? = null,
        heading: Double? = null,
        altM: Double? = null,
        accM: Double? = null,
        maxAccM: Double? = 50.0,
    ): Map<String, Any?>? {
        if (lat == null || lon == null) return null
        if (accM != null && maxAccM != null && maxAccM != 0.0 && accM > maxAccM) return null
        val time = t ?: clock()
        val before = prev

        val row = linkedMapOf<String, Any?>(
            "t" to Py.round(time, 2), "lat" to Py.round(lat, 6), "lon" to Py.round(lon, 6),
            "speed_ms" to Py.round(speedMs, 4), "heading" to Py.round(heading, 4),
            "alt_m" to Py.round(altM, 4), "acc_m" to Py.round(accM, 4),
        )

        var stepM = 0.0
        if (before != null) {
            val dt = time - number(before["t"])!!
            if (dt <= 0) return null
            stepM = Physics.haversineM(
                LatLon(number(before["lat"])!!, number(before["lon"])!!),
                LatLon(row["lat"] as Double, row["lon"] as Double),
            )
            // Under two metres while stopped is GPS jitter, not movement.
            if (stepM < 2.0) stepM = 0.0
            val speed = row["speed_ms"] as Double?
            row["g_long"] = Py.round(Physics.rate(speed, number(before["speed_ms"]), dt, Physics.G), 3)
            row["yaw_dps"] = Py.round(Physics.rateDeg(number(before["heading"]), row["heading"] as Double?, dt), 2)
            val yaw = row["yaw_dps"] as Double?
            row["g_lat"] = Py.round(Physics.latG(speed, yaw), 3)
            row["lean_deg"] = Py.round(Physics.leanFrom(speed, yaw), 1)
            row["grade_pct"] = Py.round(Physics.grade(number(before["alt_m"]), row["alt_m"] as Double?, stepM), 1)
        } else {
            for (key in listOf("g_long", "yaw_dps", "g_lat", "lean_deg", "grade_pct")) row[key] = null
        }

        // Before `prev` moves on: the climb accumulator needs the previous altitude.
        trackMaxima(row)
        distanceM += stepM
        row["dist_m"] = Py.round(distanceM, 1)

        prev = row
        count += 1
        window.add(row)
        if (window.size > WINDOW) window.subList(0, window.size - WINDOW).clear()
        // The previous row is complete - its sensors were merged while it was the
        // newest - so it goes to disk now, and this one waits for its own.
        writePending()
        pending = row
        return row
    }

    private fun writePending() {
        val row = pending ?: return
        pending = null
        append(row)
    }

    /**
     * Merge sensor or audio channels into the newest row, rather than making rows
     * of their own: the accelerometer runs far faster than the 1 Hz fix. Unknown
     * keys are kept - they are not CSV columns, but they are in the JSONL, which
     * is the archive, and in the GPX when their names are valid tags. A null
     * value is left out.
     */
    fun feedExtra(values: Map<String, Any?>): Map<String, Any?>? {
        if (window.isEmpty() || values.isEmpty()) return null
        val row = window.last()
        for ((key, value) in values) if (value != null) row[key] = value
        trackSensorMaxima(row)
        return row
    }

    /** The two channels that had names first; [feedExtra] is the real door. */
    fun feedMotion(gVert: Double? = null, tiltDeg: Double? = null): Map<String, Any?>? =
        feedExtra(linkedMapOf("g_vert" to Py.round(gVert, 3), "tilt_deg" to Py.round(tiltDeg, 1)))

    /**
     * A moment worth finding again - a hard brake, a knock, a stop - on its own
     * line, so the interesting six seconds of a four-hour ride need no scan.
     */
    fun noteIncident(what: String, t: Double? = null): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>("t" to Py.round(t ?: clock(), 2), "what" to what)
        incidentList.add(entry)
        append(LinkedHashMap(entry).apply { put("kind", "incident") })
        return entry
    }

    // ------------------------------------------------------------- output

    /**
     * The numbers the Rides list and the end of a ride want. The sensor extremes
     * are null, not zero, when nothing fed them: a zero would be
     * indistinguishable from a phone with no accelerometer.
     */
    fun summary(now: Double? = null): Map<String, Any?> = linkedMapOf(
        "name" to name, "started" to started, "stopped" to stopped, "samples" to count,
        "duration_s" to Py.round(durationS(now), 1),
        "distance_m" to Py.round(distanceM, 1),
        "max_speed_ms" to Py.round(maxSpeedMs, 2),
        "max_lean_deg" to Py.round(maxLeanDeg, 1),
        "max_g_lat" to Py.round(maxGLat, 3),
        "max_g_long" to Py.round(maxGLong, 3),
        "min_g_long" to Py.round(minGLong, 3),
        "climb_m" to Py.round(climbM, 1),
        "descent_m" to Py.round(descentM, 1),
        "incidents" to incidentList.size,
        "max_g_vert" to orNone(Py.round(maxGVert, 3)),
        "max_g_bump" to orNone(Py.round(maxGBump, 3)),
        "max_climb_mps" to orNone(Py.round(maxClimbMps, 2)),
        "min_climb_mps" to orNone(Py.round(minClimbMps, 2)),
        "max_rpm_est" to Py.roundToLong(maxRpmEst).takeIf { it != 0L },
        "path" to path,
    )

    /**
     * Every channel, one row per sample, in [Channels.ORDER]. Flat so it opens
     * in anything; a blank cell is the honest form of a sensor this phone lacks.
     */
    fun toCsv(rows: List<Map<String, Any?>> = samples): String {
        val out = StringBuilder(Channels.ORDER.joinToString(","))
        for (row in rows) {
            out.append('\n')
            out.append(Channels.ORDER.joinToString(",") { key -> row[key]?.let { Py.str(it) } ?: "" })
        }
        return out.append('\n').toString()
    }

    /**
     * A GPX track with every other channel in an extensions block - every one
     * the row carries, not a list: the list of eight this used to name dropped
     * the barometer, the accelerometer, the gyroscope, the compass and the
     * battery from every ride that reached the server.
     */
    fun toGpx(rows: List<Map<String, Any?>> = samples): String {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Agedashii Navi\" " +
            "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
            "xmlns:navi=\"https://alola.lol/navi/1\">\n" +
            "  <metadata><name>${xml(name)}</name><desc>${xml(ELE_NOTE)}</desc></metadata>\n" +
            "  <trk>\n" +
            "    <name>${xml(name)}</name>\n" +
            "    <trkseg>\n"
        val body = rows.map { row ->
            val t = number(row["t"])?.takeIf { it != 0.0 } ?: 0.0
            val time = GPX_TIME.format(Instant.ofEpochSecond(floor(t).toLong()))
            val parts = mutableListOf("      <trkpt lat=\"${Py.str(row["lat"])}\" lon=\"${Py.str(row["lon"])}\">")
            // <ele> BEFORE <time>: GPX 1.1 fixes the order and a strict importer refuses the other.
            row["alt_m"]?.let { parts.add("        <ele>${Py.str(it)}</ele>") }
            parts.add("        <time>$time</time>")
            val keys = extensionKeys(row)
            if (keys.isNotEmpty()) {
                parts.add("        <extensions>")
                for (key in keys) parts.add("          <navi:$key>${xml(row[key])}</navi:$key>")
                parts.add("        </extensions>")
            }
            parts.add("      </trkpt>")
            parts.joinToString("\n")
        }
        return head + body.joinToString("\n") + "\n    </trkseg>\n  </trk>\n</gpx>\n"
    }

    /**
     * Summary, channel dictionary and samples together. The dictionary travels
     * with the data: a column called g_lat means nothing in six months.
     */
    fun toJson(rows: List<Map<String, Any?>> = samples): String {
        val channels = LinkedHashMap<String, Any?>()
        for (c in Channels.ALL) channels[c.name] = linkedMapOf("kind" to c.kind.wire, "unit" to c.unit, "about" to c.about)
        return PyJson.dumps(
            linkedMapOf("summary" to summary(), "channels" to channels, "incidents" to incidentList, "samples" to rows),
            indent = 1,
        )
    }

    // ------------------------------------------------------------ private

    private fun trackMaxima(row: Map<String, Any?>) {
        number(row["speed_ms"])?.let { maxSpeedMs = pyMax(maxSpeedMs, it) }
        number(row["lean_deg"])?.let { maxLeanDeg = pyMax(maxLeanDeg, abs(it)) }
        number(row["g_lat"])?.let { maxGLat = pyMax(maxGLat, abs(it)) }
        number(row["g_long"])?.let {
            maxGLong = pyMax(maxGLong, it)
            minGLong = pyMin(minGLong, it)
        }
        val alt = number(row["alt_m"])
        val prevAlt = number(prev?.get("alt_m"))
        if (alt != null && prevAlt != null) {
            val rise = alt - prevAlt
            // A metre of dead band: GPS altitude wanders a metre or two standing
            // still, and a two-hour cafe stop would otherwise climb hundreds.
            if (rise > 1.0) climbM += rise else if (rise < -1.0) descentM += -rise
        }
    }

    private fun trackSensorMaxima(row: Map<String, Any?>) {
        row["g_vert"]?.let { maxGVert = pyMax(maxGVert, abs(operand(it))) }
        row["g_peak"]?.let { maxGVert = pyMax(maxGVert, abs(operand(it))) }
        row["g_bump"]?.let { maxGBump = pyMax(maxGBump, abs(operand(it))) }
        row["rpm_est"]?.let { maxRpmEst = pyMax(maxRpmEst, abs(operand(it))) }
        row["climb_mps"]?.let {
            val climb = operand(it)
            maxClimbMps = pyMax(maxClimbMps, climb)
            minClimbMps = pyMin(minClimbMps, climb)
        }
    }

    private fun append(obj: Map<String, Any?>) {
        val out = writer ?: return
        try {
            write(obj)
            sinceFlush += 1
            if (sinceFlush >= FLUSH_EVERY) {
                out.flush()
                sinceFlush = 0
            }
        } catch (e: IOException) {
            // Losing the log must not stop the ride. Say so once, keep sampling
            // into memory, and let the UI show the error.
            errorList.add("ride log stopped: ${e.message}")
            closeQuietly()
        }
    }

    private fun write(obj: Map<String, Any?>) {
        writer!!.write(PyJson.compact(obj) + "\n")
    }

    private fun closeQuietly() {
        try {
            writer?.close() ?: stream?.close()
        } catch (_: IOException) {
            // Already gone; nothing more to lose.
        }
        writer = null
        stream = null
    }

    companion object {
        /** Rows between flushes: five seconds of loss at worst at 1 Hz. */
        const val FLUSH_EVERY = 5

        /** Rows the live HUD keeps in memory: ten minutes at 1 Hz. The log keeps everything. */
        const val WINDOW = 600

        /** What the GPX base schema already carries, so never repeated as an extension. */
        val GPX_BASE: Set<String> = setOf("t", "lat", "lon", "alt_m")

        /** What <ele> is, written into every GPX. */
        const val ELE_NOTE = "ele is the altitude the phone's GPS reported: height above the " +
            "WGS84 ellipsoid unless the phone supplied a sea-level figure. " +
            "Around the Bay Area the ellipsoid figure reads about 32 m below " +
            "sea level. Barometric altitude, when the phone has a barometer, " +
            "is navi:alt_baro_m."

        /** An extension tag this writes: ASCII only, which a strict parser - the dashboard's - accepts. */
        private val TAG = Regex("[A-Za-z_][A-Za-z0-9_]*")

        // The minute-resolution default is telemetry.py's, kept so the port
        // matches. CALLERS MUST PASS A NAME WITH SECONDS: the server keys one
        // file per name, and two rides started in the same minute overwrote
        // each other on the dashboard (live.py:219-227).
        private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val GPX_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /**
         * The row's channels for a GPX extensions block: known ones in log order,
         * then anything merged that is not declared yet, sorted so two exports
         * of one ride are identical, if its name is a valid ASCII tag. An absent
         * value is left out, never written empty; a list is left out too.
         */
        fun extensionKeys(row: Map<String, Any?>): List<String> {
            val known = Channels.ORDER.filter { it !in GPX_BASE && scalar(row[it]) }
            val unknown = row.keys.filter { it !in Channels && it !in GPX_BASE && scalar(row[it]) && TAG.matches(it) }.sorted()
            return known + unknown
        }

        /**
         * A JSONL ride log read back. A line that will not parse is skipped - the
         * torn last line after a crash - because a partial file is still worth
         * something. A missing file is an empty log.
         */
        fun readLog(path: String): RideLog {
            val lines = try {
                File(path).readLines(Charsets.UTF_8)
            } catch (_: IOException) {
                return RideLog(emptyMap(), emptyList(), emptyList(), emptyMap())
            }
            var header: Map<String, Any?> = emptyMap()
            var footer: Map<String, Any?> = emptyMap()
            val samples = ArrayList<MutableMap<String, Any?>>()
            val incidents = ArrayList<Map<String, Any?>>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parsed = try {
                    PyJson.loads(line)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val obj = parsed as? MutableMap<String, Any?> ?: continue
                when (obj["kind"]) {
                    "header" -> header = obj
                    "footer" -> footer = obj
                    "incident" -> incidents.add(obj)
                    else -> if ("lat" in obj) samples.add(obj)
                }
            }
            return RideLog(header, samples, incidents, footer)
        }

        /**
         * A Ride rebuilt from its log - the WHOLE ride, not the window. The
         * maxima are replayed through the same code that produced them live, so
         * a finished ride's summary cannot disagree with the one shown at its end.
         */
        fun loadRide(path: String): Ride {
            val log = readLog(path)
            val headerName = log.header["name"]
            val ride = Ride(path = null, name = if (headerName is String && headerName.isNotEmpty()) headerName else "ride")
            ride.started = number(log.header["started"])
            ride.stopped = number(log.footer["stopped"])
            ride.window.addAll(log.samples)
            ride.count = log.samples.size
            ride.incidentList.addAll(log.incidents)
            if (log.samples.isNotEmpty()) {
                ride.distanceM = number(log.samples.last()["dist_m"])?.takeIf { it != 0.0 } ?: 0.0
                for (row in log.samples) {
                    ride.trackMaxima(row)
                    ride.trackSensorMaxima(row)
                    ride.prev = row
                }
            }
            return ride
        }

        /** The ride's name off the first line alone, for a list of forty rides. Null when that line is not a header. */
        fun headerName(path: String): String? {
            val first = try {
                File(path).bufferedReader(Charsets.UTF_8).use { it.readLine() } ?: "{}"
            } catch (_: IOException) {
                return null
            }
            val obj = try {
                PyJson.loads(first) as? Map<*, *>
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            return if (obj["kind"] == "header") obj["name"] as? String else null
        }

        private fun scalar(value: Any?): Boolean = value is Number || value is String || value is Boolean

        /** Python's `_xml`: control characters dropped - not even escaped, they are not XML - then escaped. */
        private fun xml(value: Any?): String {
            val text = Py.str(value)
            val clean = buildString(text.length) { for (c in text) if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c) }
            return clean.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        }

        private fun number(value: Any?): Double? = when (value) {
            null -> null
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            else -> null
        }

        /** abs() and max() take numbers; Python raises TypeError on anything else, and so does this. */
        private fun operand(value: Any): Double =
            number(value) ?: throw IllegalArgumentException("bad operand type for abs(): '${value::class.simpleName}'")

        /** Python's max(a, b): the first unless the second is greater - so max(0.0, -0.0) is 0.0. */
        private fun pyMax(a: Double, b: Double): Double = if (b > a) b else a

        private fun pyMin(a: Double, b: Double): Double = if (b < a) b else a

        private fun orNone(x: Double): Double? = if (x == 0.0) null else x
    }
}
