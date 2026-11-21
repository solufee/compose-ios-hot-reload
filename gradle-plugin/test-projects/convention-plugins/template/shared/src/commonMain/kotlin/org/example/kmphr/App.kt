package org.example.kmphr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val UI_TAG = "u1"

@Composable
fun App() {
    val p = probe()
    println("BOTAS ui=$UI_TAG $p")
    MaterialTheme {
        Column(Modifier.safeContentPadding().fillMaxSize()) {
            Text("BOTAS ui=$UI_TAG")
            Text(p)
        }
    }
}
