package org.example.kmphr

import kotlinx.serialization.Serializable

@Serializable
sealed interface NavKey {
    @Serializable
    data object Home : NavKey

    @Serializable
    data class Detail(val id: Int) : NavKey
}
