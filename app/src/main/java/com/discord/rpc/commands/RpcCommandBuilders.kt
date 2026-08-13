package com.discord.rpc.commands

import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommandContext
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcObject
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcTransportTypes
import org.json.JSONObject
import rx.Observable

fun rpcCommand(
    scope: RpcScopeConfig? = null,
    validation: RpcObject? = null,
    handler: (RpcCommandContext) -> Observable<Any?>,
): RpcCommand = object : RpcCommand {
    override val scope = scope

    override fun validate(args: JSONObject) {
        validation?.assert(args)
    }

    override fun handle(context: RpcCommandContext) = handler(context)
}

fun syncRpcCommand(
    scope: RpcScopeConfig? = null,
    validation: RpcObject? = null,
    handler: (RpcCommandContext) -> Any?,
): RpcCommand = rpcCommand(scope, validation) { context ->
    Observable.fromCallable { handler(context) }
}

val unsupportedCommand: RpcCommand = syncRpcCommand {
    throw RpcError.error(RpcErrors.INVALID_COMMAND, "Unsupported command: ${it.cmd}")
}

val deprecatedCommand: RpcCommand = syncRpcCommand {
    throw RpcError.error(RpcErrors.INVALID_COMMAND, "Deprecated command: ${it.cmd}")
}

/** The unbalanced quote is the reference's; activity SDKs match on the exact string. */
fun validatePostMessageTransport(transport: String) {
    if (transport != RpcTransportTypes.POST_MESSAGE) {
        throw RpcError.error(RpcErrors.INVALID_COMMAND, "command not available from \"$transport transport")
    }
}

fun validateTransportType(transport: String) {
    if (transport != RpcTransportTypes.IPC && transport != RpcTransportTypes.POST_MESSAGE) {
        throw RpcError.error(RpcErrors.INVALID_COMMAND, "Invalid transport.")
    }
}

fun validateApplication(socket: RpcSocket): Long =
    socket.application.id ?: throw RpcError.error(RpcErrors.INVALID_COMMAND, "Invalid application")
