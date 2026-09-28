package online.iamprettyfamous.navi.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import online.iamprettyfamous.navi.core.theme.TypeScale
import online.iamprettyfamous.navi.ui.ButtonColumn
import online.iamprettyfamous.navi.core.dash.Anchor
import online.iamprettyfamous.navi.core.dash.DashState
import online.iamprettyfamous.navi.core.dash.Hud
import online.iamprettyfamous.navi.core.dash.Layout
import online.iamprettyfamous.navi.core.guidance.LimitSource
import online.iamprettyfamous.navi.core.guidance.SpeedLimit
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
        leanDeg = 24.0, tiltDeg = 24.0, pitchDeg = 4.0, gLat = 0.45, gLong = -0.3, altM = 152.0, climbMps = 1.2,
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

    @Composable
    private fun Scene(state: DashState, layout: Layout, theme: String, wide: Boolean) {
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
            HudOverlay(Hud.plan(state, layout, wide, if (wide) Anchor.RIGHT else Anchor.BOTTOM), palette(theme))
            Buttons(theme)
        }
    }

    /**
     * The button column as MainActivity lays it out, from the same ButtonColumn
     * numbers, mid-ride: record with its line beside it, then four buttons. A
     * gauge under any of these is the collision the Flet app shipped.
     */
    @Composable
    private fun Buttons(theme: String) {
        val p = palette(theme)
        @Composable
        fun button() = Box(
            Modifier.size(TypeScale.TOUCH_DP.dp).background(p.surface, CircleShape).border(1.dp, p.border, CircleShape),
        )
        Column(
            Modifier.safeDrawingPadding().padding(ButtonColumn.EDGE_DP.dp),
            verticalArrangement = Arrangement.spacedBy(ButtonColumn.GAP_DP.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonColumn.BESIDE_DP.dp)) {
                button()
                BasicText(
                    "Recording 0:12:34",
                    Modifier.background(p.surface, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = TextStyle(color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            repeat(4) { button() }
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
