package org.example.feature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

expect fun featureTag(): String

fun featureLine(): String = "feature-1"

@Composable
fun FeatureBadge() {
    Text("Feature: ${featureLine()} | ${featureTag()}")
}
