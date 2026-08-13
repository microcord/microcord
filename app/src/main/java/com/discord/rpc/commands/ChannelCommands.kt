package com.discord.rpc.commands

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcBoolean
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcNumber
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcString
import com.discord.rpc.jsonArrayOf
import com.discord.rpc.rpcSchema
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.permissions.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject

/** `messages` and `voice_states` stay empty; the reference only fills them for IPC clients. */
fun transformChannel(channel: Channel): JSONObject = JSONObject()
    .put("id", channel.id.toString())
    .put("name", channel.name ?: JSONObject.NULL)
    .put("type", channel.type)
    .put("topic", channel.topic ?: JSONObject.NULL)
    .put("bitrate", channel.bitrate)
    .put("user_limit", channel.userLimit)
    .put("guild_id", channel.guildId.takeIf { it > 0L }?.toString() ?: JSONObject.NULL)
    .put("position", channel.position)
    .put("messages", JSONArray())
    .put("voice_states", JSONArray())

private fun channelOrThrow(channelId: Long?): Channel =
    channelId?.let { StoreStream.getChannels().getChannel(it) }
        ?: throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid channel id: $channelId")

private fun channelPermissions(channelId: Long): Long? =
    StoreStream.getPermissions().getPermissionsByChannel()[channelId]

val channelCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_CHANNEL to syncRpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(OAuth2Scopes.RPC, OAuth2Scopes.GUILDS, OAuth2Scopes.GUILDS_CHANNELS_READ),
        ),
    ) { context ->
        val channelId = (context.args.opt("channel_id") as? String)?.toLongOrNull()
        val channel = channelOrThrow(channelId)
        if (channel.guildId <= 0L &&
            OAuth2Scopes.RPC !in context.socket.authorization.scopes &&
            OAuth2Scopes.DM_CHANNELS_READ !in context.socket.authorization.scopes
        ) {
            throw RpcError.error(RpcErrors.INVALID_PERMISSIONS, "Invalid scope")
        }
        transformChannel(channel)
    },

    RpcCommands.GET_CHANNELS to syncRpcCommand(scope = RpcScopeConfig.One(OAuth2Scopes.RPC)) { context ->
        val guildId = (context.args.opt("guild_id") as? String)?.toLongOrNull()
        if (guildId != null && StoreStream.getGuilds().getGuild(guildId) == null) {
            throw RpcError.error(RpcErrors.INVALID_GUILD, "Invalid guild id: $guildId")
        }
        val channels = StoreStream.getChannels().getAllChannels().values
            .filter { guildId == null || it.guildId == guildId }
            .filter { PermissionUtils.can(Permission.VIEW_CHANNEL, channelPermissions(it.id)) }
            .map {
                JSONObject()
                    .put("id", it.id.toString())
                    .put("name", it.name ?: JSONObject.NULL)
                    .put("type", it.type)
            }
        JSONObject().put("channels", jsonArrayOf(channels))
    },

    RpcCommands.GET_CHANNEL_PERMISSIONS to syncRpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(OAuth2Scopes.GUILDS_MEMBERS_READ, OAuth2Scopes.GUILDS_CHANNELS_READ),
        ),
    ) {
        val channel = currentEmbeddedActivityChannel()
            ?: throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid channel")
        JSONObject().put("permissions", (channelPermissions(channel.id) ?: 0L).toString())
    },

    RpcCommands.GET_SELECTED_VOICE_CHANNEL to syncRpcCommand(
        scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_READ)),
    ) {
        StoreStream.getVoiceChannelSelected().getSelectedVoiceChannel()?.let(::transformChannel)
    },

    RpcCommands.SELECT_VOICE_CHANNEL to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.RPC),
        validation = rpcSchema {
            put("channel_id", RpcString(nullable = true))
            put("timeout", RpcNumber(min = 0, max = 60))
            put("force", RpcBoolean())
            put("navigate", RpcBoolean())
        },
    ) { context ->
        val channelId = (context.args.opt("channel_id") as? String)?.toLongOrNull()
        if (channelId == null) {
            StoreStream.getVoiceChannelSelected().clear()
            return@syncRpcCommand null
        }
        val selected = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId()
        if (selected != 0L && selected != channelId && !context.args.optBoolean("force", false)) {
            throw RpcError.error(
                RpcErrors.SELECT_VOICE_FORCE_REQUIRED,
                "User is already joined to a voice channel.",
            )
        }
        val channel = channelOrThrow(channelId)
        if (!channel.isGuildVocalChannel()) {
            throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Channel is not a voice channel")
        }
        if (channel.guildId > 0L &&
            !PermissionUtils.can(Permission.CONNECT, channelPermissions(channel.id))
        ) {
            throw RpcError.error(
                RpcErrors.INVALID_PERMISSIONS,
                "Connect permission required to join channel",
            )
        }
        StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId).subscribe({}, {})
        transformChannel(channel)
    },

    RpcCommands.SELECT_TEXT_CHANNEL to syncRpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.RPC),
        validation = rpcSchema {
            put("channel_id", RpcString(nullable = true))
            put("timeout", RpcNumber(min = 0, max = 60))
        },
    ) { context ->
        val channelId = (context.args.opt("channel_id") as? String)?.toLongOrNull()
            ?: return@syncRpcCommand null
        val channel = channelOrThrow(channelId)
        if (channel.isGuildVocalChannel()) {
            throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Channel is not a text channel")
        }
        if (channel.guildId > 0L &&
            !PermissionUtils.can(Permission.VIEW_CHANNEL, channelPermissions(channel.id))
        ) {
            throw RpcError.error(RpcErrors.INVALID_CHANNEL, "No permission to see channel")
        }
        ChannelSelector.getInstance().selectChannel(channel.guildId, channel.id)
        transformChannel(channel)
    },
)

internal fun currentEmbeddedActivityChannel(): Channel? =
    StoreStream.getChannelsSelected().getSelectedChannel()
        ?: StoreStream.getVoiceChannelSelected().getSelectedVoiceChannel()
