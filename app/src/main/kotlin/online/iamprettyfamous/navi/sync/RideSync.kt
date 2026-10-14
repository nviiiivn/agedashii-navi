package online.iamprettyfamous.navi.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import online.iamprettyfamous.navi.prefs.Prefs
import online.iamprettyfamous.navi.record.RideFiles
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 9: rides leave the phone.
 *
 * Every finished ride has a `.gpx` beside its log (RideFiles.export). This sends
 * each one to the dashboard on the Pi - the same `/api/rides/upload` the Flet app
 * used, same JSON shape, so Moto's Trips page and the server folder see Kotlin
 * rides exactly like the old ones - and marks it with a `<ride>.sent` file so it
 * is never sent twice.
 *
 * It runs by itself: when a ride ends, when the app opens, and the moment the
 * phone gets a network (she rides with no signal and the phone finds Wi-Fi at
 * home - that is the moment that matters). Nothing is ever deleted here.
 */
object RideSync {
    private const val TAG = "RideSync"
    const val DEFAULT_URL = "http://192.168.86.21:6900"

    data class Status(
        val sent: Int = 0,
        val waiting: Int = 0,
        val busy: Boolean = false,
        /** The last thing that happened, for the Settings line. */
        val last: String = "",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var watching = false

    /** Rides with a .gpx and no .sent marker, oldest first. */
    fun waiting(context: Context): List<File> =
        RideFiles.dir(context).listFiles { f -> f.name.endsWith(".gpx") }
            ?.filter { !marker(it).exists() }?.sortedBy { it.name }.orEmpty()

    private fun sentCount(context: Context): Int =
        RideFiles.dir(context).listFiles { f -> f.name.endsWith(".sent") }?.size ?: 0

    private fun marker(gpx: File) = File(gpx.path.removeSuffix(".gpx") + ".sent")

    /** Recount without sending. */
    fun refresh(context: Context) {
        val c = context.applicationContext
        _status.value = _status.value.copy(sent = sentCount(c), waiting = waiting(c).size)
    }

    /** Send everything that is waiting, once, in the background. Safe to call often. */
    fun kick(context: Context, why: String = "") {
        val c = context.applicationContext
        scope.launch { sendAll(c, why) }
    }

    /**
     * Listen for the network coming back and send then. Registered once for the
     * life of the process; the callback fires on every network the phone gains,
     * so a ride finished in a canyon goes up when she walks in the door.
     */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val c = context.applicationContext
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kick(c, "network back") }
        })
        refresh(c)
    }

    private suspend fun sendAll(c: Context, why: String) {
        if (!lock.tryLock()) return
        try {
            val todo = waiting(c)
            _status.value = Status(sent = sentCount(c), waiting = todo.size, busy = todo.isNotEmpty(), last = _status.value.last)
            if (todo.isEmpty()) return
            if (!online(c)) { _status.value = _status.value.copy(busy = false, last = "no network - ${todo.size} waiting"); return }
            val url = Prefs(c).syncUrl.trimEnd('/')
            var ok = 0; var fail = ""
            for (gpx in todo) {
                val r = post(url, gpx)
                if (r.isSuccess) {
                    marker(gpx).writeText("${r.getOrThrow()}\n${ISO.get()!!.format(Date())}\n")
                    ok++
                    _status.value = _status.value.copy(sent = sentCount(c), waiting = waiting(c).size)
                } else {
                    fail = r.exceptionOrNull()?.message ?: "failed"
                    Log.w(TAG, "upload failed ${gpx.name}: $fail")
                    break   // the server is unreachable or unhappy; try again on the next network/open
                }
            }
            val left = waiting(c).size
            _status.value = Status(
                sent = sentCount(c), waiting = left, busy = false,
                last = when {
                    fail.isNotEmpty() -> "sent $ok, then: $fail ($left waiting)"
                    ok > 0 -> "sent $ok ${if (why.isNotEmpty()) "($why)" else ""}".trim()
                    else -> _status.value.last
                },
            )
        } finally { lock.unlock() }
    }

    private fun online(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** One ride up. The server answers {"ok": true, "id": "<slug>"}; the id is what we keep. */
    private fun post(base: String, gpx: File): Result<String> = runCatching {
        val body = JSONObject().put("filename", gpx.name).put("content", gpx.readText()).toString()
        val conn = (URL("$base/api/rides/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 120000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error("HTTP $code")
            val j = runCatching { JSONObject(text) }.getOrNull() ?: error("odd reply")
            if (!j.optBoolean("ok", false)) error(j.optString("error", "server said no"))
            j.optString("id", gpx.nameWithoutExtension)
        } finally { conn.disconnect() }
    }

    private val ISO = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US) }
}
