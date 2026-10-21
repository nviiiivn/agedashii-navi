package online.iamprettyfamous.navi.core.dash

import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.ManeuverKind
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.theme.Role

/**
 * The turn banner: the instruction, the distance to it, and off-route - the
 * thing she reads at 60 mph. Planned from the Follower's [Status], the same
 * maneuver and timing the voice uses, so the two never disagree about WHICH
 * turn - but not the same WORDS: the voice is her own recorded character lines
 * ("GO LEFT IF YOU WANT TO LIVE"), platform TTS beneath, and diverges from this
 * text on purpose. Nothing here may assume the banner says what is spoken.
 * ride.py's turn_band, with its two inventions removed ([PYTHON_WRONG]).
 *
 * A solid band on the theme's surface, never glass: what the map paints under
 * it cannot move its contrast, which DashContrastTest asserts on the surface.
 */
object TurnBanner {
    /** The shapes a rider can tell apart at a glance - Guidance.turnIcon's names. */
    enum class Glyph { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN, FLAG }

    /** The next turn: the one interactive colour. Off-route is the alarm, so it is BAD. */
    val TURN = Role.ACCENT
    val OFF_ROUTE = Role.BAD
    val TITLE = Role.TEXT

    /** Distance and street, ETA's label: the second weight, through DashColours.textRole. */
    val DETAIL = Role.TEXT2
    val ETA = Role.TEXT

    const val OFF_ROUTE_TITLE = "off route"
    const val ARRIVED_TITLE = "arrived"
    const val ETA_LABEL = "arrive"

    /**
     * Where ride.py is WRONG, not merely different: it asserted what it did not
     * know - the same ruling as the empty dash.
     */
    val PYTHON_WRONG: Map<String, String> = linkedMapOf(
        "instruction" to "ride.py is wrong here: with no instruction it showed \"continue\"; we show a dash",
        "glyph" to "ride.py is wrong here: turn_icon(\"\") is \"straight\", so no instruction drew a straight-on " +
            "arrow; we draw no glyph",
    )

    data class Plan(
        val glyph: Glyph?,
        val glyphRole: Role,
        val title: String,
        val detail: String,
        val eta: String,
        val offRoute: Boolean,
    ) {
        fun texts(): List<String> = listOf(title, detail, eta, ETA_LABEL)
    }

    /** The banner for [status], or null - no route, no banner. [destination] is the name shown on arrival. */
    fun plan(status: Status?, imperial: Boolean, destination: String? = null): Plan? {
        if (status == null || !status.ok) return null
        // The shape from the same classifier that picks her clip, so the arrow
        // and the voice cannot disagree; the words' icon only where the kind has
        // no side (exit, merge) or there is none.
        val glyph = if (status.instruction.isBlank()) null else glyphOfKind(status.kind) ?: glyphOf(status.icon)
        return when {
            status.offRoute -> Plan(
                glyph, OFF_ROUTE, OFF_ROUTE_TITLE,
                Guidance.distanceText(status.offRouteM, imperial) + " from the line - re-routing",
                status.eta, offRoute = true,
            )
            status.arrived -> Plan(
                glyph, TURN, ARRIVED_TITLE, destination?.takeIf { it.isNotBlank() } ?: Gauges.DASH,
                status.eta, offRoute = false,
            )
            else -> Plan(
                glyph, TURN, status.instruction.ifBlank { Gauges.DASH },
                listOf(Guidance.distanceText(status.turnInM, imperial), status.street).filter { it.isNotBlank() }.joinToString(" · "),
                status.eta, offRoute = false,
            )
        }
    }

    fun glyphOfKind(kind: String?): Glyph? = when (kind) {
        ManeuverKind.CONTINUE -> Glyph.STRAIGHT
        ManeuverKind.TURN_LEFT -> Glyph.LEFT
        ManeuverKind.TURN_RIGHT -> Glyph.RIGHT
        ManeuverKind.SLIGHT_LEFT -> Glyph.SLIGHT_LEFT
        ManeuverKind.SLIGHT_RIGHT -> Glyph.SLIGHT_RIGHT
        ManeuverKind.SHARP_LEFT -> Glyph.SHARP_LEFT
        ManeuverKind.SHARP_RIGHT -> Glyph.SHARP_RIGHT
        ManeuverKind.UTURN -> Glyph.U_TURN
        ManeuverKind.ARRIVE -> Glyph.FLAG
        else -> null
    }

    fun glyphOf(icon: String): Glyph? = when (icon) {
        "straight" -> Glyph.STRAIGHT
        "left" -> Glyph.LEFT
        "right" -> Glyph.RIGHT
        "slight_left" -> Glyph.SLIGHT_LEFT
        "slight_right" -> Glyph.SLIGHT_RIGHT
        "sharp_left" -> Glyph.SHARP_LEFT
        "sharp_right" -> Glyph.SHARP_RIGHT
        "u_turn" -> Glyph.U_TURN
        "flag" -> Glyph.FLAG
        else -> null
    }
}
