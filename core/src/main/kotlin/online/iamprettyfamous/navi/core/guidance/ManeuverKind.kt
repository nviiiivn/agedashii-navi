package online.iamprettyfamous.navi.core.guidance

/**
 * Which maneuver a prompt is about, as the KEY her recorded clips are filed
 * under - never the words. AGEDASHII settled the names (2026-09-28) and she
 * may already be recording against them, so they are a contract: the Kotlin
 * app, the Flet app and her audio files all use exactly these, and a name is
 * never invented here. Text is what is shown and what TTS says; the kind picks
 * the clip. Keeping them apart is what lets one set of her files work in both
 * apps.
 *
 * There is ONE classifier: Valhalla's. The Kotlin app reads its maneuver
 * `type`; the Flet router, which has no Valhalla maneuvers, classifies its
 * bearing change with [fromTurnDegree], Valhalla 3.6.3's own table - so the two
 * apps cannot disagree about slight and sharp in front of her.
 */
object ManeuverKind {
    const val TURN_LEFT = "turn-left"
    const val TURN_RIGHT = "turn-right"
    const val SLIGHT_LEFT = "slight-left"
    const val SLIGHT_RIGHT = "slight-right"
    const val SHARP_LEFT = "sharp-left"
    const val SHARP_RIGHT = "sharp-right"
    const val UTURN = "uturn"
    const val CONTINUE = "continue"
    const val MERGE = "merge"
    const val EXIT = "exit"
    const val ARRIVE = "arrive"

    /**
     * The twelfth, added by AGEDASHII 2026-09-28: optional. If she records it,
     * it plays; if not, the words go to the phone's voice as before - a name
     * costs nothing and only ever improves on silence.
     */
    const val ROUNDABOUT = "roundabout"

    /** From her other clips: the route starting. Not a maneuver she is told to make. */
    const val START = "start"

    /** The maneuver clips, in AGEDASHII's order: her eleven, then roundabout. */
    val MANEUVERS = listOf(
        TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, CONTINUE, MERGE, EXIT, ARRIVE,
        ROUNDABOUT,
    )

    /**
     * Valhalla's maneuver `type` (its turn-by-turn API reference, kNone = 0 to
     * kBuildingExit = 43) to a clip name, or null where no clip fits - then the
     * words are spoken by TTS rather than a wrong clip played. Entering a
     * roundabout (26) is [ROUNDABOUT]; leaving one (27) is null - the clip for
     * going in would be wrong on the way out, so the words say which exit.
     * Null for ferries, transit and indoor steps: she rides a motorcycle.
     *
     * Ramps are not exits: Valhalla keeps kExitRight/Left (20, 21, leaving a
     * highway) apart from kRampRight/Left (18, 19, often ONTO one), and an
     * on-ramp announced as an exit is wrong. A ramp is its shape - slight to
     * its side, or straight on.
     */
    fun fromValhalla(type: Int?): String? = when (type) {
        1, 2, 3 -> START
        4, 5, 6 -> ARRIVE
        7, 8, 17, 22 -> CONTINUE // becomes, continue, ramp straight, stay straight
        9, 18, 23 -> SLIGHT_RIGHT // slight right, ramp right, stay right
        10 -> TURN_RIGHT
        11 -> SHARP_RIGHT
        12, 13 -> UTURN
        14 -> SHARP_LEFT
        15 -> TURN_LEFT
        16, 19, 24 -> SLIGHT_LEFT // slight left, ramp left, stay left
        20, 21 -> EXIT
        25, 37, 38 -> MERGE
        26 -> ROUNDABOUT
        else -> null
    }

    /**
     * A turn by its angle - clockwise degrees from straight on, as Valhalla
     * measures turn_degree - in Valhalla 3.6.3's own bands (src/baldr/turn.cc,
     * make_turn_type_lut, read from the tag 2026-09-28): 0-10 and 350-359
     * straight, 11-44 slight right, 45-135 right, 136-159 sharp right, 160-200
     * reverse, 201-224 sharp left, 225-315 left, 316-349 slight left.
     */
    fun fromTurnDegree(degrees: Int): String = when (((degrees % 360) + 360) % 360) {
        in 0..10, in 350..359 -> CONTINUE
        in 11..44 -> SLIGHT_RIGHT
        in 45..135 -> TURN_RIGHT
        in 136..159 -> SHARP_RIGHT
        in 160..200 -> UTURN
        in 201..224 -> SHARP_LEFT
        in 225..315 -> TURN_LEFT
        else -> SLIGHT_LEFT // 316..349
    }
}
