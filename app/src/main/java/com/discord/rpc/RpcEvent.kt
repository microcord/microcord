package com.discord.rpc

import org.json.JSONObject

/**
 * A subscribable event. [handle] runs at `SUBSCRIBE` time and returns the updater the server re-runs
 * on every store change; the updater dispatches itself and returns the state to compare next time.
 */
interface RpcEvent {
    val scope: RpcScopeConfig? get() = null

    fun validate(args: JSONObject) = Unit

    fun handle(socket: RpcSocket, args: JSONObject): ((RpcSubscription) -> Any?)? = null
}

open class PushOnlyRpcEvent(override val scope: RpcScopeConfig? = null) : RpcEvent
