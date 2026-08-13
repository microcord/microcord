package com.discord.rpc.events

import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.PushOnlyRpcEvent
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcEvent
import com.discord.rpc.RpcEvents
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcString
import com.discord.rpc.RpcSubscription
import com.discord.rpc.commands.activityInstanceConnectedParticipants
import com.discord.rpc.commands.deprecatedVoiceSettings
import com.discord.rpc.commands.voiceSettings
import com.discord.rpc.rpcSchema
import com.discord.rpc.transformGuildMember
import com.discord.rpc.transformUser
import com.discord.stores.StoreStream
import org.json.JSONObject

private val channelIdSchema = rpcSchema {
    put("channel_id", RpcString(optional = false))
}

private val guildIdSchema = rpcSchema {
    put("guild_id", RpcString(optional = false))
}

private class ChannelScopedEvent(override val scope: RpcScopeConfig?) : RpcEvent {
    override fun validate(args: JSONObject) = channelIdSchema.assert(args)
}

private fun diffing(next: () -> JSONObject?): (RpcSubscription) -> Any? = { subscription ->
    val payload = next()
    val serialized = payload?.toString()
    if (serialized != null && serialized != subscription.prevState) subscription.dispatch(payload)
    serialized
}

val rpcEventHandlers: Map<String, RpcEvent> = mapOf(
    RpcEvents.GUILD_STATUS to object : RpcEvent {
        override val scope = RpcScopeConfig.One(OAuth2Scopes.RPC)

        override fun validate(args: JSONObject) = guildIdSchema.assert(args)

        override fun handle(socket: RpcSocket, args: JSONObject): (RpcSubscription) -> Any? {
            val guildId = (args.opt("guild_id") as? String)?.toLongOrNull()
            if (guildId == null || StoreStream.getGuilds().getGuild(guildId) == null) {
                throw RpcError.error(RpcErrors.INVALID_GUILD, "Invalid guild id: $guildId")
            }
            return diffing {
                val guild = StoreStream.getGuilds().getGuild(guildId) ?: return@diffing null
                JSONObject()
                    .put("guild", com.discord.rpc.transformGuild(guild))
                    // Deprecated and always zero, but the key is still expected.
                    .put("online", 0)
            }
        }
    },

    RpcEvents.CURRENT_USER_UPDATE to object : RpcEvent {
        override val scope = RpcScopeConfig.Any(listOf(RpcScopes.LOCAL, OAuth2Scopes.IDENTIFY))

        override fun handle(socket: RpcSocket, args: JSONObject) = diffing {
            transformUser(StoreStream.getUsers().getMe())
        }
    },

    RpcEvents.CURRENT_GUILD_MEMBER_UPDATE to object : RpcEvent {
        override val scope =
            RpcScopeConfig.All(listOf(OAuth2Scopes.IDENTIFY, OAuth2Scopes.GUILDS_MEMBERS_READ))

        override fun handle(socket: RpcSocket, args: JSONObject): (RpcSubscription) -> Any? {
            val guildId = (args.opt("guild_id") as? String)?.toLongOrNull()
            val meId = StoreStream.getUsers().getMe().id
            return diffing {
                guildId?.let { StoreStream.getGuilds().getMember(it, meId) }?.let(::transformGuildMember)
            }
        }
    },

    RpcEvents.ACTIVITY_INSTANCE_PARTICIPANTS_UPDATE to object : RpcEvent {
        override val scope = RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED))

        override fun handle(socket: RpcSocket, args: JSONObject) =
            diffing { activityInstanceConnectedParticipants() }
    },

    RpcEvents.VOICE_SETTINGS_UPDATE to object : RpcEvent {
        override val scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ))

        override fun handle(socket: RpcSocket, args: JSONObject) = diffing { deprecatedVoiceSettings() }
    },

    RpcEvents.VOICE_SETTINGS_UPDATE_2 to object : RpcEvent {
        override val scope = RpcScopeConfig.One(RpcScopes.LOCAL)

        override fun handle(socket: RpcSocket, args: JSONObject) = diffing {
            socket.application.id?.let { voiceSettings() }
        }
    },

    RpcEvents.VOICE_CHANNEL_SELECT to PushOnlyRpcEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),
    RpcEvents.GUILD_CREATE to PushOnlyRpcEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),
    RpcEvents.CHANNEL_CREATE to PushOnlyRpcEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),

    RpcEvents.MESSAGE_CREATE to ChannelScopedEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),
    RpcEvents.MESSAGE_UPDATE to ChannelScopedEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),
    RpcEvents.MESSAGE_DELETE to ChannelScopedEvent(RpcScopeConfig.One(OAuth2Scopes.RPC)),

    RpcEvents.SPEAKING_START to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ, RpcScopes.LOCAL)),
    ),
    RpcEvents.SPEAKING_STOP to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ, RpcScopes.LOCAL)),
    ),

    RpcEvents.VOICE_STATE_CREATE to ChannelScopedEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ),
    RpcEvents.VOICE_STATE_DELETE to ChannelScopedEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ),
    RpcEvents.VOICE_STATE_UPDATE to ChannelScopedEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ),

    RpcEvents.ACTIVITY_JOIN to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.AUTHENTICATED, RpcScopes.LOCAL)),
    ),
    RpcEvents.ACTIVITY_JOIN_REQUEST to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
    ),
    RpcEvents.ACTIVITY_SPECTATE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.AUTHENTICATED, RpcScopes.LOCAL)),
    ),
    RpcEvents.ACTIVITY_INVITE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
    ),
    RpcEvents.GAME_JOIN to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
    ),
    RpcEvents.GAME_SPECTATE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
    ),

    RpcEvents.ACTIVITY_PIP_MODE_UPDATE to PushOnlyRpcEvent(),
    RpcEvents.ACTIVITY_LAYOUT_MODE_UPDATE to PushOnlyRpcEvent(),
    RpcEvents.FRAME_LAYOUT_MODE_UPDATE to PushOnlyRpcEvent(),
    RpcEvents.AUTHORIZE_REQUEST to PushOnlyRpcEvent(),

    RpcEvents.THERMAL_STATE_UPDATE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED)),
    ),
    RpcEvents.ORIENTATION_UPDATE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED)),
    ),
    RpcEvents.ENTITLEMENT_CREATE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(RpcScopes.LOCAL, RpcScopes.AUTHENTICATED)),
    ),
    RpcEvents.ENTITLEMENT_DELETE to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(RpcScopes.LOCAL, RpcScopes.AUTHENTICATED)),
    ),
    RpcEvents.NOTIFICATION_CREATE to PushOnlyRpcEvent(
        RpcScopeConfig.All(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_NOTIFICATIONS_READ)),
    ),
    RpcEvents.RELATIONSHIP_UPDATE to PushOnlyRpcEvent(
        RpcScopeConfig.One(OAuth2Scopes.RELATIONSHIPS_READ),
    ),
    RpcEvents.SCREENSHARE_STATE_UPDATE to PushOnlyRpcEvent(
        RpcScopeConfig.All(listOf(RpcScopes.LOCAL, OAuth2Scopes.RPC_SCREENSHARE_READ)),
    ),
    RpcEvents.VIDEO_STATE_UPDATE to PushOnlyRpcEvent(
        RpcScopeConfig.All(listOf(RpcScopes.LOCAL, OAuth2Scopes.RPC_VIDEO_READ)),
    ),
    RpcEvents.VOICE_CONNECTION_STATUS to PushOnlyRpcEvent(
        RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ),
)
