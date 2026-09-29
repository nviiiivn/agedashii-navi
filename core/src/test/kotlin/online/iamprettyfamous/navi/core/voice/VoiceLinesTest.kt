package online.iamprettyfamous.navi.core.voice

import online.iamprettyfamous.navi.core.voice.VoiceLines.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against the names in her real Arnold pack (arnold-navi.zip, 19 clips), so a
 * rule that would refuse or misplay one of her files fails here first.
 */
class VoiceLinesTest {
    private val pack = listOf(
        "arrive.mp3", "continue.mp3", "dist-1500ft.mp3", "dist-200ft.mp3", "dist-400ft.mp3", "dist-800ft.mp3",
        "exit.mp3", "merge.mp3", "offroute.mp3", "rerouting.mp3", "sharp-left.mp3", "sharp-right.mp3",
        "slight-left.mp3", "slight-right.mp3", "speeding.mp3", "start.mp3", "turn-left.mp3", "turn-right.mp3", "uturn.mp3",
    )
    private val have: Set<String> = pack.mapNotNull { VoiceLines.parse(it).first }.toSet()

    private fun ft(feet: Double) = feet / VoiceLines.FEET_PER_M

    @Test
    fun `every file in her pack is taken - none refused`() {
        assertEquals(19, have.size, "refused: ${pack.filter { VoiceLines.parse(it).first == null }}")
    }

    @Test
    fun `names forgive case, spaces and underscores, and nothing else`() {
        assertEquals("turn-left" to ".mp3", VoiceLines.parse("Turn_Left.MP3"))
        assertEquals("slight-right" to ".wav", VoiceLines.parse("slight right.wav"))
        assertEquals("roundabout" to ".m4a", VoiceLines.parse("roundabout.m4a"))
        assertEquals("dist-0.5mile" to ".ogg", VoiceLines.parse("dist-0.5mile.ogg"))
        assertEquals("dist-2miles" to ".mp3", VoiceLines.parse("dist-2miles.mp3"))
        assertEquals("dist-quarter" to ".mp3", VoiceLines.parse("dist-quarter.mp3"))
        assertNull(VoiceLines.parse("turnleft.mp3").first, "not guessed at")
        assertNull(VoiceLines.parse("turn-left.txt").first)
        assertNull(VoiceLines.parse("dist-0ft.mp3").first)
        assertNull(VoiceLines.parse("dist-400.mp3").first, "a distance has a unit")
    }

    @Test
    fun `minion 4's distance grammar, exactly - accepted and refused`() {
        val accepted = mapOf(
            "dist-400ft" to "dist-400ft", "dist-1500ft" to "dist-1500ft", "dist-0.5mile" to "dist-0.5mile",
            "dist-1.5miles" to "dist-1.5miles", "dist-250.5ft" to "dist-250.5ft", "DIST 3MILES" to "dist-3miles",
            "dist_5_mile" to "dist-5mile",
        )
        for ((file, name) in accepted) assertEquals(name, VoiceLines.parse("$file.mp3").first, file)
        val refused = listOf(
            "dist-0ft", "dist-0.0mile", "dist-012ft", "dist-.5mile", "dist-5.ft", "dist-1e3ft",
            "dist-12km", "dist--5ft", "dist-ft", "dist-abcft",
        )
        for (file in refused) assertNull(VoiceLines.parse("$file.mp3").first, file)
    }

    @Test
    fun `distances read in feet`() {
        assertEquals(400.0, VoiceLines.distanceFeet("dist-400ft"))
        assertEquals(2640.0, VoiceLines.distanceFeet("dist-0.5mile"))
        assertEquals(10560.0, VoiceLines.distanceFeet("dist-2miles"))
        assertEquals(1320.0, VoiceLines.distanceFeet("dist-quarter"))
        assertEquals(2640.0, VoiceLines.distanceFeet("dist-half"))
    }

    @Test
    fun `the nearest clip she HAS, within a quarter either way, the shorter on a tie`() {
        assertEquals(1.25, VoiceLines.DISTANCE_BOUND)
        assertEquals("dist-200ft", VoiceLines.nearestDistance(ft(200.0), have))
        assertEquals("dist-400ft", VoiceLines.nearestDistance(ft(350.0), have), "350 is nearer 400 than 200 by ratio")
        assertNull(VoiceLines.nearestDistance(ft(300.0), have), "300 is 1.33 from 400 and 1.5 from 200: the phone says it")
        assertEquals("dist-200ft", VoiceLines.nearestDistance(ft(250.0), have), "exactly x1.25 is inside")
        assertNull(VoiceLines.nearestDistance(ft(251.0), have))
        assertEquals("dist-1500ft", VoiceLines.nearestDistance(ft(1875.0), have))
        assertNull(VoiceLines.nearestDistance(ft(5280.0), have), "two hundred for a mile away is a wrong instruction")
        assertNull(VoiceLines.nearestDistance(null, have))
        // A true tie, between two clips 1.25 x 1.25 apart, goes to the shorter.
        val tie = setOf("dist-400ft", "dist-625ft")
        assertEquals("dist-400ft", VoiceLines.nearestDistance(ft(500.0), tie))
    }

    @Test
    fun `her renamed set covers what minion 3 measured, and no further`() {
        // minion 5's pack: each clip says what its name says. At x1.25 they cover
        // 422-660, 1056-1650, 2112-3300 and 4224-6600 ft (minion 3).
        val set = setOf("dist-0.1mile", "dist-quarter", "dist-half", "dist-1mile")
        val want = mapOf(
            423.0 to "dist-0.1mile", 528.0 to "dist-0.1mile", 660.0 to "dist-0.1mile", 421.0 to null, 661.0 to null,
            800.0 to null, 1056.0 to "dist-quarter", 1320.0 to "dist-quarter", 1650.0 to "dist-quarter", 1651.0 to null,
            2112.0 to "dist-half", 3300.0 to "dist-half", 4224.0 to "dist-1mile", 6600.0 to "dist-1mile", 6601.0 to null,
        )
        for ((feet, clip) in want) assertEquals(clip, VoiceLines.nearestDistance(ft(feet), set), "$feet ft")
    }

    @Test
    fun `her line and her distance, then nothing of ours`() {
        val items = VoiceLines.playlist(
            "In 400 ft, Turn right onto 13th Street", "turn-right", "near", ft(400.0),
            "Turn right onto 13th Street.", "13th Street", imperial = true, have = have,
        )
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Clip("turn-right", "Turn right")), items)
    }

    @Test
    fun `a missing item falls back for that item alone`() {
        val far = VoiceLines.playlist(
            "In 1.0 mi, Turn right", "turn-right", "far", 1609.344, "Turn right", "", true, have,
        )
        assertEquals(listOf(Item.Speak("In 1.0 miles,"), Item.Clip("turn-right", "Turn right")), far)
        val noRoundabout = VoiceLines.playlist(
            "In 400 ft, Enter the roundabout", "roundabout", "near", ft(400.0), "Enter the roundabout", "", true, have,
        )
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Speak("Enter the roundabout")), noRoundabout)
    }

    @Test
    fun `none of hers applies - our whole sentence, unchanged`() {
        val say = "In 1.0 mi, Take the ferry"
        assertEquals(listOf(Item.Speak(say)), VoiceLines.playlist(say, null, "far", 1609.344, "Take the ferry", "", true, have))
        assertEquals(listOf(Item.Speak("x")), VoiceLines.playlist("x", "turn-left", "near", ft(400.0), "Turn left", "", true, emptySet()))
    }

    @Test
    fun `now is the maneuver alone`() {
        assertEquals(
            listOf(Item.Clip("turn-left", "Turn left")),
            VoiceLines.playlist("Turn left onto Grand", "turn-left", "now", ft(80.0), "Turn left onto Grand", "Grand", true, have),
        )
    }

    @Test
    fun `street names only when she turns them on, and never on arrival`() {
        val on = VoiceLines.playlist("x", "turn-left", "now", null, "Turn left onto Grand", "Grand Ave", true, have, streets = true)
        assertEquals(Item.Speak("onto Grand Ave"), on.last())
        val arrive = VoiceLines.playlist("x", "arrive", "now", null, "You have arrived", "Grand Ave", true, have, streets = true)
        assertTrue(arrive.none { it is Item.Speak && "onto" in it.text })
    }

    @Test
    fun `after her exit clip the side, after her roundabout clip the exit number`() {
        // Her one "exit" clip serves both sides and her one "roundabout" clip every exit.
        val right = VoiceLines.playlist(
            "x", "exit", "near", ft(400.0), "Take exit 2 on the right toward Oakland.", "", true, have,
        )
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Clip("exit", "Take exit 2 on the right toward Oakland"), Item.Speak("on the right")), right)
        val left = VoiceLines.playlist("x", "exit", "now", null, "Take the exit on the left.", "", true, have)
        assertEquals(listOf(Item.Clip("exit", "Take the exit on the left"), Item.Speak("on the left")), left)
        val round = VoiceLines.playlist(
            "x", "roundabout", "now", null, "Enter the roundabout and take the 2nd exit onto Main Street.", "Main Street", true, have + "roundabout",
        )
        assertEquals(listOf(Item.Clip("roundabout", "Enter the roundabout and take the 2nd exit"), Item.Speak("take the 2nd exit")), round)
    }

    @Test
    fun `the phone's own sentence already says it, so nothing follows`() {
        val say = "Take the exit on the left."
        assertEquals(listOf(Item.Speak(say)), VoiceLines.playlist(say, "exit", "now", null, say, "", true, have - "exit"))
        val distOnly = VoiceLines.playlist("x", "exit", "near", ft(400.0), say, "", true, have - "exit")
        assertEquals(listOf(Item.Clip("dist-400ft", "In 400 feet,"), Item.Speak("Take the exit on the left")), distOnly)
        assertNull(VoiceLines.afterHerLine("exit", "Take the exit."), "no side in the words: nothing invented")
        assertNull(VoiceLines.afterHerLine("turn-left", "Turn left on the right side"))
    }

    @Test
    fun `a refused re-route in words she can act on, and never 'find signal'`() {
        val outside = VoiceLines.refusal("no route: No suitable edges near location")
        assertEquals(listOf(Item.Speak("No route. You are outside the offline map. Ride back into it.")), outside)
        assertEquals(outside, VoiceLines.refusal("no route: {\"error_code\":171}"))
        assertEquals(listOf(Item.Speak("No new route yet. It will keep trying.")), VoiceLines.refusal("no route: No path could be found for input"))
    }

    @Test
    fun `a refusal is said once per off-route spell`() {
        val cues = VoiceLines.Cues()
        val dest = Any()
        cues.update(true, true, dest, true, false, 0.0)
        assertTrue(cues.refused())
        assertFalse(cues.refused(), "the retries need no voice")
        cues.update(true, true, dest, true, false, 12.0)
        assertFalse(cues.refused(), "still the same spell")
        cues.update(true, false, dest, false, false, 20.0)
        cues.update(true, true, dest, true, false, 30.0)
        assertTrue(cues.refused(), "a new spell may say it again")
    }

    @Test
    fun `situations - hers, the phone's, or for speeding nothing`() {
        assertEquals(listOf(Item.Clip("offroute", "Off route.")), VoiceLines.situation("offroute", have))
        assertEquals(listOf(Item.Speak("Re-routing.")), VoiceLines.situation("rerouting", emptySet()))
        assertEquals(emptyList(), VoiceLines.situation("speeding", emptySet()), "a robot nagging about speed is not what she asked for")
        assertEquals(VoiceLines.Source.SILENT, VoiceLines.source("speeding", emptySet()))
        assertEquals(VoiceLines.Source.YOURS, VoiceLines.source("speeding", have))
        assertEquals(VoiceLines.Source.PHONE, VoiceLines.source("roundabout", have))
    }

    @Test
    fun `cues fire on their edges only`() {
        val cues = VoiceLines.Cues()
        val dest = Any()
        assertEquals(listOf("start"), cues.update(true, false, dest, false, false, 0.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, false, 1.0), "the same destination is no new start")
        assertEquals(listOf("offroute"), cues.update(true, true, dest, false, false, 2.0))
        assertEquals(listOf("rerouting"), cues.update(true, true, dest, true, false, 3.0))
        assertEquals(emptyList(), cues.update(true, true, dest, true, false, 15.0), "once per off-route spell")
        assertEquals(listOf("speeding"), cues.update(true, false, dest, false, true, 20.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, false, 21.0))
        assertEquals(emptyList(), cues.update(true, false, dest, false, true, 30.0), "not again inside a minute")
        assertEquals(listOf("start"), cues.update(true, false, Any(), false, true, 90.0).filter { it == "start" })
    }
}
