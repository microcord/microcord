package com.discord.rpc.commands

import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcNumber
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.jsonArrayOf
import com.discord.rpc.rpcSchema
import com.discord.rpc.transformGuild
import com.discord.rpc.transformUser
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject

val guildCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_GUILD to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.RPC),
        validation = rpcSchema {
            put("guild_id", RpcString())
            put("timeout", RpcNumber(min = 0, max = 60))
        },
    ) { context ->
        val guildId = (context.args.opt("guild_id") as? String)?.toLongOrNull()
        val guild = guildId?.let { StoreStream.getGuilds().getGuild(it) }
            ?: throw RpcError.error(RpcErrors.INVALID_GUILD, "Invalid guild id: $guildId")
        // `members` is always empty; the reference deprecated it without removing the key.
        transformGuild(guild)
            .put("members", JSONArray())
            .put("vanity_url_code", guild.vanityUrlCode ?: JSONObject.NULL)
    },

    RpcCommands.GET_GUILDS to syncRpcCommand(scope = RpcScopeConfig.One(OAuth2Scopes.RPC)) {
        JSONObject().put("guilds", jsonArrayOf(StoreStream.getGuilds().getGuilds().values.map(::transformGuild)))
    },
)

val userCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_USER to syncRpcCommand(
        scope = RpcScopeConfig.Any(listOf(RpcScopes.EMBEDDED_APP, RpcScopes.LOCAL)),
        validation = rpcSchema {
            put("id", RpcString(optional = false, max = 64))
        },
    ) { context ->
        val id = (context.args.opt("id") as? String)?.toLongOrNull()
        id?.let { StoreStream.getUsers().getUsers()[it] }?.let(::transformUser)
    },

    RpcCommands.USER_SETTINGS_GET_LOCALE to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.IDENTIFY),
    ) {
        JSONObject().put("locale", StoreStream.getUserSettingsSystem().getLocale())
    },

    RpcCommands.GET_RELATIONSHIPS to syncRpcCommand(
        scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RELATIONSHIPS_READ)),
    ) { context ->
        val users = StoreStream.getUsers().getUsers()
        val applicationId = context.socket.application.id
        val relationships = StoreStream.getUserRelationships().getRelationships()
            .filter { it.value != RELATIONSHIP_NONE }
            .mapNotNull { (userId, type) ->
                val user = users[userId] ?: return@mapNotNull null
                JSONObject()
                    .put("type", type)
                    .put("user", transformUser(user))
                    .put(
                        "presence",
                        JSONObject()
                            .put("status", presenceStatus(userId))
                            .put("activity", applicationActivity(userId, applicationId)),
                    )
            }
        JSONObject().put("relationships", jsonArrayOf(relationships))
    },
)

private const val RELATIONSHIP_NONE = 0

private fun presenceStatus(userId: Long): String =
    StoreStream.getPresences().getPresences()[userId]?.status?.name?.lowercase() ?: "offline"

private fun applicationActivity(userId: Long, applicationId: Long?): Any {
    if (applicationId == null) return JSONObject.NULL
    val activity = StoreStream.getPresences().getApplicationActivity(userId, applicationId)
        ?: return JSONObject.NULL
    return JSONObject()
        .put("name", activity.name)
        .put("type", activity.type.apiInt)
        .put("application_id", applicationId.toString())
        .put("details", activity.details ?: JSONObject.NULL)
        .put("state", activity.state ?: JSONObject.NULL)
}
