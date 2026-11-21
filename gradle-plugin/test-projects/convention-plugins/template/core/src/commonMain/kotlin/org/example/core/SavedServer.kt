package org.example.core

import kotlinx.serialization.Serializable

@Serializable
data class SavedServer(val host: String, val name: String)
