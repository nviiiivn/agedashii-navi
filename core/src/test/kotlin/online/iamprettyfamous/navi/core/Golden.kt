package online.iamprettyfamous.navi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertTrue

/**
 * Outputs recorded from the Python, which is the specification. The generators
 * are tools/golden_*.py; each says how to re-run it.
 */
object Golden {
    fun load(name: String): JsonObject {
        val stream = Golden::class.java.getResourceAsStream("/golden/$name")
            ?: error("missing golden file $name - run tools/golden_*.py")
        return Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }
}

fun JsonElement.doubles(): DoubleArray = jsonArray.map { it.jsonPrimitive.doubleOrNull!! }.toDoubleArray()

fun JsonElement?.doubleOrNull(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

/** Equal to within [relative] of the larger magnitude, or [absolute] near zero. */
fun assertClose(expected: Double?, actual: Double?, relative: Double = 1e-9, absolute: Double = 1e-12, what: String = "") {
    if (expected == null || actual == null) {
        assertTrue(expected == null && actual == null, "$what: expected $expected, got $actual")
        return
    }
    val tolerance = max(absolute, relative * max(abs(expected), abs(actual)))
    assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual (tolerance $tolerance)")
}
