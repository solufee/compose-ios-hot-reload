package org.example.core

import kotlinx.serialization.json.Json
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class ServerStore {
    private val json = Json { encodeDefaults = true }
    fun roundTrip(): String {
        val t = json.encodeToString(SavedServer.serializer(), SavedServer("h1", "n1"))
        return t + "/" + json.decodeFromString(SavedServer.serializer(), t)
    }
}

// not a definition yet: the "add a single" step puts @Single on it
class CoreExtra {
    fun tag(): String = "k1"
}

@Module
@ComponentScan("org.example.core")
class CoreModule
