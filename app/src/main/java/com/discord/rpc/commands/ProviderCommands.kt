package com.discord.rpc.commands

import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcObject
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.rpcSchema
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject
import rx.Observable

/** Amazon Music's applications; the reference's provider switch has no other case. */
private val ALLOWED_APPLICATIONS = setOf("1214629548377768066", "1234546995360694434")

private const val AMAZON_MUSIC = "amazon-music"

private val authenticatedScope = RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED))

private fun requireAllowedApplication(applicationId: Long) {
    if (applicationId.toString() !in ALLOWED_APPLICATIONS) {
        throw RpcError.error(
            RpcErrors.UNAUTHORIZED_FOR_APPLICATION,
            "Command not available for this application",
        )
    }
}

private fun requireKnownProvider(provider: String) {
    if (provider != AMAZON_MUSIC) {
        throw RpcError.error(RpcErrors.INVALID_PROVIDER, "Platform not found for provider \"$provider\"")
    }
}

private fun connectedAccountId(provider: String): String? =
    StoreStream.getUserConnections().getConnectedAccounts().connectedAccounts
        .firstOrNull { it.type == provider }
        ?.id

private fun accessTokenFor(provider: String, accountId: String): Observable<Any?> =
    RestAPI.api.getConnectionAccessToken(provider, accountId)
        .map { JSONObject().put("access_token", it.accessToken) as Any? }
        .onErrorResumeNext {
            Observable.error(
                RpcError.error(
                    RpcErrors.OAUTH2_ERROR,
                    "Refreshing access token did not return a new access token",
                ),
            )
        }

val providerCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_PROVIDER_ACCESS_TOKEN to rpcCommand(
        scope = authenticatedScope,
        validation = rpcSchema {
            put("provider", RpcString(optional = false))
            put("connection_redirect", RpcString())
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        val provider = context.args.getString("provider")
        requireKnownProvider(provider)
        requireAllowedApplication(validateApplication(context.socket))
        // The reference would open the OAuth consent flow here and wait for USER_CONNECTIONS_UPDATE.
        val accountId = connectedAccountId(provider)
            ?: throw RpcError.error(RpcErrors.OAUTH2_ERROR, "OAuth2 setup for \"$provider\" failed")
        accessTokenFor(provider, accountId)
    },

    RpcCommands.MAYBE_GET_PROVIDER_ACCESS_TOKEN to rpcCommand(
        scope = authenticatedScope,
        validation = rpcSchema {
            put("provider", RpcString(optional = false))
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        val provider = context.args.getString("provider")
        requireKnownProvider(provider)
        requireAllowedApplication(validateApplication(context.socket))
        val accountId = connectedAccountId(provider)
            ?: throw RpcError.error(RpcErrors.NO_CONNECTION_FOUND, "No connection found")
        accessTokenFor(provider, accountId)
    },

    RpcCommands.NAVIGATE_TO_CONNECTIONS to syncRpcCommand(
        scope = authenticatedScope,
        validation = RpcObject(optional = true),
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        requireAllowedApplication(validateApplication(context.socket))
        rpcActivityHost?.navigateToConnections()
        null
    },
)
