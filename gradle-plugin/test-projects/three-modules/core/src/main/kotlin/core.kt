package excore

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

const val CORE_MARK = "core-0"

/** Used by :ui, so :ui really depends on :core. */
fun coreAccent(): Color = Color(0xFFB4C8FF)

@Composable
fun CoreMark(tick: Int) {
    println("RENDER_CORE $CORE_MARK tick=$tick")
    Text("CORE $CORE_MARK", color = coreAccent(), fontSize = 18.sp)
}
