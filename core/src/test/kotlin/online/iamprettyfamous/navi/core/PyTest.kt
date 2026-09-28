package online.iamprettyfamous.navi.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Python's arithmetic where it differs from Kotlin's. Ride logs, CSV exports and
 * spoken distances were all produced by Python; the port must produce the same
 * text for the same number, or a log written by one cannot be diffed against the
 * other and "in 400 ft" becomes "in 450 ft" on a tie.
 */
class PyTest {
    private fun fromBits(hex: String) = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, 16))

    @Test
    fun `repr, round and format agree with Python on every golden value`() {
        val cases = Golden.load("py.json").arr("cases")
        for (element in cases) {
            val case = element.jsonObject
            val x = fromBits(case.str("bits"))
            assertEquals(case.str("repr"), Py.repr(x), "repr of $x")
            assertEquals(case.str("str"), Py.str(x), "str of $x")
            case.getValue("round").jsonArray.forEachIndexed { places, expected ->
                val want = fromBits(expected.jsonPrimitive.content)
                val got = Py.round(x, places)
                assertEquals(want.toRawBits(), got.toRawBits(), "round($x, $places): want $want got $got")
            }
            assertEquals(case.str("fixed0"), Py.fixed(x, 0), "f'{$x:.0f}'")
            assertEquals(case.str("fixed1"), Py.fixed(x, 1), "f'{$x:.1f}'")
            case["round_int"]?.let { assertEquals(it.jsonPrimitive.long, Py.roundToLong(x), "round($x)") }
        }
    }

    @Test
    fun `ties go to even, as Python's do`() {
        assertEquals(2L, Py.roundToLong(2.5))
        assertEquals(4L, Py.roundToLong(3.5))
        assertEquals(-2L, Py.roundToLong(-2.5))
        // 2.675 is really 2.67499999... in binary, so it rounds down.
        assertEquals(2.67, Py.round(2.675, 2))
    }

    @Test
    fun `negative zero survives rounding`() {
        assertEquals((-0.0).toRawBits(), Py.round(-0.0004, 3).toRawBits())
        assertEquals("-0.0", Py.repr(Py.round(-0.0004, 3)))
    }

    @Test
    fun `whole numbers keep their point and big ones stay unscientific`() {
        assertEquals("1700000000.0", Py.repr(1700000000.0))
        assertEquals("1e+16", Py.repr(1e16))
        assertEquals("0.0001", Py.repr(0.0001))
        assertEquals("1e-05", Py.repr(0.00001))
    }

    @Test
    fun `str of a ride row value is what Python wrote into the CSV`() {
        assertEquals("4200", Py.str(4200L))
        assertEquals("4200", Py.str(4200))
        assertEquals("True", Py.str(true))
        assertEquals("False", Py.str(false))
        assertEquals("hard brake", Py.str("hard brake"))
        assertEquals("None", Py.str(null))
    }
}
