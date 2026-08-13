package com.discord.rpc

import com.discord.app.AppLog
import org.json.JSONObject

/** Frame-level tracing, off by default because every RPC frame passes through it. */
object RpcLog {
    const val TAG = "DiscordRpc"

    @Volatile
    var enabled: Boolean = false

    fun log(message: String) {
        if (enabled) AppLog.i(TAG, message)
    }

    /** Strips secrets from a frame. A string `code` is an authorization code, a numeric one a close code. */
    fun redact(payload: Any?): String {
        val json = payload as? JSONObject ?: return payload.toString()
        val secretCode = json.opt("code") is String
        if (!json.has("access_token") && !secretCode) return json.toString()
        return JSONObject(json.toString()).apply {
            if (has("access_token")) put("access_token", "<removed>")
            if (secretCode) put("code", "<removed>")
        }.toString()
    }
}
