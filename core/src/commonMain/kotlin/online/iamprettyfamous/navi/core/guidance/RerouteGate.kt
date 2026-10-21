package online.iamprettyfamous.navi.core.guidance

/**
 * When to ask the router for a new route.
 *
 * Ported from live.py:32-34, 129-145 and 300-303, where it lived beside the page
 * rather than in guidance. Two rules:
 *
 * - At most one per [everyS], so a rider who is off-route and staying off does
 *   not search once a second in a canyon.
 * - Never while a search is running. The slot is claimed BEFORE searching:
 *   `last_route_at` used to be stamped only when routing returned, so every fix
 *   during a slow search still passed the time check, asked again, and the
 *   requests queued behind the router's lock - "not frozen, but nothing updates".
 *
 * Call [begin] when the search starts and [end] when it returns, success or not.
 * The caller keeps the old route when a re-route fails: a stale route is worse
 * than a fresh one and far better than none (live.py:146-152).
 */
class RerouteGate(val everyS: Double = EVERY_S) {
    var routingNow: Boolean = false
        private set
    var lastRouteAt: Double = 0.0
        private set

    fun shouldAsk(offRouteConfirmed: Boolean, t: Double): Boolean =
        offRouteConfirmed && !routingNow && t - lastRouteAt >= everyS

    fun begin(t: Double) {
        routingNow = true
        lastRouteAt = t
    }

    fun end(t: Double) {
        routingNow = false
        lastRouteAt = t
    }

    companion object {
        /** live.py:34 REROUTE_EVERY. */
        const val EVERY_S = 12.0
    }
}
