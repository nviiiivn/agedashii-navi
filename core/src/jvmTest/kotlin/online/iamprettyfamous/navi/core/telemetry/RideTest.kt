package online.iamprettyfamous.navi.core.telemetry

import online.iamprettyfamous.navi.core.PyJson
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A scratch folder inside the build directory, removed after each test. */
fun scratchDir(): File {
    val base = Path.of("build", "tmp", "ride-tests")
    Files.createDirectories(base)
    return Files.createTempDirectory(base, "ride").toFile()
}

/** Ported from apps/navi/tests/test_core.py:77-140 (RideRecording). */
class RideRecordingTest {
    private lateinit var ride: Ride

    @BeforeTest
    fun setUp() {
        ride = Ride(name = "test")
        ride.start(1000.0)
    }

    @Test
    fun `the first fix has no derived channels`() {
        val row = ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 0.0)
        assertNotNull(row)
        for (key in listOf("g_long", "yaw_dps", "g_lat", "lean_deg", "grade_pct")) {
            assertTrue(key in row, key)
            assertNull(row[key], key)
        }
    }

    @Test
    fun `derived channels come out of two fixes`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 10.0, heading = 0.0, altM = 100.0)
        val row = ride.addFix(lat = 37.801, lon = -122.27, t = 1001.0, speedMs = 12.0, heading = 10.0, altM = 105.0)!!
        // 2 m/s in 1 s is 2 / 9.807 g.
        assertEquals(2.0 / Physics.G, row["g_long"] as Double, 0.0005)
        assertEquals(10.0, row["yaw_dps"] as Double, 0.005)
        assertTrue(row["lean_deg"] as Double > 0.0)
        assertTrue(row["dist_m"] as Double > 100.0)
    }

    @Test
    fun `a heading across north is a small turn in the log`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 20.0, heading = 359.0)
        val row = ride.addFix(lat = 37.801, lon = -122.27, t = 1001.0, speedMs = 20.0, heading = 1.0)!!
        assertEquals(2.0, row["yaw_dps"])
        assertTrue((row["lean_deg"] as Double) < 5.0, "an 89-degree lean every time the bike points north")
    }

    @Test
    fun `a wild fix is dropped rather than logged`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, accM = 5.0)
        assertNull(ride.addFix(lat = 37.90, lon = -122.27, t = 1001.0, accM = 120.0))
        assertEquals(1, ride.count)
    }

    @Test
    fun `a repeated timestamp is dropped rather than dividing by zero`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 5.0)
        assertNull(ride.addFix(lat = 37.801, lon = -122.27, t = 1000.0, speedMs = 5.0))
    }

    @Test
    fun `jitter while stopped adds no distance`() {
        ride.addFix(lat = 37.800000, lon = -122.270000, t = 1000.0)
        // ~1.1 m north - under the 2 m floor.
        ride.addFix(lat = 37.800010, lon = -122.270000, t = 1001.0)
        assertEquals(0.0, ride.distanceM)
    }

    @Test
    fun `altitude wander does not accumulate climb`() {
        listOf(100.0, 100.5, 100.2, 100.6, 100.1).forEachIndexed { i, alt ->
            ride.addFix(lat = 37.8 + i * 0.001, lon = -122.27, t = 1000.0 + i, altM = alt)
        }
        assertEquals(0.0, ride.climbM)
        assertEquals(0.0, ride.descentM)
    }

    @Test
    fun `a real climb does accumulate`() {
        listOf(100.0, 110.0, 125.0).forEachIndexed { i, alt ->
            ride.addFix(lat = 37.8 + i * 0.001, lon = -122.27, t = 1000.0 + i, altM = alt)
        }
        assertEquals(25.0, ride.climbM, 0.05)
    }

    @Test
    fun `the window is bounded but the count is not`() {
        for (i in 0 until Ride.WINDOW + 50) {
            ride.addFix(lat = 37.8 + i * 0.0002, lon = -122.27, t = 1000.0 + i, speedMs = 20.0, heading = 90.0)
        }
        assertEquals(Ride.WINDOW, ride.samples.size)
        assertEquals(Ride.WINDOW + 50, ride.count)
    }

    @Test
    fun `motion attaches to the latest sample not a new one`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0)
        ride.feedMotion(gVert = 1.8, tiltDeg = 12.0)
        assertEquals(1, ride.count)
        assertEquals(1.8, ride.samples.last()["g_vert"])
    }

    @Test
    fun `lean is not logged below walking pace`() {
        ride.addFix(lat = 37.80, lon = -122.27, t = 1000.0, speedMs = 2.0, heading = 0.0)
        val row = ride.addFix(lat = 37.8001, lon = -122.27, t = 1001.0, speedMs = 2.0, heading = 40.0)!!
        assertNull(row["lean_deg"])
        assertEquals(40.0, row["yaw_dps"])
    }
}

/** Ported from apps/navi/tests/test_core.py:143-198 (Exports). */
class ExportsTest {
    private lateinit var ride: Ride

    @BeforeTest
    fun setUp() {
        ride = Ride(name = "export test")
        ride.start(1700000000.0)
        for (i in 0 until 4) {
            ride.addFix(lat = 37.80 + i * 0.001, lon = -122.27, t = 1700000000.0 + i, speedMs = 10.0 + i, heading = i * 5.0, altM = 100.0 + i)
        }
    }

    @Test
    fun `csv has every channel and blanks for the absent ones`() {
        val lines = ride.toCsv().lines()
        assertEquals(Channels.ORDER, lines[0].split(","))
        // rpm_est has no reading, so its column exists and is empty.
        val col = Channels.ORDER.indexOf("rpm_est")
        assertEquals("", lines[1].split(",")[col])
    }

    @Test
    fun `gpx is timestamped in utc and carries the derived channels`() {
        val gpx = ride.toGpx()
        assertTrue("<gpx" in gpx)
        assertTrue("2023-11-14T22:13:20Z" in gpx)
        assertTrue("<navi:lean_deg>" in gpx.replace("\n", ""))
    }

    @Test
    fun `json carries the channel dictionary with the data`() {
        val blob = PyJson.loads(ride.toJson()) as Map<*, *>
        val channels = blob["channels"] as Map<*, *>
        assertEquals("derived", (channels["g_lat"] as Map<*, *>)["kind"])
        // rpm_est is "estimated": the microphone is real but the number is a
        // peak-pick in a noise band and is allowed to come back empty.
        assertEquals("estimated", (channels["rpm_est"] as Map<*, *>)["kind"])
        assertEquals("measured", (channels["g_vert"] as Map<*, *>)["kind"])
        assertEquals(4, (blob["samples"] as List<*>).size)
    }

    @Test
    fun `sensor channels merge into the latest sample`() {
        ride.feedExtra(linkedMapOf("g_vert" to 1.8, "g_bump" to 0.9, "tilt_deg" to -12.0, "climb_mps" to 1.4, "rpm_est" to 4200L))
        assertEquals(1.8, ride.samples.last()["g_vert"])
        assertEquals(4, ride.count, "feedExtra made a new sample")
        val summary = ride.summary()
        assertEquals(0.9, summary["max_g_bump"])
        assertEquals(1.4, summary["max_climb_mps"])
        assertEquals(4200L, summary["max_rpm_est"])
    }

    @Test
    fun `a ride with no sensors reports none not zero`() {
        // A zero maximum is indistinguishable from a phone with no accelerometer.
        assertNull(ride.summary()["max_g_bump"])
        assertNull(ride.summary()["max_rpm_est"])
    }

    @Test
    fun `a name with an ampersand does not break the gpx`() {
        ride.name = "Grizzly & Wildcat"
        assertTrue("Grizzly &amp; Wildcat" in ride.toGpx())
    }
}

/** Ported from apps/navi/tests/test_core.py:201-230 (LogOnDisk). */
class LogOnDiskTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = scratchDir()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a ride survives being torn off mid line`() {
        val path = File(dir, "ride.jsonl").path
        val ride = Ride(path = path, name = "torn")
        ride.start(1000.0)
        for (i in 0 until 20) ride.addFix(lat = 37.8 + i * 0.001, lon = -122.27, t = 1000.0 + i, speedMs = 15.0, heading = 90.0)
        ride.noteIncident("hard brake", t = 1010.0)
        ride.stop(1020.0)

        // A battery death mid-write: lop off half a line.
        val data = File(path).readText()
        File(path).writeText(data.substring(0, data.length - 12))

        val log = Ride.readLog(path)
        assertEquals("torn", log.header["name"])
        assertTrue(log.samples.size >= 18)
        assertEquals("hard brake", log.incidents[0]["what"])
    }

    @Test
    fun `an unwritable path does not stop the ride`() {
        val ride = Ride(path = "/proc/definitely/not/writable/r.jsonl")
        ride.start(1000.0)
        assertNotNull(ride.addFix(lat = 37.8, lon = -122.27, t = 1000.0))
        assertTrue(ride.errors.isNotEmpty())
    }

    @Test
    fun `a missing log reads as nothing rather than raising`() {
        val log = Ride.readLog(File(dir, "nope.jsonl").path)
        assertEquals(emptyMap(), log.header)
        assertEquals(emptyList(), log.samples)
        assertNull(Ride.headerName(File(dir, "nope.jsonl").path))
    }

    @Test
    fun `the rides list reads a name off the first line alone`() {
        val path = File(dir, "ride.jsonl").path
        val ride = Ride(path = path, name = "first line")
        ride.start(1000.0)
        ride.stop(1001.0)
        assertEquals("first line", Ride.headerName(path))
        File(path).writeText("""{"kind":"footer"}""" + "\n")
        assertNull(Ride.headerName(path))
    }
}

/**
 * Ported from apps/navi/tests/test_road_test.py:312-416
 * (EveryChannelReachesTheLogAndTheGpx, TheWholeRideIsSent). The upload itself
 * belongs to the app; what the core owes it is a log that holds every channel,
 * and a whole ride rebuilt from that log.
 */
class EveryChannelReachesTheLogTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = scratchDir()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun ride(n: Int = 4, name: String = "probe"): Ride {
        val ride = Ride(path = File(dir, "r.jsonl").path, name = name)
        ride.start(1000.0)
        for (i in 0 until n) {
            ride.addFix(lat = 37.8 + i * 0.001, lon = -122.27, t = 1000.0 + i, speedMs = 15.0, heading = 90.0 + i, altM = 10.0 + i)
            ride.feedExtra(
                linkedMapOf(
                    "rpm_est" to 4000L + i, "climb_mps" to 0.5 + i, "pressure_hpa" to 1012.0 - i, "alt_baro_m" to 11.0 + i,
                    "g_vert" to 1.0, "tilt_deg" to -3.0, "compass_deg" to 91.0, "battery_pct" to 80L,
                ),
            )
        }
        return ride
    }

    @Test
    fun `sensor channels reach the log on disk`() {
        // The ONE FIX LATE bug: rows were written before the sensors were merged
        // into them, so the log on the phone held no sensor channel at all.
        val ride = ride()
        ride.stop(1010.0)
        val samples = Ride.readLog(ride.path!!).samples
        assertEquals(4, samples.size)
        samples.forEachIndexed { i, row ->
            assertEquals(4000L + i, row["rpm_est"], "$row")
            assertEquals(0.5 + i, row["climb_mps"], "$row")
            assertEquals(1012.0 - i, row["pressure_hpa"], "$row")
        }
    }

    @Test
    fun `the newest row waits for its merge`() {
        val ride = ride(n = 2)
        assertSame<Any?>(ride.samples.last(), ride.pending)
        ride.stop(1010.0)
        assertNull(ride.pending, "stop() did not write the last row")
    }

    @Test
    fun `the gpx carries every channel not eight`() {
        val gpx = ride().toGpx()
        for (key in listOf("climb_mps", "alt_baro_m", "pressure_hpa", "g_vert", "tilt_deg", "compass_deg", "battery_pct", "rpm_est", "lean_deg", "grade_pct")) {
            assertTrue("<navi:$key>" in gpx, key)
        }
        assertTrue("<ele>" in gpx)
    }

    @Test
    fun `the gpx says which altitude ele is`() {
        assertTrue("WGS84" in ride().toGpx())
    }

    @Test
    fun `the gpx is still well formed xml`() {
        val ride = ride()
        ride.name = "ride\u0001 name"
        ride.feedExtra(linkedMapOf("bad key" to 1L, "odd<tag" to 2L, "fine_one" to "a<b\u0002", "a_list" to listOf(1L, 2L), "x²" to 3L, "ª" to 4L))
        val gpx = ride.toGpx()
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(gpx.byteInputStream())
        assertEquals(4, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        assertTrue("fine_one" in gpx)
        assertFalse("a_list" in gpx, "a list written as Python repr")
    }

    @Test
    fun `ele comes before time as the schema requires`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ride().toGpx().byteInputStream())
        val point = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").item(0) as Element
        val children = (0 until point.childNodes.length).map { point.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("ele", "time"), children.take(2).map { it.localName })
    }

    @Test
    fun `a ride rebuilt from disk sends what memory would`() {
        val ride = ride()
        ride.stop(1010.0)
        assertEquals(ride.toGpx(), Ride.loadRide(ride.path!!).toGpx())
    }

    @Test
    fun `a ride longer than the window is rebuilt whole`() {
        // The in-memory window is ten minutes; the upload read it and lost the
        // start of every longer ride. The log holds the whole ride.
        val n = Ride.WINDOW + 150
        val ride = ride(n = n)
        ride.stop(5000.0)
        assertEquals(Ride.WINDOW, ride.samples.size)
        assertEquals(n, Regex("<trkpt").findAll(Ride.loadRide(ride.path!!).toGpx()).count())
    }
}
