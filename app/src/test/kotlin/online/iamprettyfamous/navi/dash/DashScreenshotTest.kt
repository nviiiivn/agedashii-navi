package online.iamprettyfamous.navi.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.DashState
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.core.dash.TurnBanner
import online.iamprettyfamous.navi.core.guidance.Guidance
import online.iamprettyfamous.navi.core.guidance.Status
import online.iamprettyfamous.navi.core.guidance.LimitSource
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
import online.iamprettyfamous.navi.ui.ButtonColumnLayout
import online.iamprettyfamous.navi.ui.TopStack
import online.iamprettyfamous.navi.ui.Chip
import online.iamprettyfamous.navi.ui.FollowGlyph
import online.iamprettyfamous.navi.ui.RoundButton
import online.iamprettyfamous.navi.ui.palette
import online.iamprettyfamous.navi.ui.rgb
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every layout, in the three themes that matter most for reading it: Sunlight
 * (direct sun), Night (most rides) and Red night (dark-adapted). DashContrastTest
 * proves the numbers; these are for eyes, and where one looks wrong while the
 * numbers pass, the picture wins.
 *
 * `./gradlew :app:recordRoborazziDebug` writes them to app/build/outputs/roborazzi/dash.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Robolectric 4.17 wants Java 21 for SDK 35 and up, and the build JDK is 17.
// Compose lays these out itself; font rasterising may differ slightly from 36.
@Config(sdk = [34])
class DashScreenshotTest {
    /** 65 mph past a posted 55 (over is limit + 7), leaned into a right-hander, a shift due: every state lit at once. */
    private val riding = DashState(
        imperial = true, machine = "cb750", speedMs = 29.0, limit = SpeedLimit(55, LimitSource.POSTED, "primary"),
        leanDeg = 24.0, tiltDeg = 24.0, pitchDeg = 4.0, gLat = 0.45, gLong = -0.3, altM = 378.0, climbSinceStartM = 97.5, climbMps = 1.2,
        rpm = 6200.0, gear = 4, shift = "up", bands = listOf("low" to 0.6, "mid" to 0.35, "high" to 0.15),
        levelDb = -18.0, levelPeak = -10.0,
    )

    /**
     * The darkest and lightest map fill each theme can put under the glass - the
     * same pairs as DashContrastTest.mapFills (minion 3's measurements) - split
     * across the screen so every gauge is seen on both extremes.
     */
    private val fills = mapOf(
        "sunlight" to (0xe6ecf6 to 0xffffff),
        "night" to (0x06080b to 0x0d252e),
        "redshift" to (0x000000 to 0x35130f),
    )

    private val layouts = Layout.entries.filter { it != Layout.OFF }

    private val turn = Status(
        ok = true, instruction = "Turn right onto 12th Street", street = "12th Street",
        icon = Guidance.turnIcon("Turn right onto 12th Street"), turnInM = 122.0, eta = "10:42",
    )

    /** A turn over the ribbon, off-route over the corners, and no instruction over the gunsight and the column. */
    private val banners = linkedMapOf(
        "turn" to (Layout.RIBBON to TurnBanner.plan(turn, true)),
        "offroute" to (Layout.CORNERS to TurnBanner.plan(turn.copy(offRoute = true, offRouteM = 45.7), true)),
        "blank" to (Layout.GUNSIGHT to TurnBanner.plan(turn.copy(instruction = "", icon = "straight", turnInM = null, street = ""), true)),
        "column" to (Layout.COLUMN to TurnBanner.plan(turn.copy(instruction = "Make a U-turn", icon = "u_turn"), true)),
    )

    @Composable
    private fun Scene(state: DashState, layout: Layout, theme: String, wide: Boolean, banner: TurnBanner.Plan? = null) {
        val (dark, light) = fills.getValue(theme)
        Box(Modifier.fillMaxSize()) {
            // Portrait splits left/right so the bottom ribbon crosses both;
            // landscape splits top/bottom so the side column does.
            if (wide) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(rgb(dark)))
                    Box(Modifier.weight(1f).fillMaxWidth().background(rgb(light)))
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(rgb(dark)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(rgb(light)))
                }
            }
            val p = palette(theme)
            var topStack by remember { mutableStateOf(TopStack()) }
            HudOverlay(Hud.plan(state, layout, wide, if (wide) Anchor.RIGHT else Anchor.BOTTOM), p, top = topStack)
            // The real column and lines, mid-ride, with a status line up too: the
            // worst case for the top of the screen. A gauge under any of it is the
            // collision the Flet app shipped.
            ButtonColumnLayout(
                onTopStack = { topStack = it },
                banner = banner?.let { plan -> { TurnBannerView(plan, p) } },
                lines = {
                    Chip("● REC 12:34 · 3.2 mi · 1234 rows", p, edge = p.bad, ink = p.bad)
                    Chip("GPS lost 12 s ago", p)
                },
            ) {
                RoundButton("☰", p) {}
                RoundButton("■", p, p.bad) {}
                RoundButton(p, {}) { FollowGlyph(p.accent) }
            }
        }
    }

    private fun shoot(wide: Boolean) {
        val orientation = if (wide) "land" else "port"
        for (theme in fills.keys) {
            for (layout in layouts) {
                captureRoboImage("build/outputs/roborazzi/dash/$theme-$orientation-${layout.key}.png") {
                    Scene(riding, layout, theme, wide)
                }
            }
            // Following a route: the banner over each kind of layout, with the lines under it.
            for ((name, pair) in banners) {
                val (layout, plan) = pair
                captureRoboImage("build/outputs/roborazzi/dash/$theme-$orientation-banner-$name.png") {
                    Scene(riding, layout, theme, wide, plan)
                }
            }
            // Parked, no sensors: every gauge must show a dash, never a number.
            captureRoboImage("build/outputs/roborazzi/dash/$theme-$orientation-empty.png") {
                Scene(DashState(), Layout.STRIP, theme, wide)
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun portrait() = shoot(wide = false)

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscape() = shoot(wide = true)
}
