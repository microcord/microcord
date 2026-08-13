package com.discord.rpc

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

const val RPC_VERSION = 1

object RpcTransportTypes {
    const val IPC = "ipc"
    const val WEBSOCKET = "ws"
    const val HTTP = "http"
    const val POST_MESSAGE = "post_message"
}

/** [origin] is the postMessage target and the security check. */
data class RpcSocketSource(
    val type: String,
    val origin: String,
    val frameId: String,
)

class RpcAuthorization {
    var authing: Boolean = false
    var accessToken: String? = null
    var expires: Long = 0L
    val scopes: MutableList<String> = mutableListOf()
}

class RpcApplication {
    var id: Long? = null
    var name: String? = null
    var icon: String? = null
    var parentId: Long? = null
}

abstract class RpcSocket(
    val source: RpcSocketSource,
    val version: Int,
    val encoding: String,
) {
    val id: String = "rpc-${NEXT_ID.getAndIncrement()}"
    val authorization = RpcAuthorization()
    val application = RpcApplication()

    @Volatile
    var aborted: Boolean = false
        private set

    val transport: String get() = source.type

    init {
        if (version > RPC_VERSION || version < 1) {
            throw RpcError.close(RpcCloseCodes.INVALID_VERSION, "Invalid Version: $version")
        }
    }

    abstract fun send(data: JSONObject)

    abstract fun close(code: Int, message: String)

    fun abort() {
        aborted = true
    }

    private companion object {
        val NEXT_ID = AtomicLong(1)
    }
}
