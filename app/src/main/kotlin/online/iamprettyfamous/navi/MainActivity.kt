package online.iamprettyfamous.navi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Night theme bg and text, from the spec's palette table.
            Box(
                Modifier.fillMaxSize().background(Color(0xFF06080B)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Agedashii Navi",
                    style = TextStyle(color = Color(0xFFF2F6FB), fontSize = 28.sp),
                )
            }
        }
    }
}
