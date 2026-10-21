package online.iamprettyfamous.navi.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import online.iamprettyfamous.navi.core.pack.MapPack
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/**
 * A map pack she picked (Settings, or "Open in xnav.k" from Files or Safari),
 * swapped in for whatever region is on the phone - the Android app's
 * DataPack.importPack, over core's MapPack. Its files land in
 * [DataFiles.packDir], and [changed] (on the main thread) reloads the map,
 * the router and the places list from there.
 */
object PackImport {
    private const val KEY_REGION = "data_region"
    private const val KEY_VERSION = "data_version"

    data class State(
        val busy: Boolean = false,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        /** Why the pack was not put in, in words she can act on. */
        val error: String = "",
        /** Which region is on the phone: the last pack's, or the bundle's own. */
        val region: String = "",
    )

    private val defaults get() = NSUserDefaults.standardUserDefaults
    private val _state = MutableStateFlow(State(region = defaults.stringForKey(KEY_REGION) ?: ""))
    val state: StateFlow<State> = _state

    private val queue = dispatch_queue_create("pack", null)

    /**
     * Put the pack at [path] in. [release] runs when the file is no longer
     * read (Swift's end of a security-scoped URL); [changed] after a pack has
     * gone in, on the main thread.
     */
    fun import(path: String, release: () -> Unit, changed: () -> Unit) {
        if (_state.value.busy) return release()
        _state.value = _state.value.copy(busy = true, error = "", doneBytes = 0, totalBytes = 0)
        dispatch_async(queue) {
            val result = try {
                FileSystem.SYSTEM.source(path.toPath()).buffer().use { src ->
                    var shown = 0L
                    MapPack.install(
                        src, FileSystem.SYSTEM,
                        target = { name -> if (name in NAMES) "${DataFiles.packDir}/$name".toPath() else null },
                        freeBytes = freeBytes(),
                    ) { done, total ->
                        if (done - shown > 8_000_000 || done == total) {
                            shown = done
                            _state.value = _state.value.copy(doneBytes = done, totalBytes = total)
                        }
                    }
                }
            } catch (ex: Exception) {
                MapPack.Result.Failed("That map pack could not be opened (${ex.message}) - pick the ${MapPack.SUFFIX} file again")
            } finally {
                release()
            }
            when (result) {
                is MapPack.Result.Installed -> {
                    println("[pack] in: ${result.region.ifEmpty { "a pack" }} ${result.version}, ${result.bytes / 1_000_000} MB")
                    defaults.setObject(result.region, KEY_REGION)
                    defaults.setObject(result.version, KEY_VERSION)
                    _state.value = State(region = result.region)
                    dispatch_async(dispatch_get_main_queue()) { changed() }
                }
                is MapPack.Result.Failed -> {
                    println("[pack] refused: ${result.why}")
                    _state.value = _state.value.copy(busy = false, error = result.why)
                }
            }
        }
    }

    private val NAMES = setOf(DataFiles.TILES, DataFiles.ROUTER, DataFiles.PLACES)

    private fun freeBytes(): Long? =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(DataFiles.packDir, error = null)
            ?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
}
