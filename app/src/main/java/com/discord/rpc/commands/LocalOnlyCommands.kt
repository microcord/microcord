package com.discord.rpc.commands

import android.util.Base64
import com.discord.rpc.RpcBoolean
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcNumber
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.rpcSchema
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.rest.RestAPI
import java.net.URL
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import rx.Observable

/** Granted only to origin-less sockets, so registering these answers `INVALID_PERMISSIONS`. */
private val localScope = RpcScopeConfig.One(RpcScopes.LOCAL)

private val imageClient by lazy { OkHttpClient() }

val localOnlyCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_IMAGE to rpcCommand(
        scope = localScope,
        validation = rpcSchema {
            put("type", RpcString(optional = false, valid = listOf("user")))
            put("id", RpcString(optional = false))
            put("format", RpcString(optional = false, valid = listOf("png", "webp", "jpg")))
            put("size", RpcNumber(optional = false, valid = listOf(16, 32, 64, 128, 256, 512, 1024)))
        },
    ) { context ->
        val id = context.args.getString("id")
        val user = id.toLongOrNull()?.let { StoreStream.getUsers().getUsers()[it] }
            ?: throw RpcError.error(RpcErrors.INVALID_USER, "Invalid user id: $id")
        val format = context.args.getString("format")
        val size = context.args.getInt("size")
        val url = user.avatar?.let {
            "https://cdn.discordapp.com/avatars/${user.id}/$it.$format?size=$size"
        } ?: "https://cdn.discordapp.com/embed/avatars/${(user.id shr 22) % 6}.png"

        Observable.fromCallable {
            val response = imageClient.newCall(Request.Builder().url(URL(url)).build()).execute()
            val bytes = response.use { it.body?.bytes() }
                ?: throw RpcError.error(RpcErrors.UNKNOWN_ERROR, "Unable to read image")
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            JSONObject().put("data_url", "data:image/$format;base64,$encoded") as Any?
        }
    },

    // No peer-to-peer networking client here; registered only to answer in-band.
    RpcCommands.GET_NETWORKING_CONFIG to deprecatedCommand,
    RpcCommands.NETWORKING_CREATE_TOKEN to deprecatedCommand,
    RpcCommands.NETWORKING_SYSTEM_METRICS to deprecatedCommand,
    RpcCommands.NETWORKING_PEER_METRICS to deprecatedCommand,

    RpcCommands.TOGGLE_VOICE_CHANNEL_CHAT to syncRpcCommand(
        scope = localScope,
        validation = rpcSchema(required = false) {
            put("open", RpcBoolean())
        },
    ) { context ->
        val channel = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannel()
        if (channel == null || channel.guildId <= 0L) {
            throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Not connected to a guild voice channel")
        }
        val open = (context.args.opt("open") as? Boolean)
            ?: (StoreStream.getChannelsSelected().getId() != channel.id)
        if (open) {
            com.discord.utilities.channel.ChannelSelector.getInstance()
                .selectChannel(channel.guildId, channel.id)
        }
        JSONObject().put("channel_id", channel.id.toString()).put("chat_open", open)
    },
)
