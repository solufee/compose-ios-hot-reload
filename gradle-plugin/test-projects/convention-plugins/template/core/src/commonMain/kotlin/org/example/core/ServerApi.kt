package org.example.core

import kotlinx.rpc.annotations.Rpc

@Rpc
interface ServerApi {
    suspend fun ping(host: String): String
}

/** Names of the calls in the descriptor the rpc compiler plugin generates (it fails without the plugin). */
@OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)
fun rpcCalls(): String = kotlinx.rpc.descriptor.serviceDescriptorOf<ServerApi>().callables.keys.sorted().joinToString(",")
