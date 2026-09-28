package online.iamprettyfamous.navi.record

import android.content.Context
import online.iamprettyfamous.navi.core.telemetry.Ride
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where rides live and how they leave. One JSONL log per ride in the app's own
 * external folder (`adb pull` reaches it, nothing else needs a permission), with
 * CSV, GPX and JSON written beside it when the ride stops.
 */
object RideFiles {
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rides").apply { mkdirs() }

    /** `ride-20260927-143005.jsonl`: seconds in the name, like the Flet app's. */
    fun newPath(context: Context, atMs: Long, suffix: String = ""): String =
        File(dir(context), "ride-${STAMP.get()!!.format(Date(atMs))}$suffix.jsonl").path

    /**
     * Seconds in the ride's NAME too. The core's default name stops at the
     * minute, and the server keys one ride per name, so two rides started in
     * the same minute overwrote each other on the dashboard (live.py:219-227).
     */
    fun newName(atMs: Long): String = NAME.get()!!.format(Date(atMs))

    /**
     * Writes `<ride>.csv`, `.gpx` and `.json` beside the log and returns them.
     *
     * Always from the WHOLE log read back off disk, never from a live Ride: its
     * in-memory window keeps only the last 600 rows, and exporting from a window
     * silently cut every ride over ten minutes short in the Flet app.
     */
    fun export(path: String): List<File> {
        val full = Ride.loadRide(path)
        val rows = full.samples
        val base = path.removeSuffix(".jsonl")
        return listOf(
            File("$base.csv").apply { writeText(full.toCsv(rows)) },
            File("$base.gpx").apply { writeText(full.toGpx(rows)) },
            File("$base.json").apply { writeText(full.toJson(rows)) },
        )
    }

    private val STAMP = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
    private val NAME = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
}
