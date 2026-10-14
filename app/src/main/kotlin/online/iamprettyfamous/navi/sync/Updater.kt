package online.iamprettyfamous.navi.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The app updates itself, like the Flet one did: on open it reads a small
 * version file from the site, and if the site's build is newer than this one it
 * says so on the map and offers Install under Settings > About. Install
 * downloads the APK to the app's cache and hands it to Android's installer -
 * same package, same key, so it goes on over the top and keeps her settings
 * and rides.
 *
 * Manifest-side: REQUEST_INSTALL_PACKAGES and a FileProvider over the cache.
 */
object Updater {
    private const val TAG = "Updater"
    const val MANIFEST_URL = "https://xnav.alola.lol/kotlin-version.json"

    data class State(
        val checked: Boolean = false,
        val available: Boolean = false,
        val versionName: String = "",
        val versionCode: Int = 0,
        val apkUrl: String = "",
        val size: Long = 0,
        val sha256: String = "",
        val downloading: Boolean = false,
        val progress: Int = 0,
        /** The last thing that happened, for Settings. */
        val note: String = "",
        val dismissed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ask the site what the newest build is. Quiet on failure: no network is normal on the road. */
    fun check(context: Context, currentCode: Int) {
        scope.launch {
            try {
                val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 10000 }
                val text = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val j = JSONObject(text)
                val code = j.optInt("versionCode", 0)
                _state.value = _state.value.copy(
                    checked = true, available = code > currentCode, versionCode = code,
                    versionName = j.optString("versionName", code.toString()), apkUrl = j.optString("apk", ""),
                    size = j.optLong("size", 0), sha256 = j.optString("sha256", "").lowercase(),
                    note = if (code > currentCode) "Update ${j.optString("versionName")} is on the site" else "This is the newest build",
                )
            } catch (e: Exception) {
                Log.i(TAG, "no update check: ${e.message}")
                _state.value = _state.value.copy(checked = true, note = "Couldn't reach the site to check")
            }
        }
    }

    fun dismiss() { _state.value = _state.value.copy(dismissed = true) }

    /** Download the APK and open Android's installer on it. */
    fun install(context: Context) {
        val s = _state.value
        if (!s.available || s.apkUrl.isEmpty() || s.downloading) return
        val c = context.applicationContext
        scope.launch {
            val out = File(c.externalCacheDir ?: c.cacheDir, "navi-update.apk")
            try {
                _state.value = _state.value.copy(downloading = true, progress = 0, note = "Downloading ${s.versionName}…")
                val conn = (URL(s.apkUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 60000 }
                val total = if (s.size > 0) s.size else conn.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024); var done = 0L; var n: Int
                        while (inp.read(buf).also { n = it } > 0) {
                            o.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0) _state.value = _state.value.copy(progress = (done * 100 / total).toInt())
                        }
                    }
                }
                conn.disconnect()
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (s.sha256.isNotEmpty() && got != s.sha256) error("download didn't match the site's checksum")
                val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", out)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _state.value = _state.value.copy(downloading = false, progress = 100, note = "Installing ${s.versionName}")
                c.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.message}")
                runCatching { out.delete() }
                _state.value = _state.value.copy(downloading = false, note = "Update failed: ${e.message}")
            }
        }
    }
}
