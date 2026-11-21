package org.example.kmphr

import kotlinx.serialization.json.Json

fun probe(): String = runCatching {
    val k = org.koin.mp.KoinPlatform.getKoin()
    val g = k.get<org.example.kmphr.di.Greeter>().greet()
    val server = k.get<org.example.core.ServerStore>().roundTrip()
    val extra = k.getOrNull<org.example.core.CoreExtra>()?.tag() ?: "none"
    val nav = Json.encodeToString(NavKey.serializer(), NavKey.Detail(3))
    val rpc = org.example.core.rpcCalls()
    "greet=$g extra=$extra rpc=$rpc server=$server nav=$nav"
}.getOrElse { "ERR:" + it::class.simpleName + ":" + it.message }
