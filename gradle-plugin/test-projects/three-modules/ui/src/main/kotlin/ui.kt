package expui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import excore.coreAccent

const val UI_MARK = "ui-0"

@Composable
fun UiMark(tick: Int) {
    println("RENDER_UI $UI_MARK tick=$tick")
    Text("UI $UI_MARK", color = coreAccent(), fontSize = 18.sp)
}
