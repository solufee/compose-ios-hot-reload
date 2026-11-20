package org.example.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import org.example.core.CoreBadge
import org.example.feature.FeatureBadge
import org.example.feature.featureLine

fun appLine(): String = "app-1"

@Composable
fun App() {
    MaterialTheme {
        var dialogOpen by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer).safeContentPadding().fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("App: ${appLine()} | ${featureLine()}")
            FeatureBadge()
            CoreBadge()
            Button(onClick = { dialogOpen = true }) { Text("Open dialog") }
            if (dialogOpen) {
                Dialog(onDismissRequest = { dialogOpen = false }) {
                    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                        Text("Dialog: dlg-1")
                        CoreBadge()
                        Button(onClick = { dialogOpen = false }) { Text("Close dialog") }
                    }
                }
            }
        }
    }
}
