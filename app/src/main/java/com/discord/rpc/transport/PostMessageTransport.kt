package com.discord.rpc.transport

import com.discord.rpc.RpcCloseCodes
import com.discord.rpc.RpcLog
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcOpcodes
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcSocketSource
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

interface RpcTransportListener {
    fun onConnect(socket: RpcSocket)

    fun onRequest(socket: RpcSocket, payload: JSONObject)

    fun onDisconnect(socket: RpcSocket, code: Int?, message: String?)
}

fun interface ValidateSocketClient {
    fun validate(socket: RpcSocket, origin: String?, clientId: String?): Observable<Unit>
}

/** Sockets are keyed by frame id, not origin: all activities of one application share an origin. */
class PostMessageTransport(
    private val validateSocketClient: ValidateSocketClient,
    private val onFrameHandled: ((JSONObject, RpcSocket) -> Unit)? = null,
) {
    private val sockets = linkedMapOf<String, PostMessageProxySocket>()
    private val availableFrames = linkedSetOf<String>()
    private val listeners = mutableListOf<RpcTransportListener>()

    fun addListener(listener: RpcTransportListener) {
        listeners += listener
    }

    fun handleFrameMount(frameId: String) {
        availableFrames += frameId
    }

    fun handleFrameUnmount(frameId: String) {
        availableFrames -= frameId
        val socket = sockets[frameId] ?: return
        disconnectSocket(socket, RpcCloseCodes.CLOSE_NORMAL, "iFrame gone", silent = true)
    }

    fun handleMessage(
        eventData: Any?,
        source: RpcSocketSource,
        postMessageToRpcClient: PostMessageToRpcClient,
    ) {
        val existing = sockets[source.frameId]
        try {
            routeEvent(existing, source, eventData, postMessageToRpcClient)
        } catch (e: RpcError) {
            if (e.errorCode == RpcErrors.INVALID_PAYLOAD) throw e
            RpcLog.log("transport error: ${e.code} ${e.message}")
            if (existing != null) {
                disconnectSocket(existing, e.code, e.message.orEmpty(), silent = true)
            } else {
                postClose(source, e.code, e.message.orEmpty(), postMessageToRpcClient)
            }
        }
    }

    private fun routeEvent(
        existing: PostMessageProxySocket?,
        source: RpcSocketSource,
        eventData: Any?,
        postMessageToRpcClient: PostMessageToRpcClient,
    ) {
        val tuple = eventData as? JSONArray ?: run {
            RpcLog.log("dropped non-array frame: $eventData")
            return
        }
        RpcLog.log("recv ${tuple.opt(0)} ${RpcLog.redact(tuple.opt(1))}")
        when (tuple.opt(0)) {
            RpcOpcodes.HANDSHAKE -> {
                if (existing != null) {
                    throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Already connected")
                }
                handleHandshake(source, tuple.opt(1), postMessageToRpcClient)
            }
            RpcOpcodes.FRAME -> {
                if (existing == null) throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Not connected")
                handleFrame(source, existing, tuple.opt(1))
            }
            RpcOpcodes.CLOSE -> {
                if (existing == null) throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Not connected")
                handleClose(existing, tuple.opt(1))
            }
            else -> throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Invalid opcode")
        }
    }

    private fun handleFrame(source: RpcSocketSource, socket: PostMessageProxySocket, data: Any?) {
        if (source.origin != socket.source.origin) {
            throw RpcError.close(RpcCloseCodes.INVALID_ORIGIN, "Origin has changed")
        }
        val payload = when (data) {
            is JSONObject -> data
            is String -> runCatching { JSONObject(data) }.getOrElse {
                throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Payload not recognized encoding")
            }
            else -> throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Payload not recognized encoding")
        }
        onFrameHandled?.invoke(payload, socket)
        listeners.forEach { it.onRequest(socket, payload) }
    }

    private fun handleHandshake(
        source: RpcSocketSource,
        data: Any?,
        postMessageToRpcClient: PostMessageToRpcClient,
    ) {
        val payload = data as? JSONObject
            ?: throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "\"value\" must be an object")
        val version = payload.opt("v")
        if (version !is Number || version.toInt() != 1) {
            throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "child \"v\" fails because [\"v\" is required]")
        }
        val encoding = if (payload.isNull("encoding")) null else payload.opt("encoding") as? String
        if (payload.has("encoding") && encoding != "json") {
            throw RpcError.close(
                RpcCloseCodes.CLOSE_UNSUPPORTED,
                "child \"encoding\" fails because [\"encoding\" must be one of [json]]",
            )
        }
        val clientId = payload.opt("client_id") as? String
            ?: throw RpcError.close(
                RpcCloseCodes.CLOSE_UNSUPPORTED,
                "child \"client_id\" fails because [\"client_id\" is required]",
            )
        val frameId = payload.opt("frame_id") as? String
            ?: throw RpcError.close(
                RpcCloseCodes.CLOSE_UNSUPPORTED,
                "child \"frame_id\" fails because [\"frame_id\" is required]",
            )

        if (frameId != source.frameId || source.frameId !in availableFrames) {
            throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "Unrecognized iframe ID $frameId")
        }

        val socket = PostMessageProxySocket(
            source = source,
            version = version.toInt(),
            encoding = encoding ?: "json",
            postMessageToRpcClient = postMessageToRpcClient,
            postClose = ::postClose,
        )

        validateSocketClient.validate(socket, source.origin, clientId).subscribe({
            if (source.frameId !in availableFrames) {
                postClose(
                    source,
                    RpcCloseCodes.CLOSE_UNSUPPORTED,
                    "Unrecognized iframe ID ${source.frameId}",
                    postMessageToRpcClient,
                )
                return@subscribe
            }
            sockets[source.frameId] = socket
            availableFrames -= source.frameId
            socket.authorization.scopes += RpcScopes.EMBEDDED_APP
            listeners.forEach { it.onConnect(socket) }
        }, { failure ->
            val error = failure as? RpcError
            postClose(
                source,
                error?.code ?: RpcCloseCodes.CLOSE_ABNORMAL,
                failure.message.orEmpty(),
                postMessageToRpcClient,
            )
        })
    }

    private fun handleClose(socket: PostMessageProxySocket, data: Any?) {
        val payload = data as? JSONObject
            ?: throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "\"value\" must be an object")
        val code = payload.opt("code")
        if (code !is Number || code.toInt() !in RpcCloseCodes.ALL) {
            throw RpcError.close(RpcCloseCodes.CLOSE_UNSUPPORTED, "child \"code\" fails because [\"code\" is required]")
        }
        val message = if (payload.isNull("message")) null else payload.opt("message") as? String
        disconnectSocket(socket, code.toInt(), message ?: "Unknown")
    }

    private fun disconnectSocket(
        socket: PostMessageProxySocket,
        code: Int,
        message: String,
        silent: Boolean = false,
    ) {
        listeners.forEach {
            if (silent) it.onDisconnect(socket, null, null) else it.onDisconnect(socket, code, message)
        }
        socket.close(code, message)
        sockets.entries.removeAll { it.value === socket }
    }

    private companion object {
        fun postClose(
            source: RpcSocketSource,
            code: Int,
            message: String,
            postMessageToRpcClient: PostMessageToRpcClient,
        ) {
            postMessageToRpcClient(
                JSONArray()
                    .put(RpcOpcodes.CLOSE)
                    .put(JSONObject().put("code", code).put("message", message)),
                source.origin,
            )
        }
    }
}
