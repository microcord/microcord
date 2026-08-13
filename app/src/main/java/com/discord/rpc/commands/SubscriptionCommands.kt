package com.discord.rpc.commands

import android.os.Handler
import android.os.Looper
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcSocket
import com.discord.rpc.validateScope
import org.json.JSONObject

private val mainHandler = Handler(Looper.getMainLooper())

/** Set by the host: the initial state a freshly-subscribed socket is owed, if the event has one. */
var initialSubscriptionPayload: (RpcSocket, String, JSONObject) -> JSONObject? = { _, _, _ -> null }

val subscriptionCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.SUBSCRIBE to syncRpcCommand { context ->
        val evt = context.evt
        val event = evt?.let { context.server.getEventHandler(it) }
            ?: throw RpcError.error(RpcErrors.INVALID_EVENT, "Invalid event: $evt")
        if (!validateScope(context.socket.authorization.scopes, event.scope)) {
            throw RpcError.error(RpcErrors.INVALID_PERMISSIONS, "Not authenticated or invalid scope")
        }
        try {
            event.validate(context.args)
        } catch (e: RpcError) {
            throw RpcError.error(RpcErrors.INVALID_PAYLOAD, "Invalid subscription parameters provided")
        }
        val updater = event.handle(context.socket, context.args)
        val initial = initialSubscriptionPayload(context.socket, evt, context.args)

        // Registration is deferred so the {evt} ack reaches the client before the first payload.
        context.afterReply {
            context.server.addSubscription(context.socket, evt, context.args, updater)
            if (initial != null) {
                context.server.dispatchToSubscriptions(
                    evt,
                    filter = { it.socket.id == context.socket.id },
                    data = { initial },
                )
            }
        }
        JSONObject().put("evt", evt)
    },

    RpcCommands.UNSUBSCRIBE to syncRpcCommand { context ->
        val evt = context.evt
        if (evt == null || context.server.getEventHandler(evt) == null) {
            throw RpcError.error(RpcErrors.INVALID_EVENT, "Invalid event: $evt")
        }
        context.server.removeSubscription(context.socket, evt, context.args)
        JSONObject().put("evt", evt)
    },
)
