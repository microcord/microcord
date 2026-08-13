package com.discord.rpc.commands

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
import com.discord.rpc.transformUser
import com.discord.stores.StoreStream
import org.json.JSONObject
import rx.Observable

interface RpcActivityHost {
    fun connectedParticipantIds(): List<Long>

    fun participantNickname(userId: Long): String?

    fun setOrientationLockState(lockState: Int, pictureInPicture: Int?, grid: Int?)

    fun setUseInteractivePip(enabled: Boolean)

    fun openExternalLink(url: String): Observable<Boolean>

    fun refreshProxyTicket(): Observable<String>

    fun openInviteDialog()

    fun shareLink(message: String, customId: String?, linkId: String?): Observable<Boolean>

    fun navigateToConnections()

    fun initiateImageUpload(): Observable<String>

    fun captureLog(level: String, message: String)

    fun sendAnalyticsEvent(name: String, properties: JSONObject)
}

var rpcActivityHost: RpcActivityHost? = null

private fun host(): RpcActivityHost =
    rpcActivityHost ?: throw RpcError.error(RpcErrors.SERVICE_UNAVAILABLE, "No activity host")

private val authenticatedScope = RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED))

fun activityInstanceConnectedParticipants(): JSONObject {
    val users = StoreStream.getUsers().getUsers()
    val participants = rpcActivityHost?.connectedParticipantIds().orEmpty()
        .mapNotNull { users[it] }
        .map { user ->
            transformUser(user).apply {
                host().participantNickname(user.id)?.let { put("nickname", it) }
            }
        }
    return JSONObject().put("participants", jsonArrayOf(participants))
}

val activityCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_ACTIVITY_INSTANCE_CONNECTED_PARTICIPANTS to syncRpcCommand(
        scope = authenticatedScope,
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        activityInstanceConnectedParticipants()
    },

    RpcCommands.REQUEST_PROXY_TICKET_REFRESH to rpcCommand(scope = authenticatedScope) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        host().refreshProxyTicket()
            .map { JSONObject().put("ticket", it) as Any? }
            .onErrorResumeNext {
                Observable.error(RpcError.error(RpcErrors.UNKNOWN_ERROR, "Failed to create proxy ticket"))
            }
    },

    RpcCommands.SET_CONFIG to syncRpcCommand(
        scope = RpcScopeConfig.One(RpcScopes.AUTHENTICATED),
        validation = rpcSchema {
            put("use_interactive_pip", com.discord.rpc.RpcBoolean())
        },
    ) { context ->
        if (context.socket.transport != com.discord.rpc.RpcTransportTypes.POST_MESSAGE) {
            throw RpcError.error(
                RpcErrors.INVALID_COMMAND,
                "command not available from \"${context.socket.transport} transport",
            )
        }
        if (context.socket.application.id == null) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "invalid application")
        }
        val enabled = context.args.opt("use_interactive_pip")
        if (enabled is Boolean) host().setUseInteractivePip(enabled)
        JSONObject().put("use_interactive_pip", enabled ?: JSONObject.NULL)
    },

    RpcCommands.SET_ORIENTATION_LOCK_STATE to syncRpcCommand(
        validation = rpcSchema {
            put("lock_state", RpcNumber(optional = false, valid = ORIENTATION_LOCK_STATES))
            put("picture_in_picture_lock_state", RpcNumber(nullable = true, valid = ORIENTATION_LOCK_STATES))
            put("grid_lock_state", RpcNumber(nullable = true, valid = ORIENTATION_LOCK_STATES))
        },
    ) { context ->
        // Unlike its neighbours this checks source.type and quotes it correctly; SDKs match on the message.
        if (context.socket.source.type != com.discord.rpc.RpcTransportTypes.POST_MESSAGE) {
            throw RpcError.error(
                RpcErrors.INVALID_COMMAND,
                "command not available from \"${context.socket.source.type}\" transport",
            )
        }
        if (context.socket.application.id == null) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
        }
        host().setOrientationLockState(
            context.args.getInt("lock_state"),
            context.args.opt("picture_in_picture_lock_state") as? Int,
            context.args.opt("grid_lock_state") as? Int,
        )
        null
    },

    RpcCommands.OPEN_EXTERNAL_LINK to rpcCommand(
        scope = RpcScopeConfig.Any(listOf(RpcScopes.AUTHENTICATED, RpcScopes.EMBEDDED_APP)),
        validation = rpcSchema {
            put("url", RpcString(optional = false))
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        val url = context.args.getString("url")
        host().openExternalLink(url).map { JSONObject().put("opened", it) as Any? }
    },

    RpcCommands.OPEN_INVITE_DIALOG to syncRpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(com.discord.rpc.OAuth2Scopes.RPC, RpcScopes.LOCAL, RpcScopes.AUTHENTICATED),
        ),
    ) {
        currentEmbeddedActivityChannel()
            ?: throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid channel")
        host().openInviteDialog()
        null
    },

    RpcCommands.INITIATE_IMAGE_UPLOAD to rpcCommand(
        scope = RpcScopeConfig.Any(
            listOf(com.discord.rpc.OAuth2Scopes.RPC, RpcScopes.LOCAL, RpcScopes.AUTHENTICATED),
        ),
    ) { context ->
        if (context.socket.application.id == null) {
            throw RpcError.error(RpcErrors.INVALID_COMMAND, "No application.")
        }
        currentEmbeddedActivityChannel()
            ?: throw RpcError.error(RpcErrors.UNKNOWN_ERROR, "Unable to find selected channel")
        host().initiateImageUpload().map { JSONObject().put("image_url", it) as Any? }
    },

    RpcCommands.CAPTURE_LOG to syncRpcCommand(
        validation = rpcSchema {
            put("level", RpcString(optional = false, max = 10))
            put("message", RpcString(optional = false, max = 1000))
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        host().captureLog(context.args.getString("level"), context.args.getString("message"))
        null
    },

    RpcCommands.SEND_ANALYTICS_EVENT to syncRpcCommand(
        validation = rpcSchema {
            put("event_name", RpcString(optional = false))
            put("event_properties", com.discord.rpc.RpcObject(optional = false))
        },
    ) { context ->
        validatePostMessageTransport(context.socket.transport)
        validateApplication(context.socket)
        host().sendAnalyticsEvent(
            context.args.getString("event_name"),
            context.args.getJSONObject("event_properties"),
        )
        null
    },

    RpcCommands.GET_PLATFORM_BEHAVIORS to syncRpcCommand {
        JSONObject().put("iosKeyboardResizesView", true)
    },
)

private val ORIENTATION_LOCK_STATES = listOf(1, 2, 3)
