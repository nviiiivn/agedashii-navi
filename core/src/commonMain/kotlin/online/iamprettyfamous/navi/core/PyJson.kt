package online.iamprettyfamous.navi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Python's `json.dumps` and `json.loads`, as far as the ride log uses them.
 *
 * The log's lines, the JSON export and the summaries the Rides page shows were
 * all written by Python, and the server, Moto and the dashboard read them. The
 * port writes the same bytes: `ensure_ascii` escapes, Python's float repr
 * (1700000000.0, never 1.7E9; NaN and Infinity as Python spells them), and the
 * exact separators and indent layout. Reading keeps Python's distinction between
 * a whole number (Long) and a fraction (Double).
 */
object PyJson {
    private val strict = Json
    private val special = Json { isLenient = true }

    /** `json.dumps(value)`, separators ", " and ": "; or `indent=n`, separators "," and ": ". */
    fun dumps(value: Any?, indent: Int? = null): String {
        val out = StringBuilder()
        if (indent == null) encode(value, out, ", ", ": ", null, 0) else encode(value, out, ",", ": ", indent, 0)
        return out.toString()
    }

    /** `json.dumps(value, separators=(",", ":"))` - the ride log's line format. */
    fun compact(value: Any?): String {
        val out = StringBuilder()
        encode(value, out, ",", ":", null, 0)
        return out.toString()
    }

    /**
     * `json.loads(text)`. Objects keep their key order. Python also reads NaN and
     * Infinity, which its own writer produces for a NaN reading, so a line that
     * holds one is parsed a second time leniently rather than being lost.
     */
    fun loads(text: String): Any? {
        val element = try {
            strict.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            if ("NaN" !in text && "Infinity" !in text) throw e
            special.parseToJsonElement(text)
        }
        return fromElement(element)
    }

    fun fromElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { for ((k, v) in element) put(k, fromElement(v)) }
        is JsonArray -> element.mapTo(ArrayList()) { fromElement(it) }
        is JsonPrimitive -> primitive(element)
    }

    private fun primitive(p: JsonPrimitive): Any? {
        if (p.isString) return p.content
        return when (val c = p.content) {
            "true" -> true
            "false" -> false
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            // A whole number past Long's range was a BigInteger on the JVM;
            // there is none in common Kotlin, and no writer of these logs
            // produces one, so it keeps its magnitude as a Double.
            else -> if (c.any { it == '.' || it == 'e' || it == 'E' }) c.toDouble() else c.toLongOrNull() ?: c.toDouble()
        }
    }

    private fun encode(value: Any?, out: StringBuilder, item: String, key: String, indent: Int?, level: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> out.append(float(value))
            is Float -> out.append(float(value.toDouble()))
            is Number -> out.append(value.toString())
            is String -> string(value, out)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                val inner = newline(indent, level + 1)
                out.append(inner)
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(item).append(inner)
                    first = false
                    string(keyOf(k), out)
                    out.append(key)
                    encode(v, out, item, key, indent, level + 1)
                }
                out.append(newline(indent, level)).append('}')
            }
            is Iterable<*> -> list(value.toList(), out, item, key, indent, level)
            is Array<*> -> list(value.toList(), out, item, key, indent, level)
            else -> throw IllegalArgumentException("Object of type ${value::class.simpleName} is not JSON serializable")
        }
    }

    private fun list(items: List<Any?>, out: StringBuilder, item: String, key: String, indent: Int?, level: Int) {
        if (items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append('[')
        val inner = newline(indent, level + 1)
        out.append(inner)
        items.forEachIndexed { i, v ->
            if (i > 0) out.append(item).append(inner)
            encode(v, out, item, key, indent, level + 1)
        }
        out.append(newline(indent, level)).append(']')
    }

    private fun newline(indent: Int?, level: Int): String = if (indent == null) "" else "\n" + " ".repeat(indent * level)

    /** Python turns non-string keys into strings: True -> "true", None -> "null", 1.0 -> "1.0". */
    private fun keyOf(k: Any?): String = when (k) {
        is String -> k
        null -> "null"
        is Boolean -> if (k) "true" else "false"
        is Double -> float(k)
        is Number -> k.toString()
        else -> throw IllegalArgumentException("keys must be str, int, float, bool or None, not ${k::class.simpleName}")
    }

    private fun float(x: Double): String = when {
        x.isNaN() -> "NaN"
        x == Double.POSITIVE_INFINITY -> "Infinity"
        x == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> Py.repr(x)
    }

    /** ensure_ascii: everything outside space..tilde is escaped, lowercase hex. */
    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c in ' '..'~') out.append(c) else out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        out.append('"')
    }
}
