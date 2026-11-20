package org.example.core

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.core.res.Res
import org.example.core.res.core_title
import org.example.util.utilLine
import org.jetbrains.compose.resources.stringResource

expect fun coreTag(): String

fun coreLine(): String = "core-1 " + utilLine()

@Composable
fun CoreBadge() {
    Text("Core: ${stringResource(Res.string.core_title)} | ${coreTag()} | ${coreLine()}")
}
