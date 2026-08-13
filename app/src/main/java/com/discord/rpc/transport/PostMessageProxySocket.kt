package com.discord.rpc.transport

import com.discord.rpc.RpcCloseCodes
import com.discord.rpc.RpcLog
import com.discord.rpc.RpcError
import com.discord.rpc.RpcOpcodes
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcSocketSource
import org.json.JSONArray
import org.json.JSONObject

typealias PostMessageToRpcClient = (JSONArray, String) -> Unit

class PostMessageProxySocket(
    source: RpcSocketSource,
    version: Int,
    encoding: String,
    private val postMessageToRpcClient: PostMessageToRpcClient,
    private val postClose: (RpcSocketSource, Int, String, PostMessageToRpcClient) -> Unit,
) : RpcSocket(source, version, encoding) {

    private var closed = false

    init {
        when (encoding) {
            "json" -> Unit
            "etf" -> throw RpcError.close(RpcCloseCodes.INVALID_ENCODING, "Erlpack cannot be used on this client")
            else -> throw RpcError.close(RpcCloseCodes.INVALID_ENCODING, "Invalid Encoding: $encoding")
        }
    }

    override fun send(data: JSONObject) {
        RpcLog.log("send ${RpcLog.redact(data)}")
        postMessageToRpcClient(JSONArray().put(RpcOpcodes.FRAME).put(data), source.origin)
    }

    /** A postMessage channel cannot be closed; this only asks the app to restart the connection. */
    override fun close(code: Int, message: String) {
        if (!closed) postClose(source, code, message, postMessageToRpcClient)
        closed = true
    }
}
