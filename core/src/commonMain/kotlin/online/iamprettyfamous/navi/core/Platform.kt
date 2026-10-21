package online.iamprettyfamous.navi.core

import okio.FileSystem

/**
 * The few things common Kotlin has no word for, answered by each phone: the
 * JVM (Android, and the tests on the Pi) in jvmMain, the iPhone in iosMain.
 * Everything else in this module is the same code on both.
 */

/** The phone's own files. okio's FileSystem.SYSTEM, which every target here has. */
expect val Disk: FileSystem

/** NFKD - compatibility decomposition, so "é" is "e" plus its accent. */
expect fun nfkd(text: String): String

/**
 * A regex with `\d`, `\s` and `\w` meaning what Python's do - Unicode, not
 * ASCII. `ignoreCase` is Unicode-aware; `dotAll` lets `.` cross newlines.
 */
expect fun pythonRegex(pattern: String, ignoreCase: Boolean = false, dotAll: Boolean = false): Regex

/** Seconds since the epoch, as Python's `time.time()`. */
fun nowSeconds(): Double = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000.0

/** `f"{n:,}"`: a whole number with commas between the thousands. */
fun grouped(n: Long): String {
    val digits = kotlin.math.abs(n).toString()
    val out = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$out" else out
}

private const val DEG_TO_RAD = kotlin.math.PI / 180.0
private const val RAD_TO_DEG = 180.0 / kotlin.math.PI

/** Math.toRadians, which is also Python's `math.radians`: x times pi/180. */
fun toRadians(degrees: Double): Double = degrees * DEG_TO_RAD

/** Math.toDegrees, and `math.degrees`: x times 180/pi. */
fun toDegrees(radians: Double): Double = radians * RAD_TO_DEG
