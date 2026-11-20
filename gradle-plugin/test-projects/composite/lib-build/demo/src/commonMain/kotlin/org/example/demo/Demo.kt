package org.example.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.core.CoreBadge

fun demoLine(): String = "demo-1"

@Composable
fun Demo() {
    MaterialTheme {
        Column(Modifier.safeContentPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Demo: ${demoLine()}")
            CoreBadge()
        }
    }
}
