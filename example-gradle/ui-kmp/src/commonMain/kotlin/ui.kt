package expui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

const val UI_MARK = "ui-kmp"

// Declared here, implemented in iosMain.
expect fun platformLabel(): String

expect class Greeter() {
    fun greet(name: String): String
}

fun commonLabel(): String = "common-1"

@Composable
fun UiMark(tick: Int) {
    val line = "${commonLabel()} ${platformLabel()} ${Greeter().greet("kmp")}"
    println("RENDER_UI $UI_MARK tick=$tick KMP[$line]")
    Text("UI $UI_MARK $line", color = Color(0xFF7FD1AE), fontSize = 18.sp)
}
