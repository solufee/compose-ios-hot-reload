package expui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

const val UI_MARK = "ui-mf2"

@Composable
fun UiMark(tick: Int) {
    println("RENDER_UI $UI_MARK tick=$tick")
    Text("UI $UI_MARK", color = Color(0xFF7FD1AE), fontSize = 18.sp)
}

val warmTimingUi1: Int = 1
