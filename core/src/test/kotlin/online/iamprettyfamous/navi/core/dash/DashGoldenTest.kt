package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.Golden
import online.iamprettyfamous.navi.core.Py
import online.iamprettyfamous.navi.core.PyJson
import online.iamprettyfamous.navi.core.arr
import online.iamprettyfamous.navi.core.assertClose
import online.iamprettyfamous.navi.core.doubleOrNull
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.obj
import online.iamprettyfamous.navi.core.str
import online.iamprettyfamous.navi.core.theme.Role
import online.iamprettyfamous.navi.core.theme.Themes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every gauge against what gauges.py (md5 86eb5cf3) actually drew, taken apart by
 * tools/golden_dash.py: the texts it shows, the angle it turns, the fraction it
 * fills, the role it paints. Colours are compared as roles; the only roles that
 * may differ are the ones in [DashColours.CHANGED], each a decoration that wore a
 * state's colour, changed on her rule that warn/good/bad are states only.
 */
class DashGoldenTest {
    private val g = Golden.load("dash.json")
    private val night = Themes["night"]

    /** The Python's colour, as the role it was in the night palette. ROAD shares ACCENT's hex; the gauges never use ROAD. */
    private fun pyRole(hex: String): Role =
        Role.entries.filter { it != Role.ROAD }.single { "#%06x".format(night[it]) == hex.lowercase() }

    /** What our role must be, given the Python's: the same, unless it is a listed change. */
    private fun expected(part: String, python: Role): Role = DashColours.CHANGED[part]?.get(python) ?: python

    private fun JsonElement?.d(): Double? = doubleOrNull()
    private fun JsonElement.textOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonObject.texts() = getValue("texts").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `constants and layouts are gauges py's`() {
        val c = g.obj("constants")
        assertEquals(c.getValue("LEAN_FULL_DEG").jsonPrimitive.double, Gauges.LEAN_FULL_DEG)
        assertEquals(c.getValue("PITCH_FULL_DEG").jsonPrimitive.double, Gauges.PITCH_FULL_DEG)
        assertEquals(c.getValue("G_FULL").jsonPrimitive.double, Gauges.G_FULL)
        assertEquals(c.getValue("ROLL_HINT_DEG").jsonPrimitive.double, Gauges.ROLL_HINT_DEG)
        assertEquals(
            g.arr("layouts").map { it.jsonArray.map { v -> v.jsonPrimitive.content } },
            Layout.entries.filter { it != Layout.OFF }.map { listOf(it.key, it.title, it.detail) },
        )
        assertEquals(
            g.arr("lean_bands").map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.content.uppercase() },
            DashColours.LEAN_BANDS.map { it.first to it.second.name },
        )
    }

    /** A part where the Python drew a reading from nothing: listed in [Gauges.PYTHON_WRONG], or this fails. */
    private fun pythonWrong(part: String) = assertTrue(part in Gauges.PYTHON_WRONG, "$part broke the golden without a PYTHON_WRONG entry")

    @Test
    fun `every break from the golden says the Python is wrong, and there are no others`() {
        assertEquals(setOf("horizon", "horizon_line", "g_ball", "climb", "altitude"), Gauges.PYTHON_WRONG.keys)
        for ((part, why) in Gauges.PYTHON_WRONG) assertTrue(why.startsWith("gauges.py is wrong here"), part)
    }

    @Test
    fun `the horizon turns, rises and shows the phone as the Python's did`() {
        for (e in g.arr("horizon")) {
            val c = e.jsonObject
            val h = Gauges.horizon(c["lean"].d(), c["pitch"].d(), c["roll"].d())
            if (c["lean"].d() == null) {
                pythonWrong("horizon")
                assertNull(h.rotationDeg, "$c: no lean, no horizon")
            } else {
                assertClose(c.getValue("angle_deg").jsonPrimitive.double, h.rotationDeg, 1e-9, 1e-9, "$c angle")
            }
            assertClose(c.getValue("shift").jsonPrimitive.double, h.shift, 1e-12, 1e-12, "$c shift")
            assertClose(c["needle_deg"].d(), h.needleDeg, 1e-9, 1e-9, "$c needle")
            assertEquals(c.texts(), listOf(h.caption), "$c")
        }
    }

    @Test
    fun `the g ball sits where the Python put it`() {
        for (e in g.arr("g_ball")) {
            val c = e.jsonObject
            val b = Gauges.gBall(c["g_lat"].d(), c["g_long"].d())
            if (c["g_lat"].d() == null || c["g_long"].d() == null) {
                pythonWrong("g_ball")
                assertNull(b.x, "$c: no dot")
                assertNull(b.y, "$c: no dot")
                assertEquals(Gauges.DASH, b.caption, "$c")
                continue
            }
            assertClose(c.getValue("x").jsonPrimitive.double, b.x, 1e-12, 1e-12, "$c x")
            assertClose(c.getValue("y").jsonPrimitive.double, b.y, 1e-12, 1e-12, "$c y")
            assertEquals(expected("g_dot", pyRole(c.str("colour"))), b.dot, "$c")
            assertEquals(c.texts(), listOf(b.caption), "$c")
        }
    }

    @Test
    fun `meters fill and warn as the Python's did`() {
        for (e in g.arr("meter")) {
            val c = e.jsonObject
            val m = Gauges.meter(c["fraction"].d(), c["peak"].d(), c.str("label"), c.str("value"))
            // The Python fills with integer flex weights out of ~1000, hence 2e-3.
            assertClose(c.getValue("fill").jsonPrimitive.double, m.fill, 0.0, 2e-3, "$c fill")
            assertClose(c["tick"].d(), m.tick, 0.0, 2e-3, "$c tick")
            assertEquals(expected("meter", pyRole(c.str("colour"))), m.bar, "$c")
            assertEquals(c.texts(), listOf(m.value, m.label), "$c")
        }
    }

    @Test
    fun `climb reads the same, in the colours that are now honest`() {
        for (e in g.arr("climb")) {
            val c = e.jsonObject
            val k = Gauges.climb(c["alt_m"].d(), c["climb_mps"].d(), c.getValue("imperial").jsonPrimitive.boolean)
            if (c["climb_mps"].d() == null) {
                pythonWrong("climb")
                assertEquals(Arrow.NONE, k.arrow, "$c: no rate is not level")
                assertEquals(DashColours.CLIMB_LEVEL, k.role, "$c")
                assertEquals(listOf(c.texts()[0], Gauges.DASH, "altitude"), listOf(k.height, k.detail, "altitude"), "$c")
                continue
            }
            val arrow = mapOf("NORTH_EAST" to Arrow.UP, "SOUTH_EAST" to Arrow.DOWN, "EAST" to Arrow.LEVEL).getValue(c.str("icon"))
            assertEquals(arrow, k.arrow, "$c")
            assertEquals(expected("climb_${arrow.name.lowercase()}", pyRole(c.str("colour"))), k.role, "$c")
            assertEquals(c.texts(), listOf(k.height, k.detail, "altitude"), "$c")
        }
    }

    @Test
    fun `the band meter fills as the Python's did, in one data colour`() {
        for (e in g.arr("bands")) {
            val c = e.jsonObject
            val raw = c.getValue("bands")
            val bands = if (raw is JsonNull) null else raw.jsonArray.map {
                it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].d()
            }
            val got = Gauges.bands(bands)
            val want = c.getValue("bars").jsonArray.map { it.jsonObject }
            assertEquals(want.size, got.size, "$c")
            want.zip(got).forEach { (w, b) ->
                assertClose(w.getValue("fill").jsonPrimitive.double, b.fill, 0.0, 2e-3, "$c fill")
                assertEquals(expected("bands", pyRole(w.str("colour"))), b.role, "$c")
            }
            assertEquals(c.texts(), got.map { it.label } + "bands", "$c")
        }
    }

    @Test
    fun `lean warms through the same bands in every theme`() {
        for ((key, cases) in g.obj("lean_colour")) {
            for (e in cases.jsonArray) {
                val c = e.jsonObject
                assertEquals(c.str("colour").lowercase(), "#%06x".format(Themes[key][Gauges.leanRole(c["lean"].d())]), "$key $c")
            }
        }
    }

    @Test
    fun `the gear badge and shift light`() {
        for (e in g.arr("gear")) {
            val c = e.jsonObject
            val b = Gauges.gearBadge(c["gear"].d()?.toInt(), c.str("hint"))
            assertEquals(c.str("text"), b.text, "$c")
            assertEquals(pyRole(c.str("text_colour")), b.textRole, "$c")
            val arrow = mapOf("ARROW_UPWARD" to Arrow.UP, "ARROW_DOWNWARD" to Arrow.DOWN, "REMOVE" to Arrow.NONE).getValue(c.str("icon"))
            assertEquals(arrow, b.arrow, "$c")
            assertEquals(pyRole(c.str("icon_colour")), b.arrowRole, "$c")
        }
    }

    @Test
    fun `g force bars grow from the centre as the Python's did`() {
        for (e in g.arr("gforce")) {
            val c = e.jsonObject
            val f = Gauges.gForceBars(c["g_lat"].d(), c["g_long"].d())
            for ((part, bar, key) in listOf(Triple("g_long", f.long, "long"), Triple("g_lat", f.lat, "lat"))) {
                val w = c.obj(key)
                assertClose(w.getValue("left").jsonPrimitive.double, bar.left, 0.0, 2e-3, "$c $key left")
                assertClose(w.getValue("fill").jsonPrimitive.double, bar.fill, 0.0, 2e-3, "$c $key fill")
                assertClose(w.getValue("right").jsonPrimitive.double, bar.right, 0.0, 2e-3, "$c $key right")
                assertEquals(expected(part, pyRole(w.str("colour"))), bar.role, "$c $key")
            }
            assertEquals(c.texts(), listOf("BRK", "ACC"))
        }
    }

    @Test
    fun `the rpm line`() {
        for (e in g.arr("rpm_line")) {
            val c = e.jsonObject
            val r = Gauges.rpmLine(c["rpm"].d(), c.str("machine"), c.str("hint"))
            assertClose(c.getValue("fill").jsonPrimitive.double, r.fill, 0.0, 2e-3, "$c fill")
            assertEquals(c.getValue("gradient").jsonArray.map { pyRole(it.jsonPrimitive.content) }, DashColours.REV_ZONES, "$c")
            assertEquals(c.str("text"), r.text, "$c")
            assertEquals(pyRole(c.str("text_colour")), r.textRole, "$c")
        }
    }

    @Test
    fun `the gunsight line`() {
        for (e in g.arr("horizon_line")) {
            val c = e.jsonObject
            val h = Gauges.horizonLine(c["lean"].d(), c["pitch"].d())
            if (c["lean"].d() == null) {
                pythonWrong("horizon_line")
                assertNull(h.rotationDeg, "$c: no lean, no line")
            } else {
                assertClose(c.getValue("angle_deg").jsonPrimitive.double, h.rotationDeg, 1e-9, 1e-9, "$c")
            }
            assertClose(c.getValue("shift").jsonPrimitive.double, h.shift, 1e-12, 1e-12, "$c")
            assertEquals(pyRole(c.str("colour")), h.role, "$c")
        }
    }

    @Test
    fun `the scales`() {
        for (e in g.arr("loudness_norm")) assertEquals(e.jsonArray[1].d(), Gauges.loudnessNorm(e.jsonArray[0].d()), "$e")
        for (e in g.arr("rpm_fraction")) {
            val a = e.jsonArray
            assertEquals(a[2].d(), Gauges.rpmFraction(a[0].d(), a[1].textOrNull()), "$e")
        }
    }

    @Test
    fun `every layout shows the texts the Python's showed`() {
        val states = g.obj("states")
        val overlays = g.arr("overlay")
        assertEquals(35, overlays.size)
        for (e in overlays) {
            val c = e.jsonObject
            val state = stateOf(states.obj(c.str("state")))
            val layout = Layout.of(c.str("layout"))
            val hud = Hud.plan(state, layout, wide = c.getValue("wide").jsonPrimitive.boolean, anchor = Anchor.of(c.str("anchor")))
            assertEquals(c.getValue("none").jsonPrimitive.boolean, hud == null, "$c")
            // Order differs with the arrangement; what is shown must not.
            assertEquals(corrected(c.texts(), state, hud).sorted(), hud?.texts().orEmpty().sorted(), "$c")
        }
    }

    /** The Python's texts, corrected at exactly the [Gauges.PYTHON_WRONG] parts this state reaches, and no others. */
    private fun corrected(python: List<String>, state: DashState, hud: Hud?): List<String> {
        val out = python.toMutableList()
        val items = hud?.groups.orEmpty().flatMap { it.items }
        if (state.climbMps == null) repeat(items.count { it is ClimbItem }) {
            pythonWrong("climb")
            out[out.indexOf("level")] = Gauges.DASH
        }
        val oneAxis = listOfNotNull(state.gLat, state.gLong).singleOrNull()
        if (oneAxis != null && (state.gLat == null || state.gLong == null)) repeat(items.count { it is GBallItem }) {
            pythonWrong("g_ball")
            out[out.indexOf(Py.fixed(abs(oneAxis), 2) + " g")] = Gauges.DASH
        }
        for (item in items.filterIsInstance<Num>()) {
            when (item.reads) {
                setOf(DashState.Field.ALT) -> {
                    pythonWrong("altitude")
                    val alt = state.altM
                    val pyValue = if (alt == null) Gauges.DASH else Gauges.whole(if (state.imperial) alt * Gauges.FT_PER_M else alt)
                    out.remove(pyValue)
                    out.remove(if (state.imperial) "ft" else "m")
                    out += item.texts()
                }
                setOf(DashState.Field.CLIMB_SINCE_START) -> {
                    pythonWrong("altitude")
                    out += item.texts()
                }
                else -> {}
            }
        }
        return out
    }

    private fun stateOf(o: JsonObject): DashState {
        @Suppress("UNCHECKED_CAST")
        val m = PyJson.fromElement(o) as Map<String, Any?>
        fun d(k: String) = (m[k] as Number?)?.toDouble()
        return DashState(
            imperial = m["imperial"] as Boolean,
            machine = m["machine"] as String?,
            speedMs = d("speed_ms"),
            limit = Guidance.speedLimit((m["limit"] as Number?)?.toInt(), null),
            leanDeg = d("lean_deg"),
            tiltDeg = d("tilt_deg"),
            pitchDeg = d("pitch_deg"),
            gLat = d("g_lat"),
            gLong = d("g_long"),
            altM = d("alt_m"),
            climbMps = d("climb_mps"),
            rpm = d("rpm"),
            gear = (m["gear"] as Number?)?.toInt(),
            shift = m["shift"] as String? ?: "",
            bands = (m["bands"] as List<*>?)?.map { (it as List<*>)[0] as String to ((it[1] as Number?)?.toDouble() ?: 0.0) },
            levelDb = d("loudness"),
            levelPeak = d("loudness_peak"),
        )
    }
}
