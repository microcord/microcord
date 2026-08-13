package com.discord.rpc.commands

import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcArray
import com.discord.rpc.RpcBoolean
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcObject
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.rpcSchema
import com.discord.api.channel.Channel
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable

val inviteCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.CREATE_CHANNEL_INVITE to rpcCommand(
        scope = RpcScopeConfig.One(OAuth2Scopes.RPC),
    ) { context ->
        val channelId = (context.args.opt("channel_id") as? String)?.toLongOrNull()
            ?: throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid channel")
        RestAPI.api
            .postChannelInvite(
                channelId,
                RestAPIParams.Invite(
                    maxAge = (context.args.opt("max_age") as? Number)?.toInt() ?: DEFAULT_MAX_AGE,
                    maxUses = (context.args.opt("max_uses") as? Number)?.toInt() ?: 0,
                    temporary = context.args.optBoolean("temporary", false),
                    regenerate = null,
                ),
                "{}",
            )
            .map { invite -> JSONObject().put("code", invite.code) as Any? }
            .onErrorResumeNext {
                Observable.error(
                    RpcError.error(
                        RpcErrors.INVALID_PERMISSIONS,
                        "Unable to generate an invite for $channelId. Does this user have permissions?",
                    ),
                )
            }
    },

    RpcCommands.INVITE_USER_EMBEDDED to syncRpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(OAuth2Scopes.DM_CHANNELS_MESSAGES_WRITE, OAuth2Scopes.ACTIVITIES_INVITES_WRITE),
        ),
        validation = rpcSchema {
            put("user_id", RpcString(optional = false))
            put("content", RpcString(max = 1024))
        },
    ) { context ->
        if (context.socket.application.id == null) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
        }
        val channel = currentEmbeddedActivityChannel() ?: throw RpcError.error(
            RpcErrors.NO_ELIGIBLE_ACTIVITY,
            "No eligible activity for application. Ensure an activity was set using setActivity.",
        )
        val userId = context.args.getString("user_id").toLongOrNull()
            ?: throw RpcError.error(RpcErrors.INVALID_USER, "Invalid user")
        val content = context.args.opt("content") as? String
        RestAPI.api.userCreateChannel(RestAPIParams.CreateChannel(userId))
            .flatMap { dm ->
                if (dm.type == Channel.DM || dm.type == Channel.GROUP_DM) {
                    RestAPI.api.sendMessage(
                        dm.id,
                        RestAPIParams.Message(
                            content = content ?: inviteLink(channel),
                            nonce = null,
                        ),
                        null,
                        "{}",
                    )
                } else {
                    Observable.error(RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid channel"))
                }
            }
            .map { null as Any? }
            .onErrorResumeNext { failure ->
                Observable.error(
                    failure as? RpcError
                        ?: RpcError.error(RpcErrors.UNKNOWN_ERROR, "Failed to invite user"),
                )
            }
    },

    RpcCommands.SHARE_LINK to rpcCommand(
        scope = RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED)),
        validation = rpcSchema {
            put("custom_id", RpcString(max = 64))
            put("message", RpcString(optional = false, max = 1000))
            put("link_id", RpcString(max = 64))
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        rpcActivityHost?.shareLink(
            context.args.getString("message"),
            context.args.opt("custom_id") as? String,
            context.args.opt("link_id") as? String,
        )?.map { shared ->
            // camelCase, unlike every neighbouring payload.
            JSONObject()
                .put("success", shared)
                .put("didCopyLink", shared)
                .put("didSendMessage", false) as Any?
        } ?: Observable.error(RpcError.error(RpcErrors.SERVICE_UNAVAILABLE, "No activity host"))
    },

    RpcCommands.SET_CERTIFIED_DEVICES to syncRpcCommand(
        scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
        validation = rpcSchema {
            put(
                "devices",
                RpcArray(
                    items = RpcObject(
                        keys = linkedMapOf(
                            "type" to RpcString(
                                optional = false,
                                valid = listOf("audioinput", "audiooutput", "videoinput"),
                            ),
                            "id" to RpcString(optional = false, min = 1),
                            "vendor" to RpcObject(
                                optional = false,
                                keys = linkedMapOf(
                                    "name" to RpcString(min = 1),
                                    "url" to RpcString(min = 1),
                                ),
                            ),
                            "model" to RpcObject(
                                optional = false,
                                keys = linkedMapOf(
                                    "name" to RpcString(min = 1),
                                    "url" to RpcString(min = 1),
                                ),
                            ),
                            "related" to RpcArray(items = RpcString(min = 1)),
                            "echo_cancellation" to RpcBoolean(),
                            "noise_suppression" to RpcBoolean(),
                            "automatic_gain_control" to RpcBoolean(),
                            "hardware_mute" to RpcBoolean(),
                        ),
                    ),
                ),
            )
        },
    ) { context ->
        val applicationId = context.socket.application.id
            ?: throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
        certifiedDevices[applicationId] = context.args.optJSONArray("devices") ?: JSONArray()
        null
    },
)

val certifiedDevices = linkedMapOf<Long, JSONArray>()

private fun inviteLink(channel: Channel): String =
    "https://discord.com/channels/${channel.guildId.takeIf { it > 0L } ?: "@me"}/${channel.id}"

private const val DEFAULT_MAX_AGE = 86400
