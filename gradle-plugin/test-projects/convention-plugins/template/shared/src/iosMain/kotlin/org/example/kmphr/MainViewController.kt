package org.example.kmphr

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.plugin.module.dsl.startKoin

fun MainViewController(): platform.UIKit.UIViewController {
    if (org.koin.mp.KoinPlatform.getKoinOrNull() == null) {
        startKoin<org.example.kmphr.di.KoinApp>()
    }
    return ComposeUIViewController { App() }
}
