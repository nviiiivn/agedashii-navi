package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.PyJson
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole rides through Kotlin's Ride against what the Python Ride produced for
 * the same calls (tools/golden_telemetry.py, telemetry.py md5 f3a7163e): every
 * returned row, the window, the summary, the CSV, GPX and JSON exports, the
 * JSONL file byte for byte, and what readLog, loadRide and headerName make of it.
 * Byte-for-byte matters: the server, Moto and the dashboard all read these files,
 * and a log written by the port must be the log the Python would have written.
 */
class RideGoldenTest {
    private val golden = Golden.load("telemetry.json")

    @Test
    fun `the constants are the Python's`() {
        assertEquals(golden.getValue("window").jsonPrimitive.int, Ride.WINDOW)
        assertEquals(golden.getValue("flush_every").jsonPrimitive.int, Ride.FLUSH_EVERY)
        assertEquals(golden.str("ele_note"), Ride.ELE_NOTE)
    }

    @Test
    fun `a short ride with everything awkward in it`() = replay(golden.arr("scenarios")[0].jsonObject)

    @Test
    fun `a ride longer than the window`() = replay(golden.arr("scenarios")[1].jsonObject)

    private fun replay(scenario: JsonObject) {
        val dir = scratchDir()
        try {
            val machine = PyJson.fromElement(golden.getValue("machine")) as Map<String, Any?>
            val ride = Ride(path = File(dir, "ride.jsonl").path, name = scenario.str("name"), hz = 1, machine = machine)
            val returns = mutableListOf<String>()
            for (element in scenario.arr("ops")) {
                val op = element.jsonArray[0].jsonPrimitive.content
                val args = PyJson.fromElement(element.jsonArray[1]) as Map<String, Any?>
                fun d(key: String) = (args[key] as Number?)?.toDouble()
                val got: Any? = when (op) {
                    "start" -> ride.start(d("when"))
                    "stop" -> ride.stop(d("when"))
                    "add_fix" -> ride.addFix(
                        lat = d("lat"), lon = d("lon"), t = d("t"), speedMs = d("speed_ms"),
                        heading = d("heading"), altM = d("alt_m"), accM = d("acc_m"),
                    )
                    "feed_extra" -> ride.feedExtra(args["values"] as Map<String, Any?>)
                    "feed_motion" -> ride.feedMotion(gVert = d("g_vert"), tiltDeg = d("tilt_deg"))
                    "note_incident" -> ride.noteIncident(args["what"] as String, t = d("t"))
                    else -> error("unknown op $op")
                }
                // Encoded NOW: a returned row is live and later calls change it.
                returns.add(PyJson.dumps(got))
            }
            val name = scenario.str("name")
            val folder = dir.path
            fun same(key: String, actual: String) {
                val text = actual.replace(folder, "<PATH>")
                val want = scenario.getValue(key)
                if (want is JsonObject) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                    assertEquals(want.getValue("length").jsonPrimitive.int, text.length, "$name $key length")
                    assertEquals(want.str("sha256"), digest, "$name $key")
                } else {
                    assertEquals(want.jsonPrimitive.content, text, "$name $key")
                }
            }

            assertEquals(scenario.getValue("count").jsonPrimitive.int, ride.count, "$name count")
            assertEquals(scenario.getValue("window").jsonPrimitive.int, ride.samples.size, "$name window")
            val wantReturns = scenario.getValue("returns")
            if (wantReturns !is JsonNull) {
                assertEquals(wantReturns.jsonArray.map { PyJson.dumps(PyJson.fromElement(it)) }, returns, "$name returns")
                assertEquals(PyJson.dumps(PyJson.fromElement(scenario.getValue("samples"))), PyJson.dumps(ride.samples), "$name samples")
            }
            same("summary", PyJson.dumps(ride.summary()))
            same("csv", ride.toCsv())
            same("gpx", ride.toGpx())
            same("json", ride.toJson())
            same("log", File(ride.path!!).readText())
            val log = Ride.readLog(ride.path!!)
            same("read_log", PyJson.dumps(listOf(log.header, log.samples, log.incidents, log.footer)))
            val loaded = Ride.loadRide(ride.path!!)
            same("loaded_summary", PyJson.dumps(loaded.summary()))
            same("loaded_gpx", loaded.toGpx())
            same("loaded_csv", loaded.toCsv())
            assertEquals(scenario.getValue("header_name").let { if (it is JsonNull) null else it.jsonPrimitive.content }, Ride.headerName(ride.path!!))
            assertTrue(ride.errors.isEmpty(), "${ride.errors}")
            assertEquals(0, scenario.arr("errors").size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
