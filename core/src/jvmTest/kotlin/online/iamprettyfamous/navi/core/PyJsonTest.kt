package online.iamprettyfamous.navi.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Python's json.dumps and json.loads, as far as the ride log uses them. Every
 * expected string here is what CPython 3.14 printed for the same value.
 */
class PyJsonTest {
    @Test
    fun `the log's compact form`() {
        val row = linkedMapOf<String, Any?>("t" to 1000.0, "lat" to 37.8, "n" to 4200L, "none" to null, "ok" to true, "list" to listOf(1L, 2L))
        assertEquals("""{"t":1000.0,"lat":37.8,"n":4200,"none":null,"ok":true,"list":[1,2]}""", PyJson.compact(row))
    }

    @Test
    fun `the default separators`() {
        assertEquals("""{"a": 1, "b": [1.5, "x"]}""", PyJson.dumps(linkedMapOf("a" to 1, "b" to listOf(1.5, "x"))))
    }

    @Test
    fun `strings are escaped to ascii`() {
        // json.dumps("Grizzly & Wildcat <\x01> café\x7f\"\\\n\t") in CPython.
        assertEquals(
            "\"Grizzly & Wildcat <\\u0001> caf\\u00e9\\u007f\\\"\\\\\\n\\t\"",
            PyJson.dumps("Grizzly & Wildcat <\u0001> café\u007f\"\\\n\t"),
        )
        assertEquals("\"\\ud83c\\udfcd\"", PyJson.dumps("🏍"))
    }

    @Test
    fun `indent one is the export layout`() {
        val value = linkedMapOf("summary" to linkedMapOf("name" to "x", "samples" to 2), "incidents" to emptyList<Any>(), "empty" to emptyMap<String, Any>(), "rows" to listOf(linkedMapOf("t" to 1.0)))
        val expected = "{\n \"summary\": {\n  \"name\": \"x\",\n  \"samples\": 2\n },\n \"incidents\": [],\n \"empty\": {},\n \"rows\": [\n  {\n   \"t\": 1.0\n  }\n ]\n}"
        assertEquals(expected, PyJson.dumps(value, indent = 1))
    }

    @Test
    fun `floats are python floats`() {
        assertEquals("[1700000000.0, 1e-05, -0.0, NaN, Infinity, -Infinity]", PyJson.dumps(listOf(1700000000.0, 1e-5, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun `reading keeps whole numbers whole and fractions fractional`() {
        val got = PyJson.loads("""{"a":1000,"b":1000.0,"c":1e3,"d":"1","e":null,"f":[true,false],"g":{"h":-2}}""") as Map<*, *>
        assertEquals(1000L, got["a"])
        assertEquals(1000.0, got["b"])
        assertEquals(1000.0, got["c"])
        assertEquals("1", got["d"])
        assertNull(got["e"])
        assertEquals(listOf(true, false), got["f"])
        assertEquals(mapOf("h" to -2L), got["g"])
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), got.keys.toList())
    }

    @Test
    fun `a torn line is an error, not a value`() {
        assertTrue(runCatching { PyJson.loads("""{"t":1000.0,"lat":37""") }.isFailure)
    }
}
