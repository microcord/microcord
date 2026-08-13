package com.discord.rpc.commands

import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcString
import com.discord.rpc.rpcSchema
import org.json.JSONObject
import rx.Observable

/** The consent-prompting half: [authorize] resolves with the code, [authenticate] with `/oauth2/@me`. */
interface RpcAuthHost {
    fun authorize(socket: RpcSocket, args: JSONObject): Observable<String>

    fun authenticate(socket: RpcSocket, accessToken: String): Observable<JSONObject>
}

var rpcAuthHost: RpcAuthHost? = null

private fun host(): RpcAuthHost =
    rpcAuthHost ?: throw RpcError.error(RpcErrors.SERVICE_UNAVAILABLE, "No auth host")

val authCommands: Map<String, RpcCommand> = mapOf(
    // Unvalidated: the reference declares no schema, so any payload the JS client accepts must pass.
    RpcCommands.AUTHORIZE to rpcCommand { context ->
        val clientId = (context.args.opt("client_id") as? String)?.takeIf(String::isNotEmpty)
            ?: throw RpcError.error(RpcErrors.INVALID_CLIENTID, "No client id provided")
        if (context.socket.authorization.accessToken != null) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "Already authenticated")
        }
        if (context.socket.authorization.authing) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "Already authing")
        }
        context.socket.authorization.authing = true
        if (context.args.opt("response_type") == "token") {
            context.socket.authorization.authing = false
            throw RpcError.error(
                RpcErrors.INVALID_COMMAND,
                "Authorization response_type \"token\" is not supported",
            )
        }
        if (RpcScopes.PRIVATE !in context.socket.authorization.scopes &&
            context.socket.application.id?.toString() != clientId
        ) {
            context.socket.authorization.authing = false
            throw RpcError.error(RpcErrors.INVALID_CLIENTID, "Application does not match the connection's")
        }
        host().authorize(context.socket, context.args)
            .doOnTerminate { context.socket.authorization.authing = false }
            .map { JSONObject().put("code", it) as Any? }
    },

    RpcCommands.AUTHENTICATE to rpcCommand(
        validation = rpcSchema {
            put("access_token", RpcString(nullable = true))
        },
    ) { context ->
        val accessToken = context.args.opt("access_token") as? String
            ?: throw RpcError.error(RpcErrors.INVALID_TOKEN, "No access token provided")
        if (context.socket.authorization.accessToken?.isNotEmpty() == true) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "Already authenticated")
        }
        if (context.socket.authorization.authing) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "Already authenticating")
        }
        context.socket.authorization.authing = true
        host().authenticate(context.socket, accessToken)
            .doOnTerminate { context.socket.authorization.authing = false }
            .map { body ->
                val scopes = body.optJSONArray("scopes")
                if (scopes != null) {
                    for (index in 0 until scopes.length()) {
                        (scopes.opt(index) as? String)?.let { context.socket.authorization.scopes += it }
                    }
                }
                context.socket.authorization.scopes += RpcScopes.AUTHENTICATED
                context.socket.authorization.accessToken = accessToken
                JSONObject(body.toString()).put("access_token", accessToken) as Any? 
            }
    },
)
