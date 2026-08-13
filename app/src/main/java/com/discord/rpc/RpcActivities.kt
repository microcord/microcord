package com.discord.rpc

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.stores.StoreStream
import com.google.gson.stream.JsonReader
import java.io.StringReader
import org.json.JSONObject

/** The activities applications have published over RPC, held per socket. */
object RpcActivities {

    private val bySocket = LinkedHashMap<String, Activity>()

    @Synchronized
    fun set(socketId: String, activity: Activity?) {
        val previous = bySocket[socketId]
        // Integrations republish presence on a timer; the gateway rate limits presence updates.
        if (previous == activity) return
        if (activity == null) bySocket.remove(socketId) else bySocket[socketId] = activity
        // Clearing still has to name the type slot being emptied.
        StoreStream.setRpcActivity(activity?.type ?: previous?.type ?: ActivityType.PLAYING, activity)
    }

    fun parse(activity: JSONObject): Activity? = runCatching {
        InboundGatewayGsonParser.fromJson(JsonReader(StringReader(activity.toString())), Activity::class.java)
    }.getOrNull()
}
