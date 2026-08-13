package com.discord.rpc.commands

import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcScopeConfig

/** Quests are not modelled on this client, so every lookup misses. */
val questCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_QUEST_ENROLLMENT_STATUS to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.IDENTIFY),
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        throw RpcError.error(
            RpcErrors.INVALID_COMMAND,
            "Quest not found: ${context.args.opt("quest_id")}",
        )
    },

    RpcCommands.QUEST_START_TIMER to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.IDENTIFY),
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        throw RpcError.error(
            RpcErrors.INVALID_COMMAND,
            "Quest not found: ${context.args.opt("quest_id")}",
        )
    },

    RpcCommands.GET_QUEST to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.IDENTIFY),
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        throw RpcError.error(RpcErrors.INVALID_COMMAND, "No eligible quests found")
    },
)
