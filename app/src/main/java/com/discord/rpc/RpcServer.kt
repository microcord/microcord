package com.discord.rpc

import com.discord.rpc.transport.PostMessageTransport
import com.discord.rpc.transport.RpcTransportListener
import org.json.JSONObject
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

class RpcCommandContext(
    val socket: RpcSocket,
    val server: RpcServer,
    val cmd: String,
    val evt: String?,
    val nonce: String?,
    val args: JSONObject,
    val isSocketConnected: () -> Boolean,
    /** Runs after the command's reply is on the wire, never before. */
    val afterReply: (() -> Unit) -> Unit = {},
)

interface RpcCommand {
    val scope: RpcScopeConfig? get() = null

    /** Throws [RpcError] with `INVALID_PAYLOAD` when [args] does not satisfy the command's schema. */
    fun validate(args: JSONObject) = Unit

    /** Resolves with a [JSONObject], or a [org.json.JSONArray] for the commands that answer bare arrays. */
    fun handle(context: RpcCommandContext): Observable<Any?>
}

class RpcSubscription(
    val socket: RpcSocket,
    val evt: String,
    val args: JSONObject,
    val update: ((RpcSubscription) -> Any?)?,
    val dispatch: (Any?) -> Unit,
) {
    var prevState: Any? = null
}

/** Config echoed to every socket in its `READY`. */
data class RpcServerConfig(
    val cdnHost: String,
    val apiEndpoint: String,
    val environment: String,
)

class RpcServer(private val config: RpcServerConfig) {
    private val commands = linkedMapOf<String, RpcCommand>()
    private val events = linkedMapOf<String, RpcEvent>()
    private val recentDispatchIds = ArrayDeque<String>()

    val sockets = linkedSetOf<RpcSocket>()
    val subscriptions = mutableListOf<RpcSubscription>()

    var getCurrentUser: () -> JSONObject? = { null }
    var onConnect: (RpcSocket) -> Unit = {}
    var onDisconnect: (RpcSocket, Int?, String?) -> Unit = { _, _, _ -> }

    fun setCommandHandler(cmd: String, command: RpcCommand) {
        commands[cmd] = command
    }

    fun setEventHandler(evt: String, event: RpcEvent) {
        events[evt] = event
    }

    fun getEventHandler(evt: String): RpcEvent? = events[evt]

    fun registerTransport(transport: PostMessageTransport) {
        transport.addListener(object : RpcTransportListener {
            override fun onConnect(socket: RpcSocket) = handleConnect(socket)

            override fun onRequest(socket: RpcSocket, payload: JSONObject) = handleRequest(socket, payload)

            override fun onDisconnect(socket: RpcSocket, code: Int?, message: String?) =
                handleDisconnect(socket, code, message)
        })
    }

    private fun handleConnect(socket: RpcSocket) {
        sockets += socket
        onConnect(socket)

        val ready = JSONObject()
            .put("v", socket.version)
            .put(
                "config",
                JSONObject()
                    .put("cdn_host", config.cdnHost)
                    .put("api_endpoint", config.apiEndpoint)
                    .put("environment", config.environment),
            )

        if (socket.transport == RpcTransportTypes.IPC) {
            val user = getCurrentUser() ?: run {
                socket.close(RpcCloseCodes.CLOSE_NORMAL, "User logged out")
                return
            }
            ready.put("user", user)
        }

        dispatch(socket, null, RpcCommands.DISPATCH, RpcEvents.READY, ready)
    }

    private fun handleDisconnect(socket: RpcSocket, code: Int?, message: String?) {
        socket.abort()
        removeSubscriptions(socket)
        RpcActivities.set(socket.id, null)
        sockets -= socket
        onDisconnect(socket, code, message)
    }

    private fun handleRequest(socket: RpcSocket, payload: JSONObject) {
        val cmd = payload.optString("cmd")
        val nonce = payload.opt("nonce") as? String
        try {
            if (nonce.isNullOrEmpty()) {
                throw RpcError.error(RpcErrors.INVALID_PAYLOAD, "Payload requires a nonce")
            }
            val command = commands[cmd]
                ?: throw RpcError.error(RpcErrors.INVALID_COMMAND, "Invalid command: $cmd")
            if (!validateScope(socket.authorization.scopes, command.scope)) {
                throw RpcError.error(RpcErrors.INVALID_PERMISSIONS, "Not authenticated or invalid scope")
            }
            val args = payload.optJSONObject("args") ?: JSONObject()
            command.validate(args)
            val pendingAfterReply = mutableListOf<() -> Unit>()

            val context = RpcCommandContext(
                socket = socket,
                server = this,
                cmd = cmd,
                evt = payload.opt("evt") as? String,
                nonce = nonce,
                args = args,
                isSocketConnected = { socket in sockets },
                afterReply = { pendingAfterReply += it },
            )
            // Frames arrive on the main thread; handlers may hit the API, and replies touch the UI.
            command.handle(context)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { data ->
                        dispatch(socket, nonce, cmd, null, data)
                        pendingAfterReply.forEach { it() }
                        pendingAfterReply.clear()
                    },
                    { failure ->
                        failure.toRpcError().let { error(socket, nonce, cmd, it.code, it.message.orEmpty()) }
                    },
                )
        } catch (e: Throwable) {
            e.toRpcError().let { error(socket, nonce, cmd, it.code, it.message.orEmpty()) }
        }
    }

    fun dispatch(
        socket: RpcSocket,
        nonce: String? = null,
        cmd: String = RpcCommands.DISPATCH,
        evt: String? = null,
        data: Any? = null,
    ) {
        socket.send(
            JSONObject()
                .put("cmd", cmd)
                .put("data", data ?: JSONObject.NULL)
                .put("evt", evt ?: JSONObject.NULL)
                .put("nonce", nonce ?: JSONObject.NULL),
        )
    }

    fun error(
        socket: RpcSocket,
        nonce: String? = null,
        cmd: String = RpcCommands.DISPATCH,
        code: Int = RpcErrors.UNKNOWN_ERROR,
        message: String = "Unknown Error",
    ) {
        dispatch(
            socket,
            nonce,
            cmd,
            RpcEvents.ERROR,
            JSONObject().put("code", code).put("message", message),
        )
    }

    fun isSubscribed(applicationId: Long?, event: String): Boolean =
        subscriptions.any { it.socket.application.id == applicationId && it.evt == event }

    fun isChildSubscribed(parentApplicationId: Long?, event: String): Boolean {
        if (parentApplicationId == null) return false
        return subscriptions.any { it.socket.application.parentId == parentApplicationId && it.evt == event }
    }

    fun getSubscription(socket: RpcSocket, evt: String, args: JSONObject): RpcSubscription? =
        subscriptions.firstOrNull { it.socket === socket && it.evt == evt && it.args.matches(args) }

    fun addSubscription(
        socket: RpcSocket,
        evt: String,
        args: JSONObject,
        update: ((RpcSubscription) -> Any?)? = null,
    ) {
        if (getSubscription(socket, evt, args) != null) return
        val subscription = RpcSubscription(socket, evt, args, update) { data ->
            dispatch(socket, null, RpcCommands.DISPATCH, evt, data)
        }
        subscriptions += subscription
        subscription.prevState = update?.invoke(subscription)
    }

    fun removeSubscription(socket: RpcSocket, evt: String, args: JSONObject) {
        subscriptions.removeAll { it.socket === socket && it.evt == evt && it.args.matches(args) }
    }

    fun removeSubscriptions(socket: RpcSocket) {
        subscriptions.removeAll { it.socket === socket }
    }

    /** @param dedupId suppresses a repeat of the same logical update. */
    fun dispatchToSubscriptions(
        evt: String,
        filter: (RpcSubscription) -> Boolean,
        data: (RpcSubscription) -> Any?,
        dedupId: String? = null,
    ) {
        if (!dedupId.isNullOrEmpty() && !deduplicate(dedupId)) return
        subscriptions.toList().forEach { subscription ->
            if (subscription.evt != evt || !filter(subscription)) return@forEach
            dispatch(subscription.socket, null, RpcCommands.DISPATCH, subscription.evt, data(subscription))
        }
    }

    fun updateSubscriptions() {
        subscriptions.toList().forEach { subscription ->
            val update = subscription.update ?: return@forEach
            subscription.prevState = update(subscription)
        }
    }

    private fun deduplicate(id: String): Boolean {
        if (id in recentDispatchIds) return false
        recentDispatchIds.addFirst(id)
        while (recentDispatchIds.size > DEDUP_LIMIT) recentDispatchIds.removeLast()
        return true
    }

    private companion object {
        const val DEDUP_LIMIT = 50

        fun Throwable.toRpcError(): RpcError {
            if (this is RpcError) return this
            RpcLog.log("handler threw: ${javaClass.simpleName}: $message")
            return RpcError.error(RpcErrors.UNKNOWN_ERROR, message ?: "Unknown Error")
        }

        fun JSONObject.matches(other: JSONObject): Boolean {
            val keys = keys().asSequence().toSet()
            val otherKeys = other.keys().asSequence().toSet()
            return keys == otherKeys && keys.all { opt(it)?.toString() == other.opt(it)?.toString() }
        }
    }
}
