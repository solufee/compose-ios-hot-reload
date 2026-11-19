package expui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

const val UI2_MARK = "ui2-mf2"

@Composable
fun UiMark2(tick: Int) {
    println("RENDER_UI2 $UI2_MARK tick=$tick")
    Text("UI2 $UI2_MARK", color = Color(0xFFFFB4A2), fontSize = 18.sp)
}
