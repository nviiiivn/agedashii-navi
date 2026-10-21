package online.iamprettyfamous.navi.routing

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import online.iamprettyfamous.navi.core.geo.LatLon
import online.iamprettyfamous.navi.core.geo.RouteLine
import online.iamprettyfamous.navi.core.routing.RouteRequest
import online.iamprettyfamous.navi.core.routing.TileCheck
import online.iamprettyfamous.navi.core.routing.TileVersionGuard
import online.iamprettyfamous.navi.core.routing.ValhallaRoute
import java.io.File

/**
 * Offline routing: Valhalla on the phone, reading one tile archive from app
 * storage. Every call runs on its own "router" thread - loading the archive
 * and routing both take real time, and the ride thread must never wait on them.
 *
 * The archive is checked before Valhalla ever sees it: TileVersionGuard refuses
 * tiles built by any Valhalla but the one this binding carries, because
 * Valhalla itself only warns and then routes on misread data.
 */
object Router {
    private const val TAG = "NaviRouter"
    const val TILES_NAME = "bayarea-valhalla-3.6.3.tar"

    /** Whether routing works, and if not, why - for the screen. */
    data class State(val ready: Boolean = false, val why: String = "not loaded", val lastMs: Long? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val handler: Handler by lazy { Handler(HandlerThread("router").apply { start() }.looper) }
    private var valhalla: Valhalla? = null

    fun tilesFile(context: Context): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "routing"), TILES_NAME)

    /** Load the archive (once). Safe to call repeatedly. */
    fun load(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (valhalla != null) return@post
            val tar = tilesFile(app)
            if (!tar.isFile) {
                _state.value = State(why = "no routing file - copy $TILES_NAME to ${tar.parent}")
                return@post
            }
            when (val check = TileVersionGuard.check(tar.path)) {
                is TileCheck.Refused -> {
                    _state.value = State(why = check.reason)
                    return@post
                }
                is TileCheck.Ok -> Log.i(TAG, "tiles ok: ${check.tiles} tiles, Valhalla ${check.version}")
            }
            try {
                val config = ValhallaConfigFactory.usingTileExtract(tar.absolutePath)
                valhalla = Valhalla(app, config)
                _state.value = State(ready = true, why = "")
            } catch (e: Throwable) {
                Log.w(TAG, "Valhalla would not start", e)
                _state.value = State(why = "the router would not start: ${e.message}")
            }
        }
    }

    /** New tiles are in place (DataPack): drop the router reading the old ones and load again. */
    fun reload(context: Context) {
        handler.post { valhalla = null }
        load(context)
    }

    /** Route from [from] to [to]; [done] runs on the router thread with the route or the reason there is none. */
    fun route(from: LatLon, to: LatLon, imperial: Boolean, heading: Double? = null, done: (RouteLine) -> Unit) {
        handler.post {
            val v = valhalla ?: return@post done(RouteLine(ok = false, reason = _state.value.why))
            val t0 = SystemClock.elapsedRealtime()
            val line = try {
                ValhallaRoute.parse(v.routeRaw(RouteRequest.motorcycle(from, to, imperial, heading)))
            } catch (e: Throwable) {
                // Valhalla's own text ("ValhallaError(code=442, No path could
                // be found for input)") reached her screen once; say it plainly.
                val why = e.message.orEmpty()
                RouteLine(ok = false, reason = if ("No path could be found" in why) "no road on this phone's map reaches that point" else "the router failed: $why")
            }
            val ms = SystemClock.elapsedRealtime() - t0
            _state.value = _state.value.copy(lastMs = ms)
            Log.i(TAG, "route ok=${line.ok} %.2f mi %.1f min, ${line.maneuvers.size} turns, ${line.points.size} points, $ms ms ${line.reason}"
                .format(line.distanceM / 1609.344, line.durationS / 60))
            done(line)
        }
    }
}
